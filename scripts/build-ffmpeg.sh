#!/usr/bin/env bash
#
# Build a minimal FFmpeg (with libx265) as static libraries for Android arm64-v8a.
# Requires libx265 to have been built first (scripts/build-x265.sh).
# Output is installed into app/src/main/cpp/prebuilt/arm64-v8a.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

: "${ANDROID_NDK_HOME:=$HOME/Android/Sdk/ndk/28.2.13676358}"
: "${API:=29}"
ABI="arm64-v8a"
ARCH="aarch64"
FFMPEG_TAG="n7.1"

WORK="$HERE/.work"
SRC="$WORK/ffmpeg"
PREFIX="$ROOT/app/src/main/cpp/prebuilt/$ABI"

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
CC="$TOOLCHAIN/bin/${ARCH}-linux-android${API}-clang"
CXX="$TOOLCHAIN/bin/${ARCH}-linux-android${API}-clang++"
SYSROOT="$TOOLCHAIN/sysroot"

if [ ! -f "$PREFIX/lib/libx265.a" ]; then
  echo "ERROR: libx265 not built yet. Run scripts/build-x265.sh first." >&2
  exit 1
fi

mkdir -p "$WORK"

if [ ! -d "$SRC" ]; then
  echo ">> Cloning FFmpeg $FFMPEG_TAG ..."
  git clone --depth 1 --branch "$FFMPEG_TAG" \
    https://github.com/FFmpeg/FFmpeg.git "$SRC"
fi

# FFmpeg's libx265 multilayer/SVC path (gated on X265_BUILD >= 210) passes an
# array of x265_picture pointers, but a standard single-layer x265 build (such
# as ours) exposes encoder_encode with a single x265_picture* out parameter.
# The mismatch makes x265 overwrite the pointer array, yielding a NULL output
# picture and a SIGSEGV. Force the classic single-picture path.
if ! grep -q "X265_BUILD >= 1000000" "$SRC/libavcodec/libx265.c"; then
  patch -p1 -d "$SRC" < "$HERE/patches/ffmpeg-n7.1-libx265-single-picture.patch"
fi

# Restrict pkg-config to the Android prefix so it does NOT pick up the host's
# (x86_64) libx265.pc.
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"

echo ">> Configuring FFmpeg (arm64-v8a, API $API) ..."
cd "$SRC"
make distclean >/dev/null 2>&1 || true

./configure \
  --prefix="$PREFIX" \
  --target-os=android \
  --arch="$ARCH" \
  --enable-cross-compile \
  --cc="$CC" \
  --cxx="$CXX" \
  --sysroot="$SYSROOT" \
  --ar="$TOOLCHAIN/bin/llvm-ar" \
  --nm="$TOOLCHAIN/bin/llvm-nm" \
  --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
  --strip="$TOOLCHAIN/bin/llvm-strip" \
  --pkg-config="pkg-config" \
  --pkg-config-flags="--static" \
  --enable-gpl \
  --enable-libx265 \
  --enable-pic \
  --enable-static \
  --disable-shared \
  --enable-small \
  --disable-debug \
  --disable-programs \
  --disable-doc \
  --disable-avdevice \
  --disable-postproc \
  --disable-avfilter \
  --disable-network \
  --disable-bzlib \
  --disable-lzma \
  --disable-iconv \
  --disable-everything \
  --enable-protocol=file \
  --enable-demuxer=mov,matroska \
  --enable-decoder=h264,hevc \
  --enable-parser=h264,hevc \
  --enable-encoder=libx265 \
  --enable-muxer=mov,mp4 \
  --enable-bsf=hevc_mp4toannexb,h264_mp4toannexb,extract_extradata \
  --extra-cflags="-I$PREFIX/include -O2 -fPIC" \
  --extra-ldflags="-L$PREFIX/lib"

echo ">> Building FFmpeg ..."
make -j "$(nproc)"

echo ">> Installing FFmpeg to $PREFIX ..."
make install

echo ">> FFmpeg done:"
ls -l "$PREFIX"/lib/libav*.a "$PREFIX"/lib/libsw*.a 2>/dev/null || true
