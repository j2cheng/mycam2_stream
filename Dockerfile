# Build environment for mycam2_stream.
#
# Stage 1: install host tools needed by:
#   - scripts/build-gstreamer-android.sh  (Cerbero, which fetches its own
#     sources/NDK; we only need the host-side build prerequisites Cerbero
#     wraps over)
#   - scripts/build-app.sh                (JDK 17 + Android SDK/NDK + Gradle
#     wrapper)
#
# We deliberately do NOT pre-install any GStreamer libraries: Cerbero builds
# them from source as part of build-gstreamer-android.sh.

FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# ---------------------------------------------------------------------------
# Host packages
#
# Two groups:
#
# 1. Tools we need directly (curl/git/JDK/etc).
# 2. Everything Cerbero's bootstrap step (`cerbero bootstrap`) tries to
#    apt-install on Debian. Cerbero invokes `apt-get -y install ...` directly
#    without running `apt-get update` first, so we must (a) pre-install those
#    packages and (b) keep /var/lib/apt/lists populated so any redundant
#    install call still succeeds. The list is taken from
#    cerbero/bootstrap/linux.py (Debian distro).
# ---------------------------------------------------------------------------
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl wget git unzip zip xz-utils bzip2 file patch sudo \
        build-essential autoconf automake autotools-dev autopoint \
        libtool libtool-bin pkg-config cmake make g++ \
        ninja-build meson flex bison gettext gperf nasm yasm intltool \
        ccache xutils-dev \
        python3 python3-pip python3-setuptools python3-venv python3-distro python3-dev \
        perl rsync locales \
        libssl-dev libpulse-dev libasound2-dev \
        libx11-dev libx11-xcb-dev libxv-dev libxext-dev libxi-dev \
        libxrender-dev libxfixes-dev libxdamage-dev libxcomposite-dev \
        libxtst-dev libxrandr-dev x11proto-record-dev \
        libgl1-mesa-dev libglu1-mesa-dev libegl1-mesa-dev \
        openjdk-17-jdk-headless \
    && apt-get clean

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH

# ---------------------------------------------------------------------------
# Android SDK + NDK
# Versions match app/build.gradle / scripts/build-app.sh:
#   platforms;android-34, build-tools;34.0.0, ndk;25.2.9519653
# ---------------------------------------------------------------------------
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH

ARG ANDROID_CMDLINE_TOOLS_VERSION=11076708
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && cd /tmp \
    && wget -q "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" \
         -O cmdline-tools.zip \
    && unzip -q cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
    && rm cmdline-tools.zip \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager --install \
         'platform-tools' \
         'platforms;android-34' \
         'build-tools;34.0.0' \
         'ndk;25.2.9519653' \
    && chmod -R a+rX "$ANDROID_HOME"

# ---------------------------------------------------------------------------
# Workspace defaults expected by the build scripts.
# ---------------------------------------------------------------------------
ENV GSTREAMER_ROOT_ANDROID=/workspace/third_party/gstreamer-android \
    CERBERO_HOME=/root/cerbero-home

WORKDIR /workspace

CMD ["bash"]
