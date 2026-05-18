# Android Camera2 + H.265 + GStreamer RTSP App

## Goal

Build an Android application that:

1. Captures video using the Android Camera2 API.
2. Encodes video to H.265/HEVC using `MediaCodec`.
3. Streams video out using GStreamer, likely via `gst-rtsp-server`.
4. Uses mixed Java and C++ code through JNI because GStreamer is native. Java only — no Kotlin.
5. Builds `libgstreamer-android.so` first, using Cerbero as the GStreamer build system.

The Gradle build uses the Groovy DSL (`build.gradle`, `settings.gradle`). No Kotlin DSL (`.kts`) files are used.

## Initial Scope

The first milestone is not the full app.

The first milestone is:

- establish the Android project structure,
- define the native/JNI boundary,
- obtain and build the GStreamer Android libraries,
- prepare for integrating `gst-rtsp-server` into the Android app.

## Proposed Architecture

### Android side

- Camera capture with Camera2
- Surface/Buffer handling for encoder input
- HEVC encoding using `MediaCodec`
- App lifecycle, permissions, preview, and control UI

### Native side

- JNI bridge between Android and GStreamer
- GStreamer initialization and runtime management
- RTSP server pipeline setup with `gst-rtsp-server`
- Buffer ingestion path for encoded frames or an alternate pipeline path if required by GStreamer constraints

## Important Technical Note

There is one design point we need to validate early:

- `MediaCodec` configured for H.265 will produce HEVC bitstream.
- `gst-rtsp-server` can absolutely serve HEVC, but the RTSP pipeline and payloader must be HEVC-oriented, for example `rtph265pay`.
- Your note saying "streamer to get 264 data and send out" conflicts with the H.265 requirement. If the intended output is H.265, we should stream HEVC. If the intended output must be H.264, then the encoder choice should change to AVC/H.264.

For now, this project plan assumes:

- Camera2 capture
- `MediaCodec` HEVC encoding
- GStreamer RTSP serving of H.265 using `gst-rtsp-server`

## Recommended Development Phases

### Phase 1: Build native GStreamer Android libraries

- set up Android app skeleton with NDK + CMake
- build GStreamer Android artifacts using Cerbero
- confirm availability of core GStreamer libraries and `gst-rtsp-server`
- define how generated `.so` files and headers will be consumed by the app

### Phase 2: Native integration

- create JNI bootstrap library
- initialize GStreamer from Android
- build a minimal RTSP server in native code
- verify GStreamer starts correctly on device

### Phase 3: Encoder path

- create Camera2 capture session
- feed frames into `MediaCodec`
- extract encoded HEVC access units
- hand encoded data to native layer for streaming

### Phase 4: End-to-end streaming

- connect encoded stream into GStreamer appsrc or another suitable ingestion path
- packetize with HEVC payloader
- expose RTSP endpoint
- test with VLC, ffplay, or GStreamer client

## Immediate Plan

1. Create the Android project scaffold.
2. Add native C++/JNI support from the start.
3. Add build documentation for Cerbero and GStreamer Android outputs.
4. Prepare a dedicated native module for GStreamer startup and RTSP serving.
5. Validate the exact Cerbero target and packaging flow for Android on this environment.

## What I Will Do Next

Next I will:

1. create the initial Android/NDK project structure,
2. add a native library entry point,
3. add build documentation and scripts for the GStreamer/Cerbero step,
4. keep the first implementation focused on the GStreamer side before Camera2 integration.

---

## Building

The repository now contains a working implementation. For full architecture
and runtime details see [docs/architecture.md](docs/architecture.md) and
[docs/gstreamer-android.md](docs/gstreamer-android.md).

### One-time prerequisites

All commands below assume a Linux host (tested on Debian Trixie).

1. **JDK 17.** AGP 8.5 requires a JDK (not a JRE). Debian Trixie ships no
   `openjdk-17-jdk` package, so use Eclipse Temurin:
   ```bash
   mkdir -p ~/jdk && cd ~/jdk
   wget https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/OpenJDK17U-jdk_x64_linux_hotspot_17.0.13_11.tar.gz
   tar xzf OpenJDK17U-jdk_x64_linux_hotspot_17.0.13_11.tar.gz
   ```

2. **Android SDK + NDK.** Download the command-line tools, then install the
   exact components pinned by `app/build.gradle`:
   ```bash
   mkdir -p ~/Android/Sdk/cmdline-tools && cd ~/Android/Sdk/cmdline-tools
   wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
   unzip commandlinetools-linux-11076708_latest.zip
   mv cmdline-tools latest

   export ANDROID_HOME=$HOME/Android/Sdk
   export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
   yes | sdkmanager --licenses
   sdkmanager 'platform-tools' 'platforms;android-34' 'build-tools;34.0.0' 'ndk;25.2.9519653'
   ```

3. **Gradle wrapper.** Already checked in (`./gradlew`, `gradle/wrapper/`).
   First invocation downloads Gradle 8.9 into `~/.gradle/`.

