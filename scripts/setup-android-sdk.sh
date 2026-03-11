#!/bin/bash
# Setup Android SDK for command-line development
# Run this once: pixi run setup-sdk

set -e

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
CMDLINE_TOOLS_VERSION="11076708"  # Latest as of 2024
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"

echo "=== Android SDK Setup ==="
echo "ANDROID_HOME: $ANDROID_HOME"

# Create SDK directory
mkdir -p "$ANDROID_HOME/cmdline-tools"

# Download command-line tools if not present
if [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
    echo "Downloading Android command-line tools..."
    TEMP_ZIP="/tmp/cmdline-tools.zip"
    wget -q --show-progress -O "$TEMP_ZIP" "$CMDLINE_TOOLS_URL"

    echo "Extracting..."
    unzip -q "$TEMP_ZIP" -d "$ANDROID_HOME/cmdline-tools"
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
sdkmanager --install \
    "platform-tools" \
    "platforms;android-34" \
    "build-tools;34.0.0"

echo ""
echo "=== Setup Complete ==="
echo ""
echo "Add the following to your shell profile (~/.bashrc or ~/.zshrc):"
echo ""
echo "  export ANDROID_HOME=\"$ANDROID_HOME\""
echo "  export PATH=\"\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH\""
echo ""
echo "Then run: source ~/.bashrc"
