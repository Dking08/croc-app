#!/usr/bin/env bash
# =============================================================================
# build.sh  —  Reproducible build + sign script for croc-app
# Runs INSIDE the Docker container.
# =============================================================================
set -euo pipefail

# ── Config (edit these) ───────────────────────────────────────────────────────
REPO_URL="${REPO_URL:-https://github.com/Dking08/croc-app}"
REPO_BRANCH="${REPO_BRANCH:-main}"          # or a tag: v3.2.0
APP_SUBDIR="${APP_SUBDIR:-app}"              # Gradle module/subdir (your 'subdir:' in F-Droid)

KEYSTORE_PATH="${KEYSTORE_PATH:-/secrets/release.jks}"
KEYSTORE_PASS="${KEYSTORE_PASS:?Set KEYSTORE_PASS}"
KEY_ALIAS="${KEY_ALIAS:?Set KEY_ALIAS}"
KEY_PASS="${KEY_PASS:-$KEYSTORE_PASS}"      # defaults to keystore password

OUTPUT_DIR="/output"
# ─────────────────────────────────────────────────────────────────────────────

echo "════════════════════════════════════════════"
echo " croc-app reproducible build"
echo " Go     : $(go version)"
echo " Java   : $(java -version 2>&1 | head -1)"
echo " Repo   : $REPO_URL @ $REPO_BRANCH"
echo "════════════════════════════════════════════"

mkdir -p "$OUTPUT_DIR"

# ── 1. Clone source ───────────────────────────────────────────────────────────
echo "[1/6] Cloning repository..."
rm -rf /build/repo
git clone --recurse-submodules --depth 1 \
    --branch "$REPO_BRANCH" \
    "$REPO_URL" /build/repo
cd /build/repo

COMMIT_HASH=$(git rev-parse HEAD)
echo "      Commit: $COMMIT_HASH"

# ── 2. Verify vendored Go deps exist ─────────────────────────────────────────
echo "[2/6] Checking vendored Go dependencies..."
VENDOR_DIR="third_party/croc-src/vendor"    # adjust if your path differs
if [ ! -d "$VENDOR_DIR" ]; then
    echo "ERROR: $VENDOR_DIR not found."
    echo "       You must commit 'go mod vendor' output to your repo."
    echo "       On your dev machine, run:"
    echo "         cd third_party/croc-src && go mod vendor && git add vendor && git commit"
    exit 1
fi
echo "      vendor/ found ✓"

# ── 3. Gradle build (assembleRelease) ────────────────────────────────────────
echo "[3/6] Building APK with Gradle..."
GRADLEW_PATH=$(find /build/repo -maxdepth 4 -type f -name gradlew | sort | head -1)
if [ -n "$GRADLEW_PATH" ]; then
    GRADLE_PROJECT_DIR=$(dirname "$GRADLEW_PATH")
    chmod +x "$GRADLEW_PATH"
    GRADLE_CMD=("$GRADLEW_PATH")
    echo "      Gradle runner: $GRADLEW_PATH"
else
    GRADLE_PROJECT_DIR=/build/repo
    if ! command -v gradle >/dev/null 2>&1; then
        echo "ERROR: Could not find gradlew in the cloned repo, and Gradle is not installed in the build image."
        echo "       Repo layout:"
        find /build/repo -maxdepth 3 \( -name settings.gradle -o -name settings.gradle.kts -o -name build.gradle -o -name build.gradle.kts \) -print
        exit 1
    fi
    GRADLE_CMD=(gradle)
    echo "      Gradle runner: $(gradle --version | sed -n '3p')"
fi

cd "$GRADLE_PROJECT_DIR"
echo "      Gradle project: ${GRADLE_PROJECT_DIR#/build/repo/}"

# Force Gradle to never use Android Studio's daemon (mimics F-Droid CI exactly)
export GRADLE_USER_HOME=/tmp/gradle-home
export ANDROID_HOME=/opt/android-sdk

# These env vars mirror what your Gradle task already sets:
export GOENV=off
export GOWORK=off
export GOTELEMETRY=off
export GOOS=android
export GOARCH=arm64
export GOARM64=v8.0
export CGO_ENABLED=0
ln -sfn "$GOROOT" /build/repo/.fdroid-go