4. **GStreamer Android SDK.** Built once from Cerbero:
   ```bash
   ./scripts/build-gstreamer-android.sh
   ```
   This populates `third_party/gstreamer-android/` (~1 GB of static plugins +
   headers) and patches the SDK's `gstreamer-1.0.mk` so it cooperates with
   AGP's `externalNativeBuild`.

### Build the APK

Use the wrapper script:

```bash
scripts/build-app.sh                # debug APK (default)
scripts/build-app.sh release        # release APK
scripts/build-app.sh clean          # gradle clean
scripts/build-app.sh install        # build + adb install (needs device)
scripts/build-app.sh lint           # lint
scripts/build-app.sh :app:bundleDebug --info   # any explicit task/flags
```

The script:
- defaults `JAVA_HOME=~/jdk/jdk-17.0.13+11`, `ANDROID_HOME=~/Android/Sdk`, and
  `GSTREAMER_ROOT_ANDROID=$repo/third_party/gstreamer-android` (each overridable
  from the environment),
- preflight-checks every required path before invoking Gradle,
- auto-creates `local.properties` with `sdk.dir=$ANDROID_HOME` if missing,
- runs `./gradlew --no-daemon <task>`.

Output APK: `app/build/outputs/apk/debug/app-debug.apk`
(~15 MB, contains `libappnative.so` + `libgstreamer_android.so` for `arm64-v8a`).

### Build directly with Gradle (without the wrapper script)

```bash
export JAVA_HOME=$HOME/jdk/jdk-17.0.13+11
export PATH=$JAVA_HOME/bin:$PATH
export ANDROID_HOME=$HOME/Android/Sdk
export GSTREAMER_ROOT_ANDROID="$PWD/third_party/gstreamer-android"
./gradlew :app:assembleDebug
```

### Install and run

The app is **headless** — no Activity, no launcher icon. Permissions must be
pre-granted over ADB because there is no UI to prompt the user:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.j2cheng.cam2stream android.permission.CAMERA
adb shell pm grant com.j2cheng.cam2stream android.permission.POST_NOTIFICATIONS

# Start the service manually:
adb shell am start-foreground-service \
    -n com.j2cheng.cam2stream/.StreamerService \
    -a com.j2cheng.cam2stream.action.START

# Or reboot — BootReceiver will start it automatically.

# Stop:
adb shell am start-service \
    -n com.j2cheng.cam2stream/.StreamerService \
    -a com.j2cheng.cam2stream.action.STOP
```

Stream URL: `rtsp://<device-ip>:8554/live`. Test with `ffplay`, `gst-play-1.0`,
or VLC.

### Stopping the service

There are several ways to stop the app, listed from cleanest to harshest.

1. **`ACTION_STOP` intent — graceful (recommended).**
   ```bash
   adb shell am start-service \
       -n com.j2cheng.cam2stream/.StreamerService \
       -a com.j2cheng.cam2stream.action.STOP
   ```
   `StreamerService.onStartCommand` short-circuits and runs `stopAll(...)`:
   unregisters the `CameraManager.AvailabilityCallback`, releases the camera +
   `MediaCodec`, tears down the native RTSP server, calls `stopForeground`,
   then `stopSelf`. Returns `START_NOT_STICKY` so Android does NOT re-create
   the service afterwards.

2. **`am stopservice` — also graceful.**
   ```bash
   adb shell am stopservice -n com.j2cheng.cam2stream/.StreamerService
   ```
   The framework invokes `onDestroy()`, which calls the same `stopAll(...)`
   path. Behaviorally equivalent to option 1.

3. **`am force-stop` — harsh, but available.**
   ```bash
   adb shell am force-stop com.j2cheng.cam2stream
   ```
   Kills the entire process. `onDestroy()` is NOT guaranteed to run. The
   camera, sockets, and `libgstreamer_android.so` resources are released by
   the kernel / binder cleanup path when the process dies. Use this only if
   the service is wedged.

4. **`pm disable` — keeps the app stopped across reboots.**
   ```bash
   adb shell pm disable com.j2cheng.cam2stream
   ```
   Stops the service AND prevents `BootReceiver` from auto-starting it on
   subsequent boots. Re-enable with `adb shell pm enable com.j2cheng.cam2stream`.

After any stop, the service will not restart on its own. It comes back only
via another `ACTION_START` intent, or via `BootReceiver` on the next device
reboot.

### Logs

The whole pipeline is heavily instrumented. To watch only the relevant tags:

```bash
adb logcat -s StreamerService:I CameraRtspServer:I rtsp_server:I
```

Tag prefixes:

- `[svc] ...`   — `StreamerService` lifecycle (onCreate, onStartCommand, stopAll, …).
- `[avail] ...` — `CameraManager.AvailabilityCallback` events.
- `[cam] ...`   — `CameraDevice.StateCallback` and `CameraCaptureSession` events.
- `rtsp_server` (native) — gst-rtsp-server start/stop and media-configure /
  media-unprepared transitions.
