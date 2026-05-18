# Architecture

Headless Android service that captures from Camera2, encodes to H.265 with
`MediaCodec` on a hardware input Surface, and serves the encoded bitstream as
an RTSP stream over LAN via `gst-rtsp-server`. No Activity, no UI, no window
is ever shown.

---

## 1. Component structure

```
mycam2_stream/
├── app/
│   ├── build.gradle                       # AGP 8.5, ndk-build, arm64-v8a only
│   └── src/main/
│       ├── AndroidManifest.xml            # service + boot receiver, NO activity
│       ├── java/
│       │   ├── com/j2cheng/cam2stream/
│       │   │   ├── StreamerService.java   # foreground service (type=camera)
│       │   │   ├── BootReceiver.java      # auto-start on BOOT_COMPLETED
│       │   │   └── CameraRtspServer.java  # Camera2 + MediaCodec + JNI bridge
│       │   └── org/freedesktop/gstreamer/
│       │       └── GStreamer.java         # vendored SDK helper (GStreamer.init)
│       └── jni/
│           ├── Android.mk                 # plugin list, includes gstreamer-1.0.mk
│           ├── Application.mk             # APP_ABI=arm64-v8a, APP_PLATFORM=29
│           └── rtsp_server.c              # gst-rtsp-server hosted in C
├── third_party/
│   ├── cerbero/                           # GStreamer build system (submodule-like)
│   └── gstreamer-android/                 # Built SDK: static .a plugins + headers
│       ├── include/  lib/  share/  etc/
└── scripts/
    └── build-gstreamer-android.sh         # Cerbero → extracted SDK tarball
```

### Process / thread layout at runtime

```
                       ┌──────────────────────────────────────────┐
                       │  Android process: com.j2cheng.cam2stream │
                       └──────────────────────────────────────────┘
                                          │
        ┌─────────────────────────────────┼─────────────────────────────────┐
        │                                 │                                 │
   Main thread                  Camera2 callback thread             MediaCodec drain
   (Service lifecycle)          (CameraDevice.StateCallback,        thread
        │                        capture-session callbacks)         (CameraRtspServer
        │                                 │                          .drainLoop)
   onCreate/onStart                       │                                 │
   buildNotification                      │                                 │
        │                                 ▼                                 ▼
        ▼                          MediaCodec input          dequeueOutputBuffer()
   startForeground               Surface (HW BufferQueue)    → JNI nativePushH265Data
        │                                                                   │
        └──────────────────────────► JNI ◄──────────────────────────────────┘
                                     │
                                     ▼
                       ┌─────────────────────────────┐
                       │ Native RTSP loop thread     │
                       │ (pthread, own GMainContext) │
                       │   gst-rtsp-server :8554     │
                       │   factory "/live"           │
                       │   per-client pipeline:      │
                       │   appsrc → h265parse →      │
                       │   queue → rtph265pay        │
                       └─────────────────────────────┘
                                     │
                                     ▼
                         RTSP/RTP over UDP (LAN clients)
```

Threads in detail:
- **Main / service thread** — Android lifecycle, notifications.
- **Camera2 callback thread** — `HandlerThread "cam-bg"` owned by
  `CameraRtspServer`. Opens the camera, configures the capture session, drives
  repeating capture into the encoder's input Surface.
- **MediaCodec drain thread** — Plain `Thread`, polls
  `MediaCodec.dequeueOutputBuffer(...)`, copies each encoded NAL unit into a
  `byte[]`, calls `nativePushH265Data(...)` across JNI.
- **RTSP loop thread** — `pthread` created in `rtsp_server.c`. Owns its own
  `GMainContext` / `GMainLoop` so the RTSP server runs independently of
  Android's looper.

---

## 2. Program flow (lifecycle)

### Cold start (boot)

```
Device boot
   │
   ▼
Android broadcasts BOOT_COMPLETED
   │
   ▼
BootReceiver.onReceive
   │  ContextCompat.startForegroundService(StreamerService, ACTION_START)
   ▼
StreamerService.onCreate
   │  • create notification channel "streamer-fg" (IMPORTANCE_LOW, silent)
   │  • GStreamer.init(this)            ← initializes GLib/GStreamer
   │  • new CameraRtspServer(this)
   ▼
StreamerService.onStartCommand(ACTION_START)
   │  • startForeground(NOTIF_ID, n, FOREGROUND_SERVICE_TYPE_CAMERA)
   │  • server.start()                  ← see "Streaming start" below
   │  • updateNotification("Streaming on 8554/live")
   ▼
return START_STICKY     (Android restarts service if killed)
```