if [ -f "$APP_SUBDIR/build.gradle" ] || [ -f "$APP_SUBDIR/build.gradle.kts" ]; then
    GRADLE_TASK=":$APP_SUBDIR:assembleRelease"
else
    GRADLE_TASK="assembleRelease"
fi
echo "      Gradle task: $GRADLE_TASK"

"${GRADLE_CMD[@]}" \
    --no-daemon \
    --no-build-cache \
    --console=plain \
    "$GRADLE_TASK"

# ── 4. Locate the unsigned APK ───────────────────────────────────────────────
echo "[4/6] Locating unsigned APK..."
UNSIGNED_APK=$(find "$GRADLE_PROJECT_DIR" -path "*/build/outputs/apk/*" -name "*release*unsigned*.apk" | head -1)
if [ -z "$UNSIGNED_APK" ]; then
    # Gradle sometimes names it without 'unsigned' if signingConfig is absent
    UNSIGNED_APK=$(find "$GRADLE_PROJECT_DIR" -path "*/build/outputs/apk/*" -name "*release*.apk" | head -1)
fi
if [ -z "$UNSIGNED_APK" ]; then
    echo "ERROR: Could not find release APK in build outputs."
    find "$GRADLE_PROJECT_DIR" -path "*/build/outputs/*" -name "*.apk"
    exit 1
fi
echo "      Found: $UNSIGNED_APK"

# ── 5. Fix CRLF line endings ─────────────────────────────────────────────────
# This Docker build produces LF natively, so fix-newlines is a no-op here.
# It IS needed on the F-Droid side (via postbuild in metadata) to match your
# existing Windows-signed reference APK. Once you re-sign a fresh Linux-built
# APK as your new reference, this step and the F-Droid postbuild can be dropped.
echo "[5/6] CRLF fix (no-op on Linux build, kept for parity)..."
cp "$UNSIGNED_APK" /tmp/app-prefixed.apk
zip -d /tmp/app-prefixed.apk 'assets/dexopt/*' >/dev/null 2>&1 || true

python3 /opt/reproducible-apk-tools/inplace-fix.py \
    --zipalign \
    fix-newlines \
    /tmp/app-prefixed.apk \
    'META-INF/services/*'

ALIGNED_APK=/tmp/app-release-aligned.apk
cp /tmp/app-prefixed.apk "$ALIGNED_APK"

# ── 6. Sign with apksigner from build-tools 34 (NOT 35+) ─────────────────────
echo "[6/6] Signing APK (build-tools 34 apksigner)..."
SIGNED_APK="$OUTPUT_DIR/croc-app-release.apk"

# Use the explicit path to ensure we get build-tools 34, not any system version
/opt/android-sdk/build-tools/34.0.0/apksigner sign \
    --v1-signing-enabled false \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --v4-signing-enabled false \
    --ks "$KEYSTORE_PATH" \
    --ks-pass "pass:$KEYSTORE_PASS" \
    --ks-key-alias "$KEY_ALIAS" \
    --key-pass "pass:$KEY_PASS" \
    --out "$SIGNED_APK" \
    "$ALIGNED_APK"

# Verify the signature
/opt/android-sdk/build-tools/34.0.0/apksigner verify --verbose "$SIGNED_APK"

echo ""
echo "════════════════════════════════════════════"
echo " ✓ Build complete!"
echo "   Signed APK : $SIGNED_APK"
echo "   Commit     : $COMMIT_HASH"
SHA256=$(sha256sum "$SIGNED_APK" | cut -d' ' -f1)
echo "   SHA-256    : $SHA256"
echo ""
echo " AllowedAPKSigningKeys for your F-Droid metadata:"
/opt/android-sdk/build-tools/34.0.0/apksigner verify --print-certs "$SIGNED_APK" \
    | grep "SHA-256" | sed 's/.*: //' | tr -d ':' | tr '[:upper:]' '[:lower:]'
echo "════════════════════════════════════════════"
