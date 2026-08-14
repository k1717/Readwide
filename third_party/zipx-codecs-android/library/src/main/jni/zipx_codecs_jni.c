/*
 * Readwide JNI bridge for ZIPX methods 96 and 97.
 *
 * This file and the combined shared object are LGPL-2.1-or-later so users may
 * replace/relink the XADMaster-derived WinZip JPEG implementation. WavPack is
 * linked from the unmodified BSD-3-Clause 5.9.0 source distribution.
 */
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "wavpack.h"
#include "WinZipJPEG/Decompressor.h"

#define METHOD_JPEG 96
#define METHOD_WAVPACK 97
#define IO_BUFFER_SIZE 65536
#define WAVPACK_SAMPLES 4096
#define JPEG_LOOP_LIMIT 1000000U

typedef struct {
    JNIEnv *env;
    jobject input;
    jobject output;
    jmethodID input_read;
    jmethodID output_write;
    jbyteArray input_buffer;
    jbyteArray output_buffer;
    uint8_t *native_output;
    size_t native_output_size;
    int failed;
    int pushback;
    uint64_t input_pos;
    uint64_t output_count;
    uint64_t output_limit;
    uint8_t first[2];
    unsigned first_count;
    uint8_t last[2];
    unsigned last_count;
} StreamBridge;

static void throw_io(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls) (*env)->ThrowNew(env, cls, message);
}

static int bridge_init(StreamBridge *bridge, JNIEnv *env, jobject input,
                       jobject output, jlong output_limit) {
    memset(bridge, 0, sizeof(*bridge));
    bridge->env = env;
    bridge->input = input;
    bridge->output = output;
    bridge->pushback = -1;
    bridge->output_limit = (uint64_t) output_limit;

    jclass input_class = (*env)->GetObjectClass(env, input);
    jclass output_class = (*env)->GetObjectClass(env, output);
    if (!input_class || !output_class) return 0;
    bridge->input_read = (*env)->GetMethodID(env, input_class, "read", "([BII)I");
    bridge->output_write = (*env)->GetMethodID(env, output_class, "write", "([BII)V");
    (*env)->DeleteLocalRef(env, input_class);
    (*env)->DeleteLocalRef(env, output_class);
    if (!bridge->input_read || !bridge->output_write) return 0;

    bridge->input_buffer = (*env)->NewByteArray(env, IO_BUFFER_SIZE);
    bridge->output_buffer = (*env)->NewByteArray(env, IO_BUFFER_SIZE);
    bridge->native_output = malloc(IO_BUFFER_SIZE);
    bridge->native_output_size = IO_BUFFER_SIZE;
    return bridge->input_buffer && bridge->output_buffer && bridge->native_output;
}

static void bridge_destroy(StreamBridge *bridge) {
    if (bridge->input_buffer) (*bridge->env)->DeleteLocalRef(bridge->env, bridge->input_buffer);
    if (bridge->output_buffer) (*bridge->env)->DeleteLocalRef(bridge->env, bridge->output_buffer);
    free(bridge->native_output);
}

static size_t bridge_read(StreamBridge *bridge, void *data, size_t requested) {
    if (bridge->failed || requested == 0) return 0;
    uint8_t *out = data;
    size_t total = 0;
    if (bridge->pushback >= 0) {
        out[total++] = (uint8_t) bridge->pushback;
        bridge->pushback = -1;
        bridge->input_pos++;
        if (total == requested) return total;
    }
    while (total < requested) {
        jint chunk = (jint) (requested - total > IO_BUFFER_SIZE
                ? IO_BUFFER_SIZE : requested - total);
        jint count = (*bridge->env)->CallIntMethod(bridge->env, bridge->input,
                bridge->input_read, bridge->input_buffer, 0, chunk);
        if ((*bridge->env)->ExceptionCheck(bridge->env)) {
            bridge->failed = 1;
            return total;
        }
        if (count < 0) break;
        if (count == 0) {
            bridge->failed = 1;
            throw_io(bridge->env, "ZIPX input stream returned zero bytes");
            return total;
        }
        (*bridge->env)->GetByteArrayRegion(bridge->env, bridge->input_buffer,
                0, count, (jbyte *) (out + total));
        if ((*bridge->env)->ExceptionCheck(bridge->env)) {
            bridge->failed = 1;
            return total;
        }
        total += (size_t) count;
        bridge->input_pos += (uint64_t) count;
        /* Short reads are legal; return promptly to the native decoder. */
        if (count < chunk) break;
    }
    return total;
}

