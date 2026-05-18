# GStreamer Android Build Notes

## Goal

Build the GStreamer Android SDK with [Cerbero](https://gitlab.freedesktop.org/gstreamer/cerbero), extract it into `third_party/gstreamer-android`, and link it into the app via `ndk-build` so a per-app `libgstreamer_android.so` can be produced.

## Important: GStreamer 1.24 ships only static plugins

The GStreamer Android SDK does **not** include a prebuilt `libgstreamer_android.so`. Instead it ships:

- static plugin archives (`lib/gstreamer-1.0/libgst*.a`)
- static GStreamer libraries (`lib/libgstreamer-1.0.a`, `lib/libgstrtspserver-1.0.a`, ...)
- ndk-build glue under `share/gst-android/ndk-build/`

Including `gstreamer-1.0.mk` from your `Android.mk` causes ndk-build to:

1. Pick the static plugins listed in `GSTREAMER_PLUGINS`
2. Generate a `gstreamer_android-1.0.c` registration file from a template
3. Link everything into a single shared library (`libgstreamer_android.so`) that your app loads with `System.loadLibrary("gstreamer_android")`

This is why the project uses `ndk-build` (via `externalNativeBuild { ndkBuild { ... } }`) rather than CMake — the official GStreamer Android integration for 1.24.x is `ndk-build` only. CMake support (`find_package(GStreamerMobile ...)`) only landed in newer GStreamer releases.

## Environment

- Java 17 on `PATH` (for Gradle)
- The Android SDK is needed only to build the app, not to build GStreamer
- The Android NDK is fetched automatically by `cerbero bootstrap` if `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT` are unset
- Linux host with `git`, `python3`, `tar`, and standard build tools

## Build Command

From the repository root:

```bash
./scripts/build-gstreamer-android.sh
```

This will:

1. Clone Cerbero `1.24.10` into `third_party/cerbero`
2. Run `cerbero bootstrap` (downloads NDK r25c, Rust toolchain, etc.)
3. Run `cerbero package gstreamer-1.0` for `cross-android-arm64.cbc`
4. Locate the produced `gstreamer-1.0-android-arm64-1.24.10.tar.xz` at the cerbero source root
5. Extract it into `third_party/gstreamer-android/`

`CERBERO_HOME` defaults to `$HOME/cerbero-home` to keep the workspace small. Override with `CERBERO_HOME=/some/path` if needed.

The full build takes a long time and produces a ~3 GB extracted SDK.

## Useful Overrides

```bash
CERBERO_REF=1.24.12 ./scripts/build-gstreamer-android.sh
CERBERO_JOBS=4 ./scripts/build-gstreamer-android.sh
OUTPUT_DIR=$PWD/third_party/gstreamer-android ./scripts/build-gstreamer-android.sh
```

`CERBERO_JOBS` defaults to `2` because the linker for some recipes can need ~3 GB per job.

## App Integration

Export the SDK location before invoking Gradle:

```bash
export GSTREAMER_ROOT_ANDROID="$PWD/third_party/gstreamer-android"
./gradlew :app:assembleDebug
```

`app/build.gradle` forwards this to ndk-build through `externalNativeBuild.ndkBuild.arguments`.

The app is **headless**: it has no Activity, no launcher entry, and never shows a window. The only runtime components are a foreground service and a boot receiver. The mandatory Android foreground-service notification is the only visible artifact, and it lives in the notification shade only (`IMPORTANCE_LOW`, silent, no contentIntent).

Build pieces:

- `app/src/main/jni/Android.mk` — declares `appnative` (our JNI lib), lists the GStreamer plugins to bundle into `libgstreamer_android.so`, and includes `gstreamer-1.0.mk` from the SDK.
- `app/src/main/jni/Application.mk` — `APP_ABI := arm64-v8a`, `APP_PLATFORM := android-29`, `APP_STL := c++_shared`.
- `app/src/main/jni/rtsp_server.c` — JNI entry points + `gst-rtsp-server` setup (appsrc → h265parse → rtph265pay), implemented in C.
- `app/src/main/java/org/freedesktop/gstreamer/GStreamer.java` — vendored from `share/gst-android/ndk-build/GStreamer.java`. Provides `GStreamer.init(Context)`.
- `app/src/main/java/com/j2cheng/cam2stream/CameraRtspServer.java` — Camera2 (`TEMPLATE_RECORD`) → `MediaCodec.createInputSurface()` HEVC encoder → drains encoded NAL units into `nativePushH265Data(...)`. Zero-copy of pixels; only the compressed bitstream crosses JNI.
- `app/src/main/java/com/j2cheng/cam2stream/StreamerService.java` — `Service` with `foregroundServiceType="camera"`. Loads `gstreamer_android` + `appnative`, calls `GStreamer.init(this)`, owns the `CameraRtspServer`. Returns `START_STICKY`.
- `app/src/main/java/com/j2cheng/cam2stream/BootReceiver.java` — listens for `BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED`. Auto-starts the service.

## Running the headless service

Because there is no Activity, permissions cannot be prompted for. They must be pre-granted, typically by the operator over ADB after install. The required permissions are:

- `android.permission.CAMERA` — needed at the moment the service calls `startForeground(..., FOREGROUND_SERVICE_TYPE_CAMERA)`.
- `android.permission.POST_NOTIFICATIONS` — Android 13+, for the FGS notification.

Install + grant + start:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.j2cheng.cam2stream android.permission.CAMERA
adb shell pm grant com.j2cheng.cam2stream android.permission.POST_NOTIFICATIONS

# Start manually:
adb shell am start-foreground-service \
    -n com.j2cheng.cam2stream/.StreamerService \
    -a com.j2cheng.cam2stream.action.START

# Or reboot — BootReceiver will start it automatically:
adb reboot
```

Stop:

```bash
adb shell am start-service \
    -n com.j2cheng.cam2stream/.StreamerService \
    -a com.j2cheng.cam2stream.action.STOP
```

Stream URL: `rtsp://<device-ip>:8554/live` (HEVC / RTP payload type 96). Test with any RTSP client:

```bash
ffplay -fflags nobuffer -flags low_delay rtsp://<device-ip>:8554/live
# or
gst-play-1.0 rtsp://<device-ip>:8554/live
```

### OEM autostart caveat

On stock AOSP / Pixel firmware, `BootReceiver` runs on every boot and the service comes up automatically. On some OEM firmwares (Xiaomi, Huawei, OPPO, …) `BOOT_COMPLETED` is blocked until the user has opened the app at least once from the launcher. Since this app has no launcher entry, on those devices you must trigger the first start manually with `adb shell am start-foreground-service ...` once per install; subsequent reboots will then autostart.

### Plugins currently bundled

See `GSTREAMER_PLUGINS` in `app/src/main/jni/Android.mk`. The selection targets a Camera2 → MediaCodec H.265 → RTSP server pipeline:

- `coreelements`, `app`, `typefindfunctions` (core, appsrc/appsink)
- `videoparsersbad` (h265parse)
- `rtp`, `rtpmanager`, `rtsp`, `udp` (RTP packaging + RTSP/UDP transport)

Plus the `gstreamer-rtsp-server-1.0` library (a library, not a plugin) declared via `GSTREAMER_EXTRA_DEPS`.

If you add features (e.g. playback, software encoding fallback), extend that list and rebuild — no need to rerun Cerbero.

## Verifying the SDK

After `./scripts/build-gstreamer-android.sh` completes:

```bash
ls third_party/gstreamer-android
# -> etc  include  lib  share

ls third_party/gstreamer-android/lib/gstreamer-1.0 | head
# -> libgstapp.a  libgstcoreelements.a  libgstrtp.a  ...

ls third_party/gstreamer-android/share/gst-android/ndk-build
# -> GStreamer.java  gstreamer-1.0.mk  gstreamer_android-1.0.c.in  plugins.mk  ...
```

`libgstreamer_android.so` itself appears later, under `app/build/intermediates/ndkBuild/.../obj/local/arm64-v8a/`, after the first successful `:app:externalNativeBuildDebug` run.
