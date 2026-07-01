#!/usr/bin/env bash
#
# Build libx265 4.2 as a static library for Android arm64-v8a.
# Output is installed into app/src/main/cpp/prebuilt/arm64-v8a.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

: "${ANDROID_NDK_HOME:=$HOME/Android/Sdk/ndk/28.2.13676358}"
: "${API:=29}"
ABI="arm64-v8a"
ARCH="aarch64"
X265_TAG="4.2"

WORK="$HERE/.work"
SRC="$WORK/x265"
BUILD="$WORK/x265-build"
PREFIX="$ROOT/app/src/main/cpp/prebuilt/$ABI"

if [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "ERROR: NDK not found at $ANDROID_NDK_HOME (set ANDROID_NDK_HOME)" >&2
  exit 1
fi

mkdir -p "$WORK" "$PREFIX"

if [ ! -d "$SRC" ]; then
  echo ">> Cloning x265 $X265_TAG ..."
  git clone --depth 1 --branch "$X265_TAG" \
    https://bitbucket.org/multicoreware/x265_git.git "$SRC"
fi

# x265 4.2's generated pkg-config file mangles the unwind reference (NDK's
# CMAKE_CXX_IMPLICIT_LINK_LIBRARIES already contains an "-l:libunwind.a"-style
# entry, and CMakeLists.txt blindly prepends another "-l", yielding invalid
# tokens like "-l-l:libunwind.a") that break FFmpeg's pkg-config link test for
# libx265. Patch the .pc template to emit the libs we actually need.
if ! grep -q "Libs.private: -lc++ -lm -ldl" "$SRC/source/x265.pc.in"; then
  patch -p1 -d "$SRC" < "$HERE/patches/x265-4.2-pkgconfig-libs-private.patch"
fi

TC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"

# NOTE: We deliberately do NOT use android.toolchain.cmake here. x265 assembles
# its NEON .S files with a custom command that invokes ${CMAKE_CXX_COMPILER}
# WITHOUT the CMake flags, so the NDK r23+ default toolchain (which carries
# --target in the *flags*) makes the bare compiler default to the x86 host and
# fails with "unknown target CPU 'armv8-a'". Using the target-prefixed compiler
# wrappers (which bake in --target/--sysroot) makes the bare compiler correct.
echo ">> Configuring x265 (arm64-v8a, API $API) ..."
rm -rf "$BUILD"
mkdir -p "$BUILD"
cmake -G "Unix Makefiles" \
  -S "$SRC/source" -B "$BUILD" \
  -DCMAKE_SYSTEM_NAME=Linux \
  -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
  -DCMAKE_C_COMPILER="$TC/bin/${ARCH:-aarch64}-linux-android${API}-clang" \
  -DCMAKE_CXX_COMPILER="$TC/bin/${ARCH:-aarch64}-linux-android${API}-clang++" \
  -DCMAKE_AR="$TC/bin/llvm-ar" \
  -DCMAKE_RANLIB="$TC/bin/llvm-ranlib" \
  -DCMAKE_FIND_ROOT_PATH="$TC/sysroot" \
  -DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER \
  -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY \
  -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$PREFIX" \
  -DENABLE_SHARED=OFF \
  -DENABLE_CLI=OFF \
  -DENABLE_ASSEMBLY=ON \
  -DENABLE_PIC=ON \
  -DHIGH_BIT_DEPTH=OFF

echo ">> Building x265 ..."
cmake --build "$BUILD" -j "$(nproc)"

echo ">> Installing x265 to $PREFIX ..."
cmake --install "$BUILD"

# The CMake build installs a libx265.a (renamed from libx265-static.a on some
# versions). Make sure the canonical name exists for FFmpeg's pkg-config.
if [ ! -f "$PREFIX/lib/libx265.a" ] && [ -f "$BUILD/libx265.a" ]; then
  cp "$BUILD/libx265.a" "$PREFIX/lib/libx265.a"
fi

echo ">> libx265 done:"
ls -l "$PREFIX/lib/libx265.a" "$PREFIX/lib/pkgconfig/x265.pc" 2>/dev/null || true
