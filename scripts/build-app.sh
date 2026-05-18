#!/usr/bin/env bash
#
# Build the headless RTSP streamer APK.
#
# Wraps `./gradlew :app:assembleDebug` with all environment variables the
# build needs. Each variable can be overridden from the caller's environment;
# the defaults match what scripts/build-gstreamer-android.sh and the one-time
# system setup in this dev container produce.
#
# Usage:
#   scripts/build-app.sh                 # debug APK (default)
#   scripts/build-app.sh release         # release APK
#   scripts/build-app.sh clean           # gradle clean
#   scripts/build-app.sh <gradle task>   # any explicit task, e.g. :app:lint
#
# Required on disk:
#   - GSTREAMER_ROOT_ANDROID / third_party/gstreamer-android  (built by
#     scripts/build-gstreamer-android.sh)
#   - ANDROID_HOME with platforms;android-34, build-tools;34.0.0,
#     ndk;25.2.9519653, platform-tools
#   - JAVA_HOME pointing at a JDK 17 (Temurin recommended on Debian Trixie,
#     which has no openjdk-17-jdk package)
#
# Exit codes:
#   0 success, non-zero on any failure (set -e).

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---------------------------------------------------------------------------
# Resolve toolchain locations (overridable)
# ---------------------------------------------------------------------------

: "${JAVA_HOME:=$HOME/jdk/jdk-17.0.13+11}"
: "${GSTREAMER_ROOT_ANDROID:=$ROOT_DIR/third_party/gstreamer-android}"

# ANDROID_HOME may already be set in the user's shell to something unrelated
# (e.g. an OEM toolchain cache that doesn't contain platforms/android-34). If
# the inherited value is missing any of the components we need, silently fall
# back to the canonical install at ~/Android/Sdk before failing.
android_home_is_complete() {
  local h="$1"
  [[ -d "$h/platforms/android-34" \
     && -d "$h/build-tools/34.0.0" \
     && -d "$h/ndk/25.2.9519653" ]]
}

if [[ -n "${ANDROID_HOME:-}" ]] && ! android_home_is_complete "$ANDROID_HOME"; then
  if android_home_is_complete "$HOME/Android/Sdk"; then
    echo "build-app.sh: ignoring inherited ANDROID_HOME=$ANDROID_HOME (incomplete);" >&2
    echo "              using $HOME/Android/Sdk instead." >&2
    ANDROID_HOME="$HOME/Android/Sdk"
  fi
fi
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
ANDROID_SDK_ROOT="$ANDROID_HOME"

# ---------------------------------------------------------------------------
# Preflight: verify everything the build needs is actually on disk before
# Gradle wastes time downloading dependencies just to fail.
# ---------------------------------------------------------------------------

fail() { echo "build-app.sh: $*" >&2; exit 1; }

[[ -x "$JAVA_HOME/bin/javac" ]] \
  || fail "JAVA_HOME=$JAVA_HOME does not contain bin/javac (need a JDK 17, not a JRE)."

[[ -d "$ANDROID_HOME/platforms/android-34" ]] \
  || fail "ANDROID_HOME=$ANDROID_HOME missing platforms/android-34. Run sdkmanager 'platforms;android-34'."
[[ -d "$ANDROID_HOME/build-tools/34.0.0" ]] \
  || fail "ANDROID_HOME missing build-tools/34.0.0. Run sdkmanager 'build-tools;34.0.0'."
[[ -d "$ANDROID_HOME/ndk/25.2.9519653" ]] \
  || fail "ANDROID_HOME missing ndk/25.2.9519653. Run sdkmanager 'ndk;25.2.9519653'."

[[ -d "$GSTREAMER_ROOT_ANDROID/lib/gstreamer-1.0" ]] \
  || fail "GSTREAMER_ROOT_ANDROID=$GSTREAMER_ROOT_ANDROID looks empty. Run scripts/build-gstreamer-android.sh first."
[[ -f "$GSTREAMER_ROOT_ANDROID/share/gst-android/ndk-build/gstreamer-1.0.mk" ]] \
  || fail "GStreamer SDK at $GSTREAMER_ROOT_ANDROID is missing share/gst-android/ndk-build/gstreamer-1.0.mk."

[[ -x "$ROOT_DIR/gradlew" ]] \
  || fail "Missing $ROOT_DIR/gradlew. Generate it once with 'gradle wrapper --gradle-version 8.9'."

# Make sure local.properties has the SDK path. AGP needs it even when
# ANDROID_HOME is set, because some tasks read it directly.
LOCAL_PROPS="$ROOT_DIR/local.properties"
if [[ ! -f "$LOCAL_PROPS" ]] || ! grep -q '^sdk.dir=' "$LOCAL_PROPS"; then
  echo "sdk.dir=$ANDROID_HOME" > "$LOCAL_PROPS"
  echo "build-app.sh: wrote $LOCAL_PROPS (sdk.dir=$ANDROID_HOME)"
fi

# ---------------------------------------------------------------------------
# Pick the Gradle task
# ---------------------------------------------------------------------------

# First positional arg = task selector. Anything starting with ':' or '-'
# is forwarded verbatim so callers can pass arbitrary tasks/flags.
case "${1:-debug}" in
  debug)       TASKS=( ":app:assembleDebug" ) ;;
  release)     TASKS=( ":app:assembleRelease" ) ;;
  clean)       TASKS=( "clean" ) ;;
  install)     TASKS=( ":app:installDebug" ) ;;   # requires `adb` + a device
  lint)        TASKS=( ":app:lintDebug" ) ;;
  *)           TASKS=( "$@" ) ;;
esac

# Drop the first arg if we consumed it as a friendly alias; pass the rest
# (any extra flags like --info) through to Gradle.
case "${1:-}" in
  debug|release|clean|install|lint) shift || true ;;
esac
GRADLE_EXTRA=( "$@" )

# ---------------------------------------------------------------------------
# Export and run
# ---------------------------------------------------------------------------

export JAVA_HOME
export ANDROID_HOME
export ANDROID_SDK_ROOT
export GSTREAMER_ROOT_ANDROID
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "build-app.sh:"
echo "  JAVA_HOME              = $JAVA_HOME"
echo "  ANDROID_HOME           = $ANDROID_HOME"
echo "  GSTREAMER_ROOT_ANDROID = $GSTREAMER_ROOT_ANDROID"
echo "  Gradle task(s)         = ${TASKS[*]} ${GRADLE_EXTRA[*]:-}"

cd "$ROOT_DIR"
exec ./gradlew --no-daemon "${TASKS[@]}" "${GRADLE_EXTRA[@]}"
