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
# - build-essential, autotools, pkg-config, cmake, ninja, meson, flex, bison,
#   gettext, gperf, nasm, yasm, intltool, libtool, file, patch, perl, python3,
#   python3-{pip,setuptools,venv}: Cerbero's host-side build prerequisites.
#   Cerbero's `bootstrap` step will apt-install anything else it needs, which
#   is why we install sudo/apt-utils and run the build as root.
# - git, curl, wget, ca-certificates, unzip, xz-utils, bzip2: source fetch +
#   tarball extraction (Cerbero, Android cmdline-tools, GStreamer SDK tarball).
# - openjdk-17-jdk-headless: required by AGP 8.5. (Trixie *does* ship a
#   headless variant even though the full openjdk-17-jdk metapackage was
#   dropped; that satisfies Gradle/AGP fine.)
# - locales: keep UTF-8 stable across Gradle output.
# ---------------------------------------------------------------------------
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl wget git unzip zip xz-utils bzip2 file patch sudo \
        build-essential autoconf automake libtool libtool-bin pkg-config cmake \
        ninja-build meson flex bison gettext gperf nasm yasm intltool \
        python3 python3-pip python3-setuptools python3-venv \
        perl rsync locales \
        openjdk-17-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

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