static int bridge_write(StreamBridge *bridge, const void *data, size_t length) {
    if (bridge->failed) return 0;
    if ((uint64_t) length > bridge->output_limit - bridge->output_count) {
        bridge->failed = 1;
        throw_io(bridge->env, "ZIPX decoded output exceeds the extraction limit");
        return 0;
    }
    const uint8_t *bytes = data;
    for (size_t i = 0; i < length; i++) {
        if (bridge->first_count < 2) bridge->first[bridge->first_count++] = bytes[i];
        if (bridge->last_count < 2) {
            bridge->last[bridge->last_count++] = bytes[i];
        } else {
            bridge->last[0] = bridge->last[1];
            bridge->last[1] = bytes[i];
        }
    }
    size_t offset = 0;
    while (offset < length) {
        jint chunk = (jint) (length - offset > IO_BUFFER_SIZE
                ? IO_BUFFER_SIZE : length - offset);
        (*bridge->env)->SetByteArrayRegion(bridge->env, bridge->output_buffer,
                0, chunk, (const jbyte *) (bytes + offset));
        if ((*bridge->env)->ExceptionCheck(bridge->env)) {
            bridge->failed = 1;
            return 0;
        }
        (*bridge->env)->CallVoidMethod(bridge->env, bridge->output,
                bridge->output_write, bridge->output_buffer, 0, chunk);
        if ((*bridge->env)->ExceptionCheck(bridge->env)) {
            bridge->failed = 1;
            return 0;
        }
        offset += (size_t) chunk;
    }
    bridge->output_count += (uint64_t) length;
    return 1;
}

static int32_t wavpack_read(void *id, void *data, int32_t count) {
    if (count <= 0) return 0;
    return (int32_t) bridge_read((StreamBridge *) id, data, (size_t) count);
}

static uint32_t wavpack_get_pos(void *id) {
    StreamBridge *bridge = id;
    return bridge->input_pos > UINT32_MAX ? UINT32_MAX : (uint32_t) bridge->input_pos;
}

static int wavpack_no_seek_abs(void *id, uint32_t pos) { (void) id; (void) pos; return -1; }
static int wavpack_no_seek_rel(void *id, int32_t delta, int mode) {
    (void) id; (void) delta; (void) mode; return -1;
}

static int wavpack_push_back(void *id, int value) {
    StreamBridge *bridge = id;
    if (bridge->pushback >= 0 || bridge->input_pos == 0) return EOF;
    bridge->pushback = value & 0xff;
    bridge->input_pos--;
    return value & 0xff;
}

static uint32_t wavpack_get_length(void *id) { (void) id; return 0; }
static int wavpack_can_seek(void *id) { (void) id; return 0; }

static WavpackStreamReader wavpack_reader = {
        .read_bytes = wavpack_read,
        .get_pos = wavpack_get_pos,
        .set_pos_abs = wavpack_no_seek_abs,
        .set_pos_rel = wavpack_no_seek_rel,
        .push_back_byte = wavpack_push_back,
        .get_length = wavpack_get_length,
        .can_seek = wavpack_can_seek,
        .write_bytes = NULL
};

static int decode_wavpack(StreamBridge *bridge) {
    char error[80] = {0};
    WavpackContext *context = WavpackOpenFileInputEx(&wavpack_reader, bridge,
            NULL, error, OPEN_WRAPPER, 0);
    if (!context) {
        throw_io(bridge->env, error[0] ? error : "Invalid ZIPX WavPack stream");
        return 0;
    }

    int ok = 0;
    int channels = WavpackGetNumChannels(context);
    int bytes_per_sample = WavpackGetBytesPerSample(context);
    int mode = WavpackGetMode(context);
    unsigned char file_format = WavpackGetFileFormat(context);
    if (channels < 1 || channels > 256 || bytes_per_sample < 1 || bytes_per_sample > 4) {
        throw_io(bridge->env, "Unsupported ZIPX WavPack channel or sample width");
        goto done;
    }
    if (!(mode & MODE_LOSSLESS) || (mode & MODE_HYBRID)) {
        throw_io(bridge->env, "ZIPX WavPack payload is not lossless");
        goto done;
    }
    if (file_format != WP_FORMAT_WAV && file_format != WP_FORMAT_W64) {
        throw_io(bridge->env, "ZIPX WavPack payload is not a WAV or Wave64 stream");
        goto done;
    }

    uint32_t wrapper_header = WavpackGetWrapperBytes(context);
    unsigned char *wrapper = WavpackGetWrapperData(context);
    if (wrapper_header == 0 || !wrapper || !bridge_write(bridge, wrapper, wrapper_header)) {
        if (!bridge->failed) throw_io(bridge->env, "ZIPX WavPack payload has no WAV wrapper");
        goto done;
    }

    size_t sample_count = (size_t) channels * WAVPACK_SAMPLES;
    if (sample_count > SIZE_MAX / sizeof(int32_t)) {
        throw_io(bridge->env, "ZIPX WavPack sample buffer is too large");
        goto done;
    }
    int32_t *samples = malloc(sample_count * sizeof(int32_t));
    if (!samples) {
        throw_io(bridge->env, "Out of memory decoding ZIPX WavPack data");
        goto done;
    }
    uint64_t pcm_bytes = 0;
    for (;;) {
        uint32_t frames = WavpackUnpackSamples(context, samples, WAVPACK_SAMPLES);
        if (!frames) break;
        size_t values = (size_t) frames * (size_t) channels;
        size_t packed_size = values * (size_t) bytes_per_sample;
        if (packed_size > bridge->native_output_size) {
            uint8_t *larger = realloc(bridge->native_output, packed_size);
            if (!larger) {
                free(samples);
                throw_io(bridge->env, "Out of memory packing ZIPX WavPack samples");
                goto done;
            }
            bridge->native_output = larger;
            bridge->native_output_size = packed_size;
        }
        uint8_t *packed = bridge->native_output;
        for (size_t i = 0; i < values; i++) {
            uint32_t value = (uint32_t) samples[i];
            if (bytes_per_sample == 1) value += 0x80U;
            for (int b = 0; b < bytes_per_sample; b++)
                *packed++ = (uint8_t) (value >> (b * 8));
        }
        if (!bridge_write(bridge, bridge->native_output, packed_size)) {
            free(samples);
            goto done;
        }
        pcm_bytes += packed_size;
    }
    free(samples);

    if (bridge->failed || pcm_bytes == 0 || WavpackGetNumErrors(context) != 0 ||
            WavpackLossyBlocks(context)) {
        if (!bridge->failed) throw_io(bridge->env, "Corrupt or lossy ZIPX WavPack stream");
        goto done;
    }
    wrapper = WavpackGetWrapperData(context);
    uint32_t wrapper_total = WavpackGetWrapperBytes(context);
    if (wrapper_total < wrapper_header) {
        throw_io(bridge->env, "Invalid ZIPX WavPack trailing wrapper");
        goto done;
    }
    if (wrapper_total > wrapper_header &&
            !bridge_write(bridge, wrapper + wrapper_header, wrapper_total - wrapper_header)) goto done;
    ok = 1;

done:
    WavpackCloseFile(context);
    return ok;
}

