LOCAL_PATH := $(call my-dir)

# -----------------------------------------------------------------------------
# Our own JNI library: appnative
# -----------------------------------------------------------------------------
include $(CLEAR_VARS)

LOCAL_MODULE     := appnative
LOCAL_SRC_FILES  := rtsp_server.c
LOCAL_SHARED_LIBRARIES := gstreamer_android
LOCAL_LDLIBS     := -llog -landroid

include $(BUILD_SHARED_LIBRARY)

# -----------------------------------------------------------------------------
# GStreamer Android integration: produces libgstreamer_android.so by linking
# the static plugins listed below + the gstreamer-1.0 core into a single .so.
# -----------------------------------------------------------------------------
ifndef GSTREAMER_ROOT_ANDROID
$(error GSTREAMER_ROOT_ANDROID is not defined. Point it at the extracted GStreamer Android SDK (e.g. third_party/gstreamer-android).)
endif

GSTREAMER_ROOT        := $(GSTREAMER_ROOT_ANDROID)
GSTREAMER_NDK_BUILD_PATH := $(GSTREAMER_ROOT)/share/gst-android/ndk-build/

# Plugins required for: Camera2 frames -> appsrc (encoded H.265 NAL units) ->
# h265parse -> rtph265pay -> rtpbin (via gst-rtsp-server) -> UDP transport.
# Java side does Camera2 + MediaCodec; native side runs the RTSP server.
GSTREAMER_PLUGINS := \
    coreelements \
    app \
    rtp \
    rtpmanager \
    udp \
    rtsp \
    videoparsersbad \
    typefindfunctions

# We link gst-rtsp-server (a library, not a plugin) from the SDK, and use
# the gstreamer-app API directly to push H.265 access units into appsrc.
GSTREAMER_EXTRA_DEPS := gstreamer-rtsp-server-1.0 gstreamer-rtsp-1.0 gstreamer-sdp-1.0 gstreamer-app-1.0

# Skip bundling fonts and CA certificates by default; the streaming pipeline
# does not need them. Re-enable in GStreamer.init() if you add features that do.
GSTREAMER_INCLUDE_FONTS           := no
GSTREAMER_INCLUDE_CA_CERTIFICATES := no

# Source layout for the bundled GStreamer.java helper.
GSTREAMER_JAVA_SRC_DIR := ../java

# Pin the generated libgstreamer_android.so output to a path relative to *this*
# Android.mk, not relative to ndk-build's CWD. AGP's externalNativeBuild parses
# the prebuilt module's LOCAL_SRC_FILES relative to the Android.mk directory,
# but ndk-build resolves relative paths against its own working directory
# (= app/ under AGP). Keeping the path anchored to $(LOCAL_PATH) makes both
# agree. Requires the matching `ifndef GSTREAMER_BUILD_DIR` guard in the SDK's
# gstreamer-1.0.mk (applied automatically by scripts/build-gstreamer-android.sh).
GSTREAMER_BUILD_DIR := $(LOCAL_PATH)/gst-android-build/$(TARGET_ARCH_ABI)

include $(GSTREAMER_NDK_BUILD_PATH)/gstreamer-1.0.mk
