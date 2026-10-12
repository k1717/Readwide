/* Readwide adapter. SPDX-License-Identifier: Apache-2.0 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <new>
#include <exception>
#include "lzham_decomp.h"

namespace {
const size_t kBufferSize = 65536;
struct Context {
    lzham_decompress_state_ptr state;
    bool failed, ended;
    uint8_t input[kBufferSize], output[kBufferSize];
};

void fail(JNIEnv *env, const char *message, bool memory = false) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass(memory ? "java/lang/OutOfMemoryError" : "java/io/IOException");
    if (type) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_readwide_codecs_LzhamInputStream_nativeCreate(
        JNIEnv *env, jclass, jint dictionaryBits) {
    if (dictionaryBits < 15 || dictionaryBits > 29) {
        fail(env, "Invalid LZHAM dictionary exponent"); return 0;
    }
    Context *ctx = static_cast<Context *>(calloc(1, sizeof(Context)));
    if (!ctx) { fail(env, "Cannot allocate LZHAM context", true); return 0; }
    try {
        lzham_decompress_params params = {};
        params.m_struct_size = sizeof(params);
        params.m_dict_size_log2 = static_cast<lzham_uint32>(dictionaryBits);
        params.m_decompress_flags = LZHAM_DECOMP_FLAG_COMPUTE_ADLER32;
        ctx->state = lzham::lzham_lib_decompress_init(&params);
        if (!ctx->state) {
            free(ctx); fail(env, "Cannot allocate LZHAM decoder memory", true); return 0;
        }
        return static_cast<jlong>(reinterpret_cast<intptr_t>(ctx));
    } catch (const std::bad_alloc &) {
        free(ctx); fail(env, "Cannot allocate LZHAM decoder memory", true); return 0;
    } catch (...) {
        free(ctx); fail(env, "LZHAM initialization failed"); return 0;
    }
}

extern "C" JNIEXPORT jlong JNICALL Java_com_readwide_codecs_LzhamInputStream_nativeStep(
        JNIEnv *env, jclass, jlong handle, jbyteArray input, jint offset, jint count,
        jbyteArray output, jint capacity) {
    Context *ctx = reinterpret_cast<Context *>(static_cast<intptr_t>(handle));
    if (!ctx || ctx->failed || ctx->ended || !input || !output) {
        fail(env, "Invalid LZHAM decoder state"); return 0;
    }
    const jsize inputSize = env->GetArrayLength(input), outputSize = env->GetArrayLength(output);
    if (offset < 0 || count < 0 || offset > inputSize || count > inputSize - offset
            || count > static_cast<jint>(kBufferSize) || capacity < 1
            || capacity > outputSize || capacity > static_cast<jint>(kBufferSize)) {
        ctx->failed = true; fail(env, "Invalid LZHAM buffer range"); return 0;
    }
    env->GetByteArrayRegion(input, offset, count, reinterpret_cast<jbyte *>(ctx->input));
    if (env->ExceptionCheck()) { ctx->failed = true; return 0; }
    try {
        size_t used = static_cast<size_t>(count), produced = static_cast<size_t>(capacity);
        // Never enable synthetic zero bits on input exhaustion. EOF is accepted
        // only after an explicit codec end and its complete Adler32 trailer.
        lzham_decompress_status_t status = lzham::lzham_lib_decompress(
                ctx->state, ctx->input, &used, ctx->output, &produced, false);
        if (status >= LZHAM_DECOMP_STATUS_FIRST_FAILURE_CODE) {
            ctx->failed = true; fail(env, "Invalid or incomplete LZHAM payload/checksum"); return 0;
        }
        if (used > static_cast<size_t>(count) || produced > static_cast<size_t>(capacity)) {
            ctx->failed = true; fail(env, "Invalid LZHAM decoder progress"); return 0;
        }
        ctx->ended = status == LZHAM_DECOMP_STATUS_SUCCESS;
        env->SetByteArrayRegion(output, 0, static_cast<jsize>(produced), reinterpret_cast<jbyte *>(ctx->output));
        if (env->ExceptionCheck()) { ctx->failed = true; return 0; }
        uint64_t result = (static_cast<uint64_t>(used) << 32) | produced;
        if (ctx->ended) result |= UINT64_C(0x8000000000000000);
        return static_cast<jlong>(result);
    } catch (const std::bad_alloc &) {
        ctx->failed = true; fail(env, "LZHAM allocation failed", true); return 0;
    } catch (...) {
        ctx->failed = true; fail(env, "LZHAM decoder failed"); return 0;
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_readwide_codecs_LzhamInputStream_nativeFree(
        JNIEnv *env, jclass, jlong handle) {
    Context *ctx = reinterpret_cast<Context *>(static_cast<intptr_t>(handle));
    if (!ctx) return;
    try { lzham::lzham_lib_decompress_deinit(ctx->state); }
    catch (...) { fail(env, "LZHAM decoder cleanup failed"); }
    free(ctx);
}