### Cold start (manual / OEM with blocked auto-start)

```
adb shell am start-foreground-service \
    -n com.j2cheng.cam2stream/.StreamerService \
    -a com.j2cheng.cam2stream.action.START
```
… then identical flow to "boot" from `StreamerService.onCreate` onwards.

### Streaming start (`CameraRtspServer.start()`)

```
start()
 │
 ├─ nativeStartRtspServer(8554, "/live")     [JNI → rtsp_server.c]
 │     │
 │     ├─ gst_init(NULL,NULL)
 │     ├─ create GstRTSPServer, set service "8554"
 │     ├─ create GstRTSPMediaFactory
 │     │     factory launch string:
 │     │       ( appsrc name=mysrc is-live=true do-timestamp=false format=time
 │     │         ! h265parse config-interval=1
 │     │         ! video/x-h265,stream-format=byte-stream,alignment=au
 │     │         ! queue max-size-buffers=10 leaky=downstream
 │     │         ! rtph265pay name=pay0 config-interval=1 pt=96 )
 │     │     set_transport_mode(PLAY)      ← send-only
 │     │     set_shared(TRUE)              ← single pipeline shared by clients
 │     │     connect "media-configure"     → on_media_configure
 │     ├─ mount "/live" → factory
 │     ├─ attach server to GMainContext
 │     └─ spawn pthread loop_thread_main → g_main_loop_run
 │
 ├─ configureEncoder()
 │     • MediaFormat: video/hevc 1920x1080 30fps, bitrate 4.5 Mbps,
 │                    color=Surface, key-frame-interval ~1s
 │     • encoder.configure(format, null, null, CONFIGURE_FLAG_ENCODE)
 │     • encoderInputSurface = encoder.createInputSurface()
 │     • encoder.start()
 │
 ├─ pickBackCamera()  (CameraManager.getCameraIdList → LENS_FACING_BACK)
 │
 ├─ openCameraAndBindSurface()
 │     • cameraManager.openCamera(camId, stateCallback, camHandler)
 │     • onOpened → createCaptureSession([encoderInputSurface], ...)
 │     • onConfigured → createCaptureRequest(TEMPLATE_RECORD)
 │                       .addTarget(encoderInputSurface)
 │                       setRepeatingRequest(...)
 │       ▼
 │       camera frames now flow directly into MediaCodec's input Surface
 │       (HW BufferQueue, no CPU copy of pixels)
 │
 └─ startOutputDrainLoop()
       thread loop:
         idx = encoder.dequeueOutputBuffer(info, 10ms)
         if idx == INFO_OUTPUT_FORMAT_CHANGED:
             csd = format.getByteBuffer("csd-0")   ← VPS+SPS+PPS
             nativePushH265Data(csd, len, 0, false, /*isCodecConfig*/true)
         elif idx >= 0:
             buf = encoder.getOutputBuffer(idx)
             isKey = (info.flags & BUFFER_FLAG_KEY_FRAME) != 0
             pts   = info.presentationTimeUs - firstFrameTimeUs   ← anchor at 0
             nativePushH265Data(bytes, len, pts, isKey, false)
             encoder.releaseOutputBuffer(idx, false)
```

### RTSP client connects

```
Client: PLAY rtsp://device:8554/live
        │
        ▼
gst-rtsp-server: factory.create_element() → instantiate pipeline (first client only;
                 set_shared(TRUE) → reused for any subsequent client)
        │
        ▼
"media-configure" signal → on_media_configure(media)
        │
        ├─ find appsrc "mysrc" in media's element
        ├─ g_object_set caps =
        │     "video/x-h265, stream-format=byte-stream, alignment=nal"
        │     (MediaCodec emits one NAL per output buffer; h265parse
        │      re-aggregates to AU for the rtph265pay downstream)
        ├─ is-live=TRUE, do-timestamp=FALSE, format=GST_FORMAT_TIME
        ├─ pthread_mutex_lock(&g_lock); g_appsrc = ref(appsrc); unlock
        └─ connect signal "unprepared" → on_media_unprepared
              (drops g_appsrc when media tears down)

Subsequent nativePushH265Data() calls:
        │
        ▼
   pthread_mutex_lock(&g_lock)
   if (g_appsrc) {
       GstBuffer *buf = gst_buffer_new_memdup(data, size)
       if (isCodecConfig) {
           GST_BUFFER_PTS(buf) = 0                      ← VPS/SPS/PPS on timeline
           GST_BUFFER_DTS(buf) = 0
           // no HEADER flag: h265parse caches param sets from byte stream,
           // config-interval=1 re-injects before every keyframe
       } else {
           GST_BUFFER_PTS(buf) = ptsUs * 1000           ← ns, anchored at 0
           GST_BUFFER_DTS(buf) = GST_BUFFER_PTS(buf)
           if (!isKeyframe) GST_BUFFER_FLAG_SET(buf, GST_BUFFER_FLAG_DELTA_UNIT)
       }
       gst_app_src_push_buffer(g_appsrc, buf)           ← buffer ownership transfers
   }
   pthread_mutex_unlock(&g_lock)
        │
        ▼
   appsrc → h265parse (re-aggregates NAL→AU, injects VPS/SPS/PPS via config-interval=1)
          → caps filter (byte-stream, alignment=au)
          → queue (leaky=downstream; drops if RTSP backpressures)
          → rtph265pay (RFC 7798 packetization, pt=96, also config-interval=1)
          → RTSP server → RTP/UDP → client
```

