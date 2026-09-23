#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/.runtime-cache"
ARCHIVE="$CACHE/PHP-8.4-Android-arm64-PM5.tar.gz"
URL="https://github.com/pmmp/PHP-Binaries/releases/download/pm5-php-8.4-latest/PHP-8.4-Android-arm64-PM5.tar.gz"
mkdir -p "$CACHE"

echo "Downloading PHP 8.4 ARM64 build-time runtime…"
curl -fL --retry 3 "$URL" -o "$ARCHIVE"
python3 "$ROOT/tools/prepare_php_runtime.py" "$ARCHIVE"
