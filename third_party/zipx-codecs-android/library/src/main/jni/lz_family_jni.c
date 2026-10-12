/* Readwide streaming JNI adapter, LGPL-2.1-or-later (combined library).
 * The LZ5/Lizard/xxHash decoder sources retain their separate BSD licenses. */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
/* Both frame headers export the same obsolete, unprefixed aliases by default.
 * This adapter uses only the distinct LZ5F_/LizardF_ APIs. */
#ifndef LZ5F_DISABLE_OBSOLETE_ENUMS
#define LZ5F_DISABLE_OBSOLETE_ENUMS 1
#endif
#ifndef LIZARDF_DISABLE_OBSOLETE_ENUMS
#define LIZARDF_DISABLE_OBSOLETE_ENUMS 1
#endif
#include "lz5/lz5frame.h"
#include "lizard/lizard_frame.h"

typedef struct {
    int lizard;
    LZ5F_decompressionContext_t lz5;
    LizardF_decompressionContext_t liz;
} LzContext;

static void fail(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type) { (*env)->ThrowNew(env, type, message); (*env)->DeleteLocalRef(env, type); }
}

JNIEXPORT jlong JNICALL Java_com_readwide_codecs_LzFamilyInputStream_nativeCreate(
        JNIEnv *env, jclass cls, jboolean lizard) {
    (void) cls;
    LzContext *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) { fail(env, "Cannot allocate LZ decoder"); return 0; }
    ctx->lizard = lizard != JNI_FALSE;
    size_t status = ctx->lizard ? LizardF_createDecompressionContext(&ctx->liz, LIZARDF_VERSION)
                               : LZ5F_createDecompressionContext(&ctx->lz5, LZ5F_VERSION);
    if (ctx->lizard ? LizardF_isError(status) : LZ5F_isError(status)) {
        if (ctx->liz) LizardF_freeDecompressionContext(ctx->liz);
        if (ctx->lz5) LZ5F_freeDecompressionContext(ctx->lz5);
        free(ctx); fail(env, "Cannot initialize LZ decoder"); return 0;
    }
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT jlong JNICALL Java_com_readwide_codecs_LzFamilyInputStream_nativeStep(
        JNIEnv *env, jclass cls, jlong handle, jobject input, jint offset, jint count,
        jobject output, jint capacity) {
    (void) cls;
    LzContext *ctx = (LzContext *) (intptr_t) handle;
    if (!ctx || !input || !output) { fail(env, "Invalid LZ decoder arguments"); return 0; }
    uint8_t *src = (*env)->GetDirectBufferAddress(env, input);
    uint8_t *dst = (*env)->GetDirectBufferAddress(env, output);
    jlong src_size = (*env)->GetDirectBufferCapacity(env, input);
    jlong dst_size = (*env)->GetDirectBufferCapacity(env, output);
    if (!src || !dst || offset < 0 || count < 0 || capacity < 1
            || (jlong) offset > src_size || (jlong) count > src_size - offset
            || (jlong) capacity > dst_size || count > 65536 || capacity > 65536) {
        fail(env, "Invalid LZ decoder buffer range"); return 0;
    }
    size_t used = (size_t) count, produced = (size_t) capacity;
    size_t hint = ctx->lizard
            ? LizardF_decompress(ctx->liz, dst, &produced, src + offset, &used, NULL)
            : LZ5F_decompress(ctx->lz5, dst, &produced, src + offset, &used, NULL);
    if (ctx->lizard ? LizardF_isError(hint) : LZ5F_isError(hint)) {
        fail(env, ctx->lizard ? LizardF_getErrorName(hint) : LZ5F_getErrorName(hint)); return 0;
    }
    if (used > (size_t) count || produced > (size_t) capacity) {
        fail(env, "Invalid LZ decoder progress"); return 0;
    }
    uint64_t result = ((uint64_t) used << 32) | (uint64_t) produced;
    if (hint == 0) result |= UINT64_C(0x8000000000000000);
    return (jlong) result;
}

JNIEXPORT void JNICALL Java_com_readwide_codecs_LzFamilyInputStream_nativeFree(
        JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    LzContext *ctx = (LzContext *) (intptr_t) handle;
    if (!ctx) return;
    if (ctx->lizard) LizardF_freeDecompressionContext(ctx->liz);
    else LZ5F_freeDecompressionContext(ctx->lz5);
    free(ctx);
}