static size_t jpeg_read(void *context, uint8_t *buffer, size_t length) {
    return bridge_read((StreamBridge *) context, buffer, length);
}

static int decode_jpeg(StreamBridge *bridge) {
    WinZipJPEGDecompressor *decoder = AllocWinZipJPEGDecompressor(jpeg_read, bridge);
    if (!decoder) {
        throw_io(bridge->env, "Out of memory creating ZIPX JPEG decoder");
        return 0;
    }
    int ok = 0;
    int error = ReadWinZipJPEGHeader(decoder);
    if (error) goto decoder_error;

    unsigned iterations = 0;
    for (;;) {
        if (++iterations > JPEG_LOOP_LIMIT) {
            throw_io(bridge->env, "ZIPX JPEG bundle limit exceeded");
            goto done;
        }
        error = ReadNextWinZipJPEGBundle(decoder);
        if (error) goto decoder_error;
        if (!bridge_write(bridge, WinZipJPEGBundleMetadataBytes(decoder),
                WinZipJPEGBundleMetadataLength(decoder))) goto done;
        if (IsFinalWinZipJPEGBundle(decoder)) break;

        while (AreMoreWinZipJPEGSlicesAvailable(decoder)) {
            if (++iterations > JPEG_LOOP_LIMIT) {
                throw_io(bridge->env, "ZIPX JPEG slice limit exceeded");
                goto done;
            }
            error = ReadNextWinZipJPEGSlice(decoder);
            if (error) goto decoder_error;
            while (AreMoreWinZipJPEGBytesAvailable(decoder)) {
                size_t count = EncodeWinZipJPEGBlocksToBuffer(decoder,
                        bridge->native_output, bridge->native_output_size);
                if (count == 0) {
                    throw_io(bridge->env, "ZIPX JPEG decoder made no progress");
                    goto done;
                }
                if (!bridge_write(bridge, bridge->native_output, count)) goto done;
            }
        }
    }
    if (bridge->first_count != 2 || bridge->last_count != 2 ||
            bridge->first[0] != 0xff || bridge->first[1] != 0xd8 ||
            bridge->last[0] != 0xff || bridge->last[1] != 0xd9) {
        throw_io(bridge->env, "ZIPX JPEG output has invalid JPEG markers");
        goto done;
    }
    ok = 1;
    goto done;

decoder_error: {
        char message[96];
        snprintf(message, sizeof(message), "Invalid ZIPX JPEG stream (decoder error %d)", error);
        throw_io(bridge->env, message);
    }
done:
    FreeWinZipJPEGDecompressor(decoder);
    return ok;
}

JNIEXPORT jlong JNICALL
Java_com_readwide_codecs_ZipxNativeCodecs_nativeDecode(JNIEnv *env, jclass clazz,
        jint method, jobject input, jobject output, jlong output_limit) {
    (void) clazz;
    if (!input || !output || output_limit < 0) {
        throw_io(env, "Invalid ZIPX decoder argument");
        return -1;
    }
    StreamBridge bridge;
    if (!bridge_init(&bridge, env, input, output, output_limit)) {
        bridge_destroy(&bridge);
        throw_io(env, "Unable to initialize ZIPX decoder buffers");
        return -1;
    }
    int ok;
    if (method == METHOD_JPEG) ok = decode_jpeg(&bridge);
    else if (method == METHOD_WAVPACK) ok = decode_wavpack(&bridge);
    else {
        throw_io(env, "Unsupported native ZIPX method");
        ok = 0;
    }
    jlong result = ok && !bridge.failed ? (jlong) bridge.output_count : -1;
    bridge_destroy(&bridge);
    return result;
}
