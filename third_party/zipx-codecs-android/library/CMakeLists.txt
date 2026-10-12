# readwide-zipx-codecs is LGPL-2.1-or-later because it contains the XADMaster
# WinZip JPEG decoder. WavPack is BSD-3-Clause. Separate CAB/PPMd/LZHAM targets below
# retain their own licensing boundaries.
cmake_minimum_required(VERSION 3.22.1)
project(readwide-zipx-codecs LANGUAGES C CXX)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(WAVPACK_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(WAVPACK_ENABLE_ASM OFF CACHE BOOL "" FORCE)
set(WAVPACK_ENABLE_DSD OFF CACHE BOOL "" FORCE)
set(WAVPACK_ENABLE_LEGACY OFF CACHE BOOL "" FORCE)
set(WAVPACK_ENABLE_THREADS OFF CACHE BOOL "" FORCE)
set(WAVPACK_INSTALL_CMAKE_MODULE OFF CACHE BOOL "" FORCE)
set(WAVPACK_INSTALL_DOCS OFF CACHE BOOL "" FORCE)
set(WAVPACK_INSTALL_PKGCONFIG_MODULE OFF CACHE BOOL "" FORCE)
# Short binary path also leaves room for WavPack's nested architecture probe on Windows.
add_subdirectory(src/main/jni/external/wavpack wv EXCLUDE_FROM_ALL)
set_target_properties(wavpack PROPERTIES POSITION_INDEPENDENT_CODE ON)

# Decoder-only LZ5/Lizard frame APIs. Reuse the already vendored checksum and
# portable Zstd helper headers; do not fetch dependencies during a build.
set(LZ_NATIVE_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/external)
set(ARCHIVE_NATIVE_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../libarchive-android/library/src/main/jni/external)
add_library(readwide-lz-frames STATIC
        ${LZ_NATIVE_ROOT}/lz5/lz5.c
        ${LZ_NATIVE_ROOT}/lz5/lz5frame.c
        ${LZ_NATIVE_ROOT}/lizard/lizard_decompress.c
        ${LZ_NATIVE_ROOT}/lizard/lizard_frame.c
        ${LZ_NATIVE_ROOT}/lizard/liz_entropy_common.c
        ${LZ_NATIVE_ROOT}/lizard/liz_fse_decompress.c
        ${LZ_NATIVE_ROOT}/lizard/liz_huf_decompress.c
        ${ARCHIVE_NATIVE_ROOT}/lz4/lib/xxhash.c)
set_target_properties(readwide-lz-frames PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(readwide-lz-frames PRIVATE
        ${LZ_NATIVE_ROOT}
        ${ARCHIVE_NATIVE_ROOT}/lz4/lib
        ${ARCHIVE_NATIVE_ROOT}/zstd/lib/common)
target_compile_features(readwide-lz-frames PRIVATE c_std_99)
target_compile_definitions(readwide-lz-frames PRIVATE
        READWIDE_DECODE_ONLY=1 XXH_NAMESPACE=RW_LZF_ DEBUGLEVEL=0 HUF_DISABLE_ASM=1)
target_compile_options(readwide-lz-frames PRIVATE
        -Wall -Wextra -Wno-unused-function -Wno-unused-parameter
        -fvisibility=hidden -ffunction-sections -fdata-sections)

add_library(readwide-zipx-codecs SHARED
        src/main/jni/zipx_codecs_jni.c
        src/main/jni/lz_family_jni.c
        src/main/jni/external/xad-winzip-jpeg/WinZipJPEG/ArithmeticDecoder.c
        src/main/jni/external/xad-winzip-jpeg/WinZipJPEG/Decompressor.c
        src/main/jni/external/xad-winzip-jpeg/WinZipJPEG/JPEG.c
        src/main/jni/external/xad-winzip-jpeg/lzma/LzmaDec.c)

target_include_directories(readwide-zipx-codecs PRIVATE
        src/main/jni/external
        src/main/jni/external/wavpack/include
        src/main/jni/external/xad-winzip-jpeg
        src/main/jni/external/xad-winzip-jpeg/WinZipJPEG
        src/main/jni/external/xad-winzip-jpeg/lzma)
target_compile_features(readwide-zipx-codecs PRIVATE c_std_99)
target_compile_options(readwide-zipx-codecs PRIVATE
        -Wall -Wextra -Werror
        -Wno-unused-parameter
        # XADMaster 1.10.8 uses signed loop indices with unsigned JPEG fields.
        -Wno-sign-compare)
target_link_options(readwide-zipx-codecs PRIVATE -Wl,--gc-sections)
target_link_libraries(readwide-zipx-codecs PRIVATE wavpack readwide-lz-frames)

# Independent LGPL-2.1-only CAB shared-library boundary. Only the public mspack.h
# interface is used by the adapter. Default filesystem I/O is not compiled in.
set(MSPACK_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../libmspack/mspack)
add_library(readwide-mspack-cab STATIC
        ${MSPACK_ROOT}/system.c
        ${MSPACK_ROOT}/cabd.c
        ${MSPACK_ROOT}/mszipd.c
        ${MSPACK_ROOT}/lzxd.c
        ${MSPACK_ROOT}/qtmd.c)
set_target_properties(readwide-mspack-cab PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(readwide-mspack-cab PRIVATE ${MSPACK_ROOT})
target_compile_features(readwide-mspack-cab PRIVATE c_std_99)
target_compile_definitions(readwide-mspack-cab PRIVATE
        MSPACK_NO_DEFAULT_SYSTEM=1 READWIDE_CAB_STRICT_SET=1 _FILE_OFFSET_BITS=64 SIZEOF_OFF_T=8
        HAVE_INTTYPES_H=1 HAVE_LIMITS_H=1)
target_compile_options(readwide-mspack-cab PRIVATE
        -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden
        -ffunction-sections -fdata-sections)

add_library(readwide-cab SHARED src/main/jni/cab_decoder_jni.c)
target_include_directories(readwide-cab PRIVATE ${MSPACK_ROOT})
target_compile_features(readwide-cab PRIVATE c_std_99)
target_compile_definitions(readwide-cab PRIVATE _FILE_OFFSET_BITS=64)
target_compile_options(readwide-cab PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_options(readwide-cab PRIVATE -Wl,--gc-sections)
target_link_libraries(readwide-cab PRIVATE readwide-mspack-cab)

# Public-domain PPMd-I only, independent of libarchive and the LGPL JPEG target.
# No encoder or other 7-Zip archive/UnRAR implementation is included.
set(PPMD_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../ppmd8)
add_library(readwide-ppmd-core STATIC ${PPMD_ROOT}/Ppmd8.c ${PPMD_ROOT}/Ppmd8Dec.c)
set_target_properties(readwide-ppmd-core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(readwide-ppmd-core PRIVATE ${PPMD_ROOT})
# PUBLIC keeps CPpmd8's layout identical in the core and its JNI consumer.
target_compile_definitions(readwide-ppmd-core PUBLIC PPMD8_FREEZE_SUPPORT)
target_compile_features(readwide-ppmd-core PRIVATE c_std_99)
target_compile_options(readwide-ppmd-core PRIVATE -Wall -Wextra -fvisibility=hidden
        -ffunction-sections -fdata-sections)
add_library(readwide-ppmd SHARED src/main/jni/ppmd_zip_jni.c)
target_include_directories(readwide-ppmd PRIVATE ${PPMD_ROOT})
target_compile_features(readwide-ppmd PRIVATE c_std_99)
target_compile_options(readwide-ppmd PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_options(readwide-ppmd PRIVATE -Wl,--gc-sections)
target_link_libraries(readwide-ppmd PRIVATE readwide-ppmd-core)

# MIT LZHAM decoder, with its own JNI library. No compressor/plugin/CLI sources.
set(LZHAM_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../lzham)
add_library(readwide-lzham-core STATIC
        ${LZHAM_ROOT}/lzhamdecomp/lzham_assert.cpp
        ${LZHAM_ROOT}/lzhamdecomp/lzham_checksum.cpp
        ${LZHAM_ROOT}/lzhamdecomp/lzham_huffman_codes.cpp
        ${LZHAM_ROOT}/lzhamdecomp/lzham_lzdecomp.cpp
        ${LZHAM_ROOT}/lzhamdecomp/lzham_lzdecompbase.cpp
        ${LZHAM_ROOT}/lzhamdecomp/lzham_mem.cpp
        ${LZHAM_ROOT}/lzhamdecomp/lzham_platform.cpp
        ${LZHAM_ROOT}/lzhamdecomp/lzham_prefix_coding.cpp
        ${LZHAM_ROOT}/lzhamdecomp/lzham_symbol_codec.cpp
        ${LZHAM_ROOT}/lzhamdecomp/lzham_timer.cpp
        ${LZHAM_ROOT}/lzhamdecomp/lzham_vector.cpp)
set_target_properties(readwide-lzham-core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(readwide-lzham-core PRIVATE ${LZHAM_ROOT}/include ${LZHAM_ROOT}/lzhamdecomp)
target_compile_features(readwide-lzham-core PRIVATE cxx_std_11)
target_compile_definitions(readwide-lzham-core PRIVATE
        LZHAM_ANSI_CPLUSPLUS=1 READWIDE_LZHAM_DECODE_ONLY=1
        READWIDE_LZHAM_NO_EXIT=1 READWIDE_LZHAM_STRICT_END=1)
target_compile_options(readwide-lzham-core PRIVATE -Wall -Wextra -fexceptions -fno-strict-aliasing
        -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
add_library(readwide-lzham SHARED src/main/jni/lzham_jni.cpp)
target_include_directories(readwide-lzham PRIVATE ${LZHAM_ROOT}/include ${LZHAM_ROOT}/lzhamdecomp)
target_compile_features(readwide-lzham PRIVATE cxx_std_11)
target_compile_options(readwide-lzham PRIVATE -Wall -Wextra -Werror -fexceptions -fvisibility=hidden)
target_link_options(readwide-lzham PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
target_link_libraries(readwide-lzham PRIVATE readwide-lzham-core m)
