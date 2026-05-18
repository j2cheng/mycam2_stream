# Video Stream Configuration & Verified Results

This document captures the current encoder/streaming configuration of the
headless `StreamerService` and the verified runtime behaviour observed on
device.

## Pipeline summary

```
Camera2 (TEMPLATE_RECORD, back camera, IMPLEMENTATION_DEFINED 3840x2160)
  │
  ▼  zero-copy BufferQueue
MediaCodec (c2.qti.hevc.encoder, COLOR_FormatSurface)
  │
  ▼  Annex-B byte-stream, one NAL per output buffer
JNI nativePushH265Data (caches CSD, stamps PTS)
  │
  ▼  GstAppSrc (is-live=TRUE, do-timestamp=FALSE, alignment=nal, min/max-latency=0)
h265parse config-interval=1
  │
  ▼  video/x-h265,stream-format=byte-stream,alignment=au
queue leaky=downstream
  │
  ▼
rtph265pay pt=96 config-interval=1
  │
  ▼  RTP/UDP
gst-rtsp-server → rtsp://<device-ip>:8554/live
```

## Current encoder settings

Defined in [`CameraRtspServer.java`](../app/src/main/java/com/j2cheng/cam2stream/CameraRtspServer.java):

| Setting | Value |
|---|---|
| Codec | HEVC (`video/hevc`) |
| Resolution | 3840 × 2160 (4K UHD) |
| Frame rate | 30 fps |
| Bitrate | 15 000 000 bps (15 Mbps) |
| I-frame interval | 1 s |
| Color format | `COLOR_FormatSurface` (zero-copy Camera2 → encoder) |
| Profile | not pinned (encoder auto-selects) |
| Level   | not pinned (encoder auto-selects) |

## RTSP endpoint

| Field | Value |
|---|---|
| Mount path | `/live` |
| Port | 8554 |
| URL | `rtsp://<device-ip>:8554/live` |
| Transport modes offered | PLAY only (no record) |
| Shared media | yes (one pipeline, multiple clients) |

## Verified encoder output (device run, 2026-05-18)

`adb logcat -s CameraRtspServer:I` captured the encoder's negotiated
`MediaFormat` right after `MediaCodec.start()`:

```
encoder negotiated format: {
    mime                          = video/hevc,
    width                         = 3840,
    height                        = 2160,
    crop-left                     = 0,
    crop-top                      = 0,
    crop-right                    = 3839,
    crop-bottom                   = 2159,
    frame-rate                    = 30,
    bitrate                       = 15000000,
    max-bitrate                   = 15000000,
    bitrate-mode                  = 1,         // VBR (0=CQ, 1=VBR, 2=CBR)
    profile                       = 1,         // HEVCProfileMain
    color-standard                = 2,         // BT.709
    color-transfer                = 3,         // SDR
    color-range                   = 2,         // limited (TV)
    priority                      = 0,         // realtime
    intra-refresh-period          = 0,
    prepend-sps-pps-to-idr-frames = 0,         // encoder does NOT inline param sets
    feature-secure-playback       = 0,
    hdr10-plus-info               = (1 byte placeholder),
    video-qp-average              = 0,
    csd-0                         = HeapByteBuffer lim=89  // VPS+SPS+PPS bundle
}
```

Followed shortly by:

```
rtsp_server: cached CSD (89 bytes)
```

### Field-by-field interpretation

> **Note**: every value below is what the encoder **chose on its own** from
> the three inputs we provide (resolution, frame-rate, bitrate). We do **not**
> pin profile, level, bitrate-mode, or colour metadata. Nothing here is a
> restriction we imposed — it's the hardware's auto-selected defaults.

