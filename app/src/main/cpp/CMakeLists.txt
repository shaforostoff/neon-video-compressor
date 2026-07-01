cmake_minimum_required(VERSION 3.22.1)
# CXX is enabled so the C++ runtime (needed by libx265) is linked in.
project(nativeconverter C CXX)

# Prebuilt FFmpeg + libx265 static libs (built by scripts/build-all.sh).
set(PREBUILT ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI})

if(NOT EXISTS ${PREBUILT}/lib/libavcodec.a)
    message(FATAL_ERROR
        "Prebuilt FFmpeg not found at ${PREBUILT}. "
        "Run scripts/build-all.sh first.")
endif()

include_directories(${PREBUILT}/include)

# Import each static library.
set(FF_LIBS avformat avcodec swscale swresample avutil)
foreach(lib ${FF_LIBS})
    add_library(${lib} STATIC IMPORTED)
    set_target_properties(${lib} PROPERTIES
        IMPORTED_LOCATION ${PREBUILT}/lib/lib${lib}.a)
endforeach()

add_library(x265 STATIC IMPORTED)
set_target_properties(x265 PROPERTIES
    IMPORTED_LOCATION ${PREBUILT}/lib/libx265.a)

add_library(nativeconverter SHARED native_converter.c)

# x265 is C++; link the C++ runtime so its symbols resolve.
set_target_properties(nativeconverter PROPERTIES LINKER_LANGUAGE CXX)

# x265's aarch64 NEON assembly accesses internal globals (e.g.
# x265_entropyStateBits) via ADRP/ADD. Those are only valid against
# non-preemptible symbols, so bind defined symbols locally. Only our JNIEXPORT
# entry points need to be visible to the JVM, which -Bsymbolic does not hide.
target_link_options(nativeconverter PRIVATE "-Wl,-Bsymbolic")

find_library(log-lib log)

# Link order matters for static libs: higher-level FFmpeg libs first, then
# avutil, then x265, then system libs.
target_link_libraries(nativeconverter
    avformat
    avcodec
    swscale
    swresample
    avutil
    x265
    z
    m
    ${log-lib})
