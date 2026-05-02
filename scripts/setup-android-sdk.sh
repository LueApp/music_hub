#!/bin/bash
# Setup Android SDK for command-line development
# Run this once: pixi run setup-sdk

set -e

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
CMDLINE_TOOLS_VERSION="14742923"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"

echo "=== Android SDK Setup ==="
echo "ANDROID_HOME: $ANDROID_HOME"

download_file() {
    local url="$1"
    local output="$2"

    if command -v wget >/dev/null 2>&1; then
        wget -q --show-progress -O "$output" "$url"
    elif command -v curl >/dev/null 2>&1; then
        curl -fL "$url" -o "$output"
    else
        echo "Missing download tool: install wget or curl." >&2
        exit 1
    fi
}

extract_zip() {
    local archive="$1"
    local destination="$2"

    if command -v unzip >/dev/null 2>&1; then
        unzip -q "$archive" -d "$destination"
    elif command -v python3 >/dev/null 2>&1; then
        python3 -m zipfile -e "$archive" "$destination"
    else
        echo "Missing zip extraction tool: install unzip or python3." >&2
        exit 1
    fi
}

# Create SDK directory
mkdir -p "$ANDROID_HOME/cmdline-tools"

# Download command-line tools if not present
if [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
    echo "Downloading Android command-line tools..."
    TEMP_ZIP="/tmp/cmdline-tools.zip"
    download_file "$CMDLINE_TOOLS_URL" "$TEMP_ZIP"

    echo "Extracting..."
    extract_zip "$TEMP_ZIP" "$ANDROID_HOME/cmdline-tools"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm "$TEMP_ZIP"
    echo "Command-line tools installed."
else
    echo "Command-line tools already installed."
fi

# Add to PATH for this session
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# Accept licenses
echo "Accepting licenses..."
yes | sdkmanager --licenses > /dev/null 2>&1 || true

# Install required SDK components
echo "Installing SDK components..."
yes | sdkmanager --install \
    "platform-tools" \
    "platforms;android-35" \
    "build-tools;35.0.0"

echo ""
echo "=== Setup Complete ==="
echo ""
echo "Add the following to your shell profile (~/.bashrc or ~/.zshrc):"
echo ""
echo "  export ANDROID_HOME=\"$ANDROID_HOME\""
echo "  export PATH=\"\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH\""
echo ""
echo "Then run: source ~/.bashrc"