### Stop

```
adb ... -a com.j2cheng.cam2stream.action.STOP
   │
   ▼
StreamerService.onStartCommand(ACTION_STOP)
   │
   ▼
server.stop()
   │  • cameraDevice.close(); captureSession.close()
   │  • encoder.stop(); encoder.release()
   │  • drainThread.interrupt()
   │  • nativeStopRtspServer()
   │       - g_main_loop_quit(loop)
   │       - pthread_join(loop_thread)
   │       - unref factory, server, mounts
   │       - clear g_appsrc (under g_lock)
   ▼
stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
```

### Crash / OOM recovery

`onStartCommand` returns `START_STICKY`. If the process is killed (OOM, force
stop replacement, etc.), Android re-creates the service with a null intent,
which `StreamerService.onStartCommand` treats as `ACTION_START`. Camera and
encoder are re-acquired from scratch.

---

## 3. Data flow (the pixels)

```
   ┌────────────┐  raw YUV in        ┌────────────┐  HEVC NAL units
   │  Camera2   │ ─ HW BufferQueue ─►│ MediaCodec │ ───────────────┐
   │ (Surface   │  (zero CPU copy)   │  HEVC enc  │                │
   │  consumer  │                    │  (hardware │                │
   │  is the    │                    │  encoder)  │                │
   │  encoder)  │                    └────────────┘                │
   └────────────┘                                                  │
                                                                   ▼
                                                  ┌────────────────────────────┐
                                                  │ Java drain thread:         │
                                                  │  ByteBuffer → byte[]       │
                                                  │  + ptsUs + flags           │
                                                  └────────────────────────────┘
                                                                   │
                                                                   │  JNI call
                                                                   │  (one per AU)
                                                                   ▼
                                                  ┌────────────────────────────┐
                                                  │ rtsp_server.c              │
                                                  │  gst_buffer_new_memdup     │
                                                  │  set PTS/DTS, flags        │
                                                  │  gst_app_src_push_buffer   │
                                                  └────────────────────────────┘
                                                                   │
                                                                   ▼
                                            appsrc → h265parse → queue → rtph265pay
                                                                   │
                                                                   ▼
                                                            RTSP server :8554
                                                                   │
                                                                   ▼
                                                            RTP/UDP to client(s)
```

Key data properties:
- **Pixel data is never copied by the CPU.** Camera frames travel through a
  Surface (BufferQueue) directly into the hardware HEVC encoder.
- **Only the compressed bitstream crosses JNI** — typically tens of KB per
  frame at 4.5 Mbps / 30 fps, well under a megabyte per second total.
- The JNI boundary copies once (`gst_buffer_new_memdup`) so GStreamer fully
  owns the buffer's lifetime independently of the Java `byte[]`.
- **PTS anchoring**: the first encoded frame's `presentationTimeUs` is
  recorded as `firstFrameTimeUs`; every subsequent PTS is offset by that, so
  the stream starts at PTS 0 ns. This avoids huge timestamps confusing
  clients.
- **CSD (codec-specific data)** is the VPS+SPS+PPS prepended by MediaCodec.
  It is pushed once at format-change time with `GST_BUFFER_FLAG_HEADER`. In
  addition, `h265parse config-interval=1` and `rtph265pay config-interval=1`
  re-inject VPS/SPS/PPS roughly every second so late-joining RTSP clients can
  decode without waiting for a new IDR.
- **Backpressure**: the `queue` is `leaky=downstream` with
  `max-size-buffers=10`. If the network can't keep up, oldest encoded frames
  are dropped rather than blocking the camera/encoder pipeline.

