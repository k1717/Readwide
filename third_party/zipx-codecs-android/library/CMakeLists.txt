# The resulting JNI shared library is LGPL-2.1-or-later because it contains the
# XADMaster WinZip JPEG decoder. WavPack itself is BSD-3-Clause.
cmake_minimum_required(VERSION 3.22.1)
project(readwide-zipx-codecs LANGUAGES C)

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
add_subdirectory(src/main/jni/external/wavpack wavpack-build EXCLUDE_FROM_ALL)
set_target_properties(wavpack PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(readwide-zipx-codecs SHARED
        src/main/jni/zipx_codecs_jni.c
        src/main/jni/external/xad-winzip-jpeg/WinZipJPEG/ArithmeticDecoder.c
        src/main/jni/external/xad-winzip-jpeg/WinZipJPEG/Decompressor.c
        src/main/jni/external/xad-winzip-jpeg/WinZipJPEG/JPEG.c
        src/main/jni/external/xad-winzip-jpeg/lzma/LzmaDec.c)

target_include_directories(readwide-zipx-codecs PRIVATE
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
target_link_libraries(readwide-zipx-codecs PRIVATE wavpack)
