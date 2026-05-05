#!/bin/bash
# Build the signed Android release APK and static landing page for Cloudflare Pages.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Cloudflare Workers Builds clones with --depth 1; deepen so git describe and
# rev-list see the full history needed for versionName and versionCode.
git fetch --unshallow --tags 2>/dev/null \
    || git fetch --tags 2>/dev/null \
    || true

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

JDK_HOME="${JDK_HOME:-$HOME/.local/share/temurin-17}"
JDK_URL="${JDK_URL:-https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk}"

download_file() {
    local url="$1"
    local output="$2"

    if command -v curl >/dev/null 2>&1; then
        curl -fL "$url" -o "$output"
    elif command -v wget >/dev/null 2>&1; then
        wget -q --show-progress -O "$output" "$url"
    else
        echo "Missing download tool: install curl or wget." >&2
        exit 1
    fi
}

java_major_version() {
    if ! command -v java >/dev/null 2>&1; then
        echo 0
        return
    fi

    java -version 2>&1 \
        | awk -F '"' '/version/ { split($2, parts, "."); print parts[1]; exit }'
}

ensure_jdk() {
    local major
    major="$(java_major_version)"

    if [ "${major:-0}" -ge 17 ]; then
        echo "Using existing Java:"
        java -version
        return
    fi

    if [ ! -x "$JDK_HOME/bin/java" ]; then
        echo "Installing JDK 17 to $JDK_HOME"
        mkdir -p "$JDK_HOME"
        local archive="/tmp/temurin-17.tar.gz"
        download_file "$JDK_URL" "$archive"
        tar -xzf "$archive" -C "$JDK_HOME" --strip-components=1
        rm -f "$archive"
    fi

    export JAVA_HOME="$JDK_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"

    echo "Using downloaded Java:"
    java -version
}

ensure_jdk

if [ -z "${ANDROID_KEYSTORE_BASE64:-}" ]; then
    echo "ERROR: ANDROID_KEYSTORE_BASE64 is not set." >&2
    echo "       Add it as a build secret in Cloudflare Workers" \
         "Settings > Build > Variables and Secrets." >&2
    exit 1
fi

echo "Decoding release keystore..."
KEYSTORE_PATH="$ROOT_DIR/release.jks"
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$KEYSTORE_PATH"
export ANDROID_KEYSTORE_PATH="$KEYSTORE_PATH"
trap 'rm -f "$KEYSTORE_PATH"' EXIT

echo "Installing site dependencies..."
npm --prefix site ci

echo "Setting up Android SDK..."
bash scripts/setup-android-sdk.sh

echo "Building Android release APK..."
(cd android-app && ./gradlew assembleRelease)

echo "Building landing page with bundled APK..."
npm --prefix site run build:with-apk
