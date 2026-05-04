# syntax=docker/dockerfile:1
# ─────────────────────────────────────────────────────────────────────────────
# Reproducible build environment for croc-app
# Mirrors F-Droid's Debian trixie buildserver as closely as possible.
# ─────────────────────────────────────────────────────────────────────────────
FROM debian:trixie-slim

# ── 1. System packages ───────────────────────────────────────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends \
    # Go build prerequisites
    golang-go \
    gcc \
    libc6-dev \
    git \
    curl \
    ca-certificates \
    zip \
    unzip \
    python3 \
    # Java — trixie ships 21, not 17
    openjdk-21-jdk-headless \
    # APK signing tools
    zipalign \
    apksigner \
    # Reproducibility helpers
    dos2unix \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# ── 2. Android SDK (command-line tools only) ─────────────────────────────────
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/build-tools/34.0.0:$ANDROID_HOME/platform-tools

ARG CMDLINE_TOOLS_URL=https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip

RUN mkdir -p $ANDROID_HOME/cmdline-tools && \
    curl -o /tmp/cmdtools.zip "$CMDLINE_TOOLS_URL" && \
    unzip -q /tmp/cmdtools.zip -d /tmp/cmdtools && \
    mv /tmp/cmdtools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest && \
    rm -rf /tmp/cmdtools /tmp/cmdtools.zip

# Accept licenses & install required SDK components
# Build-tools MUST be 34 — 35+ breaks apksigcopier (F-Droid uses apksigcopier)
RUN yes | sdkmanager --licenses > /dev/null && \
    sdkmanager \
        "platforms;android-35" \
        "build-tools;34.0.0" \
        "platform-tools"

# ── 3. Exact Go version (must match F-Droid's srclibs pin) ───────────────────
# Build Go from source so this container matches F-Droid's go@go1.25.3 srclib.
ENV GO_VERSION=1.25.3

RUN curl -fsSL "https://go.dev/dl/go${GO_VERSION}.src.tar.gz" \
        -o /tmp/go-src.tar.gz && \
    tar -C /usr/local -xzf /tmp/go-src.tar.gz && \
    mv /usr/local/go /usr/local/go-${GO_VERSION} && \
    rm /tmp/go-src.tar.gz && \
    cd /usr/local/go-${GO_VERSION}/src && \
    unset GOROOT && \
    ./make.bash

ENV GOROOT=/usr/local/go-${GO_VERSION}
ENV GOPATH=/root/go
ENV PATH=$GOROOT/bin:$GOPATH/bin:$PATH
ENV GOTELEMETRY=off
ENV GOENV=off

# ── 4. Gradle (repo does not currently ship gradlew) ─────────────────────────
# Android Gradle Plugin 8.7.x requires Gradle 8.9+.
ENV GRADLE_VERSION=8.9
ENV GRADLE_HOME=/opt/gradle-${GRADLE_VERSION}
ENV PATH=$GRADLE_HOME/bin:$PATH

RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
        -o /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt && \
    rm /tmp/gradle.zip

# ── 5. reproducible-apk-tools (same as F-Droid uses via srclibs) ─────────────
RUN git clone --depth 1 --branch v0.2.3 \
        https://github.com/obfusk/reproducible-apk-tools.git \
        /opt/reproducible-apk-tools

# ── 6. Working directory ──────────────────────────────────────────────────────
WORKDIR /build
COPY build.sh /build/build.sh
RUN sed -i 's/\r$//' /build/build.sh && chmod +x /build/build.sh