| Key | Reported value | Meaning |
|---|---|---|
| `mime` | `video/hevc` | HEVC / H.265 |
| `width` × `height` | 3840 × 2160 | 4K UHD as requested |
| `crop-right`, `crop-bottom` | 3839, 2159 | full frame, no padding cropped (encoder reports inclusive coordinates) |
| `crop-left`, `crop-top` | 0, 0 | no top/left crop |
| `frame-rate` | 30 | 30 fps as requested |
| `bitrate` | 15 000 000 | 15 Mbps target as requested |
| `max-bitrate` | 15 000 000 | VBR cap pinned at the target |
| `bitrate-mode` | **1** | **VBR** (0 = CQ, 1 = VBR, 2 = CBR) — encoder's default |
| `profile` | **1** | **HEVC Main** profile (8-bit 4:2:0) — encoder's default |
| `level` | *(not reported)* | encoder did not surface it; carried inside `csd-0` SPS — expected 5.0 (`general_level_idc=150`) |
| `color-standard` | 2 | BT.709 |
| `color-transfer` | 3 | SDR (BT.709 gamma) |
| `color-range` | 2 | Limited / TV range (16–235) |
| `priority` | 0 | realtime priority |
| `intra-refresh-period` | 0 | disabled (we use `KEY_I_FRAME_INTERVAL` instead — see below) |
| `prepend-sps-pps-to-idr-frames` | **0** | encoder will **not** re-emit VPS/SPS/PPS before each IDR — that's why our JNI CSD cache-and-replay is required |
| `feature-secure-playback` | 0 | not on the DRM/secure path |
| `hdr10-plus-info` | 1-byte placeholder | HDR metadata channel present but unused (we're SDR) |
| `video-qp-average` | 0 | runtime QP telemetry, not yet populated |
| `csd-0` | 89 bytes | the one-shot VPS+SPS+PPS Annex-B bundle |

`I_FRAME_INTERVAL_SEC = 1` is sent via `KEY_I_FRAME_INTERVAL` to the encoder
before `configure()` but does not appear in the negotiated `MediaFormat`
output (it's a configure-only knob, not a queryable property).

The CSD (`csd-0`) is 89 bytes of Annex-B VPS+SPS+PPS. The native side caches it
in `g_csd` and re-pushes it into `appsrc` from `on_media_configure()` every
time a new RTSP client triggers the pipeline. Without that replay, `h265parse`
would never see parameter sets (because `prepend-sps-pps-to-idr-frames=0`) and
would drop every slice with `broken/invalid nal Slice_TRAIL_R`.

### Key observations

- **Profile = Main** (8-bit 4:2:0).
- **Level is not reported** by Qualcomm's `c2.qti.hevc.encoder` in the
  negotiated `MediaFormat`. The actual `general_level_idc` is carried inside
  the SPS bytes of `csd-0`. For 3840 × 2160 @ 30 fps, 15 Mbps the expected
  level is **5.0** (`general_level_idc = 150`).
- **Bitrate mode = VBR**, capped at 15 Mbps.
- **Colour metadata**: BT.709 / SDR / limited range. Standard for sRGB-ish
  video output.
- **`prepend-sps-pps-to-idr-frames=0`** confirms the encoder will not
  re-inject VPS/SPS/PPS at each IDR; our explicit CSD cache-and-replay is
  therefore required for correctness with late-joining clients.

## Verified client playback

| Client | Result |
|---|---|
| ffplay (`-rtsp_transport tcp rtsp://<ip>:8554/live`) | works |
| VLC (RTP-over-RTSP/TCP) | works |
| GStreamer (`gst-play-1.0 rtsp://<ip>:8554/live`) | works |

The `broken/invalid nal` warnings from `h265parse` cease after the CSD-replay
fix, and `rtpsession`'s "Can't determine running time" warning is silenced by
setting `min-latency=0, max-latency=0` on `appsrc`.

## How to change resolution / bitrate / FPS

Edit the constants at the top of
[`CameraRtspServer.java`](../app/src/main/java/com/j2cheng/cam2stream/CameraRtspServer.java):

```java
private static final int WIDTH = 3840;
private static final int HEIGHT = 2160;
private static final int BITRATE = 15_000_000;
private static final int FRAME_RATE = 30;
private static final int I_FRAME_INTERVAL_SEC = 1;
```

Suggested HEVC bitrate ranges:

| Resolution / FPS | Acceptable | Good | Visually transparent |
|---|---|---|---|
| 1920 × 1080 @ 30 | 2–4 Mbps | 4–8 Mbps | 8–12 Mbps |
| 2560 × 1440 @ 30 | 4–8 Mbps | 8–15 Mbps | 15–25 Mbps |
| 3840 × 2160 @ 30 | 8–15 Mbps | 15–25 Mbps | 25–40 Mbps |
| 3840 × 2160 @ 60 | 15–25 Mbps | 25–40 Mbps | 40–60 Mbps |

After editing, rebuild and reinstall:

```bash
scripts/build-app.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start-service          -n com.j2cheng.cam2stream/.StreamerService \
                                    -a com.j2cheng.cam2stream.action.STOP
adb shell am start-foreground-service -n com.j2cheng.cam2stream/.StreamerService \
                                      -a com.j2cheng.cam2stream.action.START
```

Watch the new `encoder negotiated format: ...` line to confirm the encoder
accepted the new parameters.