Pipeline string (native side):
```
( appsrc name=mysrc is-live=true do-timestamp=false format=time
  ! h265parse config-interval=1
  ! queue max-size-buffers=10 leaky=downstream
  ! rtph265pay name=pay0 config-interval=1 pt=96 )
```

---

## 4. Build-time data flow

```
Cerbero recipes (third_party/cerbero)
        │
        │  scripts/build-gstreamer-android.sh
        │  (Cerbero package → tarball)
        ▼
third_party/gstreamer-android/                    ← extracted SDK
  ├── include/   (headers for gst, glib, gst-rtsp-server, ...)
  ├── lib/       (static .a plugins + libgstreamer-1.0.a, etc.)
  ├── share/gst-android/ndk-build/                 ← key
  │     ├── GStreamer.java          (vendored into app)
  │     ├── gstreamer-1.0.mk        (included by app's Android.mk)
  │     ├── gstreamer_android-1.0.c.in   (template)
  │     └── plugins.mk
  └── etc/

                              │
                              │  ndk-build (run by AGP externalNativeBuild)
                              ▼
   app/build/intermediates/cxx/.../obj/local/arm64-v8a/
        ├── libgstreamer_android.so   ← generated per-app; bundles selected
        │                                static plugins + glue init code
        └── libappnative.so           ← from rtsp_server.c, links against
                                        libgstreamer_android.so

                              │
                              │  AGP packages .so into APK under
                              │  lib/arm64-v8a/
                              ▼
                          app-debug.apk
```

How `gstreamer-1.0.mk` works (high level):
1. App's `Android.mk` sets `GSTREAMER_PLUGINS`, `GSTREAMER_EXTRA_DEPS`, etc.
2. Includes `$(GSTREAMER_NDK_BUILD_PATH)/gstreamer-1.0.mk`.
3. That file:
   - expands the plugin list into static .a inputs,
   - generates `gstreamer_android-1.0.c` from the `.in` template with the
     correct `GST_PLUGIN_STATIC_DECLARE` / `_REGISTER` lines for the chosen
     plugins,
   - declares a `gstreamer_android` shared library module that links
     everything and exposes the init function called by
     `GStreamer.init(Context)`.

---

## 5. Permissions and Android policy

| Permission                              | Purpose                                                       | How granted |
|-----------------------------------------|---------------------------------------------------------------|-------------|
| `CAMERA`                                | Open the back camera                                          | `adb shell pm grant` (no Activity to prompt) |
| `INTERNET`                              | Bind UDP sockets / accept RTSP TCP                            | Install-time |
| `FOREGROUND_SERVICE`                    | Required to call `startForeground` (Android 9+)               | Install-time |
| `FOREGROUND_SERVICE_CAMERA`             | Required to use the camera from an FGS (Android 14+)          | Install-time |
| `POST_NOTIFICATIONS`                    | Show the mandatory FGS notification (Android 13+)             | `adb shell pm grant` |
| `RECEIVE_BOOT_COMPLETED`                | Allow `BootReceiver` to fire after boot                       | Install-time |

The service's `foregroundServiceType="camera"` in the manifest must match the
type passed to `startForeground(...)` (`FOREGROUND_SERVICE_TYPE_CAMERA`) or
Android 14+ throws `SecurityException` and refuses to start the camera.

---

## 6. Why this shape

- **Camera2 + MediaCodec Surface (instead of a pure GStreamer pipeline with
  `ahcsrc` / hypothetical `ahc2src`)**: zero-copy hardware path is only
  reachable via Camera2 → MediaCodec input Surface. GStreamer has no Camera2
  source element in 1.24, and `ahcsrc` (Camera1) is deprecated and CPU-copies.
- **`gst-rtsp-server` (instead of hand-rolled RTSP)**: gives us SDP
  negotiation, RTP packetization, multiple-client fanout, retransmit support,
  and RTCP for free, with one factory launch string.
- **`appsrc` between Java and the RTSP factory**: lets Java own capture/encode
  policy (resolution, bitrate, exposure, key-frame requests) while keeping the
  network side fully inside GStreamer's main loop.
- **Headless / service-only**: requirement from the operator — the device
  exists to be a camera, not to be touched. No Activity means no window can
  ever appear.
- **ndk-build (instead of CMake)**: GStreamer 1.24's Android integration
  shipped only the ndk-build `.mk` files. CMake support (`FindGStreamerMobile`)
  is a newer addition not present in this SDK.
- **arm64-v8a only**: every modern Android camera device is 64-bit; dropping
  other ABIs shrinks the APK by avoiding extra copies of `libgstreamer_android.so`.
