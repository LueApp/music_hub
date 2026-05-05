#!/bin/bash
# Build the signed release APK and install it to a connected device.
# Reads keystore credentials from .release-env (gitignored) at the repo root,
# or from environment variables already exported by the calling shell.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Source local env file if present
if [ -f .release-env ]; then
    set -a
    # shellcheck disable=SC1091
    source .release-env
    set +a
fi

# Validate required env vars
missing=()
for var in ANDROID_KEYSTORE_PATH ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
    if [ -z "${!var:-}" ]; then
        missing+=("$var")
    fi
done

if [ ${#missing[@]} -gt 0 ]; then
    echo "ERROR: missing required environment variables for release signing:" >&2
    for var in "${missing[@]}"; do
        echo "  - $var" >&2
    done
    echo "" >&2
    echo "Either export them in your shell, or create a gitignored .release-env" >&2
    echo "file at the repo root with these contents:" >&2
    echo "" >&2
    echo "  ANDROID_KEYSTORE_PATH=/absolute/path/to/release.jks" >&2
    echo "  ANDROID_KEYSTORE_PASSWORD=..." >&2
    echo "  ANDROID_KEY_ALIAS=..." >&2
    echo "  ANDROID_KEY_PASSWORD=..." >&2
    exit 1
fi

if [ ! -f "$ANDROID_KEYSTORE_PATH" ]; then
    echo "ERROR: keystore file not found at: $ANDROID_KEYSTORE_PATH" >&2
    exit 1
fi

cd android-app
./gradlew installRelease
