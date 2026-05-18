#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CERBERO_DIR="${CERBERO_DIR:-$ROOT_DIR/third_party/cerbero}"
CERBERO_REF="${CERBERO_REF:-1.24.10}"
CERBERO_CONFIG="${CERBERO_CONFIG:-config/cross-android-arm64.cbc}"
CERBERO_PACKAGE="${CERBERO_PACKAGE:-gstreamer-1.0}"
OUTPUT_DIR="${OUTPUT_DIR:-$ROOT_DIR/third_party/gstreamer-android}"

# CERBERO_HOME is where Cerbero stores sources, build trees, and the local
# prefix. Keep it outside the workspace by default so the workspace stays small.
# The pre-provisioned ~/.cerbero/cerbero.cbc in this container references this
# variable, so it must be exported before invoking cerbero.
export CERBERO_HOME="${CERBERO_HOME:-$HOME/cerbero-home}"
mkdir -p "$CERBERO_HOME"

# Note: Cerbero's cross-android-*.cbc configs do not require an Android SDK to
# build libgstreamer_android.so. The NDK is fetched automatically during
# `cerbero bootstrap` if ANDROID_NDK_HOME / ANDROID_NDK_ROOT are not set.

mkdir -p "$(dirname "$CERBERO_DIR")" "$OUTPUT_DIR"

if [[ ! -d "$CERBERO_DIR/.git" ]]; then
  git clone --branch "$CERBERO_REF" \
    https://gitlab.freedesktop.org/gstreamer/cerbero.git "$CERBERO_DIR"
else
  current_ref="$(git -C "$CERBERO_DIR" rev-parse --abbrev-ref HEAD || true)"
  if [[ "$current_ref" != "$CERBERO_REF" ]]; then
    git -C "$CERBERO_DIR" fetch --tags origin
    git -C "$CERBERO_DIR" checkout "$CERBERO_REF"
  fi
fi

pushd "$CERBERO_DIR" >/dev/null
./cerbero-uninstalled -c "$CERBERO_CONFIG" bootstrap -y --jobs "${CERBERO_JOBS:-2}"
./cerbero-uninstalled -c "$CERBERO_CONFIG" package --jobs "${CERBERO_JOBS:-2}" "$CERBERO_PACKAGE"
popd >/dev/null

# Cerbero writes the package tarballs at the cerbero source directory itself,
# not under a dist/ subdirectory. The full SDK we want is the .tar.xz that
# does NOT have the "-runtime" suffix.
PACKAGE_PATH="$(
  ls -1 "$CERBERO_DIR"/gstreamer-1.0-android-*.tar.xz 2>/dev/null \
    | grep -v -- '-runtime\.tar\.xz$' \
    | sort \
    | tail -n 1
)"

if [[ -z "$PACKAGE_PATH" ]]; then
  echo "Cerbero completed but no Android SDK tarball was found in $CERBERO_DIR." >&2
  echo "Looked for: $CERBERO_DIR/gstreamer-1.0-android-*.tar.xz (excluding -runtime)." >&2
  exit 1
fi

echo "Found GStreamer Android SDK tarball: $PACKAGE_PATH"

mkdir -p "$OUTPUT_DIR"
rm -rf "$OUTPUT_DIR"/*
tar -xf "$PACKAGE_PATH" -C "$OUTPUT_DIR"

# Patch gstreamer-1.0.mk so GSTREAMER_BUILD_DIR is overridable. AGP's
# externalNativeBuild parses the prebuilt LOCAL_SRC_FILES relative to the
# Android.mk directory, but ndk-build resolves relative paths against its CWD
# (= app/). Anchoring GSTREAMER_BUILD_DIR to $(LOCAL_PATH) from our Android.mk
# fixes the mismatch ("Expected output file at gst-android-build/.../
# libgstreamer_android.so for target gstreamer_android but there was none").
GST_MK="$OUTPUT_DIR/share/gst-android/ndk-build/gstreamer-1.0.mk"
if [[ -f "$GST_MK" ]] && ! grep -q 'ifndef GSTREAMER_BUILD_DIR' "$GST_MK"; then
  sed -i 's|^GSTREAMER_BUILD_DIR           := gst-android-build/$(TARGET_ARCH_ABI)$|ifndef GSTREAMER_BUILD_DIR\nGSTREAMER_BUILD_DIR           := gst-android-build/$(TARGET_ARCH_ABI)\nendif|' "$GST_MK"
fi

cat <<EOF

Extracted GStreamer Android SDK to: $OUTPUT_DIR

Note: libgstreamer_android.so is NOT shipped pre-built. It is generated at
app build time by including share/gst-android/ndk-build/gstreamer-1.0.mk from
your Android.mk (see app/src/main/jni/Android.mk).

Export this before running gradlew so the Android.mk can find the SDK:

  export GSTREAMER_ROOT_ANDROID="$OUTPUT_DIR"

EOF