#!/bin/bash
# Run Music Hub on desktop for development

set -e

cd "$(dirname "$0")/.."

# Check if kivy is installed
if ! python3 -c "import kivy" 2>/dev/null; then
    echo "Installing Kivy..."
    pip install kivy
fi

# Run the app
python3 main.py
