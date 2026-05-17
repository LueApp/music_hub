#!/bin/bash
# Build Tutti APK for Android

set -e

cd "$(dirname "$0")/.."

# Check if buildozer is installed
if ! command -v buildozer &> /dev/null; then
    echo "Installing Buildozer..."
    pip install buildozer
fi

# Run tests first
echo "Running tests..."
pytest tests/ -v

# Build the APK
echo "Building APK..."
buildozer android debug

echo ""
echo "Build complete!"
echo "APK location: bin/"
ls -la bin/*.apk 2>/dev/null || echo "No APK found in bin/"
