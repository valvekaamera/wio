#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
export PATH="${HOME}/.local/bin:${PATH}"
echo "Building firmware..."
pio run
echo
echo "Uploading to Wio Terminal..."
echo "If upload fails, enter bootloader mode:"
echo "  1. Quickly slide the power switch DOWN twice"
echo "  2. Wait for the blue LED to pulse"
echo "  3. Run this script again"
echo
pio run -t upload
echo
echo "Opening serial monitor at 115200 baud (Ctrl+C to exit)..."
pio device monitor -b 115200
