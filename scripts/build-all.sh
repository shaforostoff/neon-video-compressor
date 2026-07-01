#!/usr/bin/env bash
#
# Build libx265 4.2 + minimal FFmpeg for Android arm64-v8a and install the
# static libraries + headers into app/src/main/cpp/prebuilt/arm64-v8a.
#
# Usage:
#   ANDROID_NDK_HOME=/path/to/ndk ./scripts/build-all.sh
#
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"$HERE/build-x265.sh"
"$HERE/build-ffmpeg.sh"

echo
echo "========================================================"
echo " Native build complete."
echo " Prebuilt libs: app/src/main/cpp/prebuilt/arm64-v8a/lib"
echo "========================================================"
