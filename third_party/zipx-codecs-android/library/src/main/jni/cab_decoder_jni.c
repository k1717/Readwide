/* Copyright 2026 Readwide contributors. SPDX-License-Identifier: LGPL-2.1-only
 * Uses only libmspack's public API; the resulting separate shared library also
 * contains LGPL-2.1-only libmspack. No native filesystem access is exposed.
 */
#include <jni.h>
#include <mspack.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>

#define IO_CHUNK 65536

struct cab_session {
    struct mspack_system system; /* first: public self pointer */
    JNIEnv *env;                 /* current synchronous JNI call only */
    jobject owner;
    jbyteArray buffer;
    jmethodID read_source, checkpoint, write_output;
    jobject output;             /* local reference, only during nativeExtract */
    struct mscab_decompressor *decoder;
    struct mscabd_cabinet *cabinet;
    struct mscabd_file **files;
    int *folder_indices;
    jint file_count, volume_count;
    jlong *lengths;
    char *tokens;               /* distinct empty strings, identity not content */
    char output_token;
    uint64_t written, expected;
};

struct cab_file {
    struct mspack_file base;
    struct cab_session *session;
    jint volume; /* -1 for the single approved output callback */
    off_t offset;
};

struct folder_index { struct mscabd_folder *folder; jint index; };
static int compare_folder(const void *left, const void *right) {
    uintptr_t a = (uintptr_t)((const struct folder_index *)left)->folder;
    uintptr_t b = (uintptr_t)((const struct folder_index *)right)->folder;
    return (a > b) - (a < b);
}

static void fail(JNIEnv *env, const char *type, const char *message) {
    if ((*env)->ExceptionCheck(env)) return; /* preserve source/output exception */
    jclass cls = (*env)->FindClass(env, type);
    if (cls) { (*env)->ThrowNew(env, cls, message); (*env)->DeleteLocalRef(env, cls); }
}

static void io_error(struct cab_session *s, const char *message) {
    fail(s->env, "java/io/IOException", message);
}

static int checkpoint(struct cab_session *s) {
    if ((*s->env)->ExceptionCheck(s->env)) return 0;
    (*s->env)->CallVoidMethod(s->env, s->owner, s->checkpoint);
    return !(*s->env)->ExceptionCheck(s->env);
}

static void *cab_alloc(struct mspack_system *sys, size_t bytes) {
    (void)sys;
    return malloc(bytes);
}

static void cab_copy(void *src, void *dest, size_t bytes) { memcpy(dest, src, bytes); }
static void cab_message(struct mspack_file *file, const char *format, ...) {
    (void)file; (void)format; /* diagnostics never enable repair or become instructions */
}

static struct mspack_file *cab_open(struct mspack_system *sys, const char *name, int mode) {
    struct cab_session *s = (struct cab_session *)sys;
    if (!checkpoint(s)) return NULL;
    jint volume = -1;
    if (mode == MSPACK_SYS_OPEN_READ) {
        uintptr_t address = (uintptr_t)name, first = (uintptr_t)s->tokens;
        if (address < first || address - first >= (uintptr_t)s->volume_count) {
            io_error(s, "Unapproved CAB input token"); return NULL;
        }
        volume = (jint)(address - first);
    } else if (mode != MSPACK_SYS_OPEN_WRITE || name != &s->output_token || !s->output) {
        io_error(s, "Unapproved CAB output token"); return NULL;
    }
    struct cab_file *file = calloc(1, sizeof(*file));
    if (!file) { fail(s->env, "java/lang/OutOfMemoryError", "CAB file handle"); return NULL; }
    file->session = s; file->volume = volume;
    return &file->base;
}

static void cab_close(struct mspack_file *file) { free(file); }

static int cab_read(struct mspack_file *base, void *target, int bytes) {
    struct cab_file *file = (struct cab_file *)base;
    struct cab_session *s = file->session;
    JNIEnv *env = s->env;
    if (file->volume < 0 || bytes < 0) { io_error(s, "Invalid CAB read"); return -1; }
    int total = 0;
    while (total < bytes) {
        if (!checkpoint(s)) return -1;
        jlong left = s->lengths[file->volume] - (jlong)file->offset;
        if (left == 0) break;
        if (left < 0) { io_error(s, "CAB read beyond cabinet extent"); return -1; }
        int count = bytes - total;
        if (count > IO_CHUNK) count = IO_CHUNK;
        if ((jlong)count > left) count = (int)left;
        jint got = (*env)->CallIntMethod(env, s->owner, s->read_source,
                file->volume, (jlong)file->offset, s->buffer, (jint)count);
        if ((*env)->ExceptionCheck(env)) return -1;
        if (got <= 0 || got > count) { io_error(s, "Truncated or stalled CAB source"); return -1; }
        (*env)->GetByteArrayRegion(env, s->buffer, 0, got, (jbyte *)target + total);
        if ((*env)->ExceptionCheck(env)) return -1;
        total += got; file->offset += (off_t)got;
    }
    return total;
}

static int cab_write(struct mspack_file *base, void *source, int bytes) {
    struct cab_file *file = (struct cab_file *)base;
    struct cab_session *s = file->session;
    JNIEnv *env = s->env;
    if (file->volume != -1 || !s->output || bytes < 0 || s->written > s->expected
            || (uint64_t)bytes > s->expected - s->written) {
        io_error(s, "CAB output exceeds declared entry length"); return -1;
    }
    int total = 0;
    while (total < bytes) {
        if (!checkpoint(s)) return -1;
        int count = bytes - total;
        if (count > IO_CHUNK) count = IO_CHUNK;
        (*env)->SetByteArrayRegion(env, s->buffer, 0, count, (const jbyte *)source + total);
        if ((*env)->ExceptionCheck(env)) return -1;
        (*env)->CallVoidMethod(env, s->output, s->write_output, s->buffer, (jint)0, (jint)count);
        if ((*env)->ExceptionCheck(env)) return -1;
        total += count; s->written += (uint64_t)count;
    }
    return total;
}

static int cab_seek(struct mspack_file *base, off_t offset, int mode) {
    struct cab_file *file = (struct cab_file *)base;
    struct cab_session *s = file->session;
    if (!checkpoint(s)) return -1;
    if (file->volume < 0) { io_error(s, "CAB output seek is unsupported"); return -1; }
    off_t origin;
    if (mode == MSPACK_SYS_SEEK_START) origin = 0;
    else if (mode == MSPACK_SYS_SEEK_CUR) origin = file->offset;
    else if (mode == MSPACK_SYS_SEEK_END) origin = (off_t)s->lengths[file->volume];
    else { io_error(s, "Invalid CAB seek mode"); return -1; }
    /* All origins are nonnegative and within uint32; avoid signed addition overflow. */
    if (offset < -origin || offset > (off_t)s->lengths[file->volume] - origin) {
        io_error(s, "CAB seek outside cabinet extent"); return -1;
    }
    file->offset = origin + offset;
    return 0;
}

static off_t cab_tell(struct mspack_file *base) { return ((struct cab_file *)base)->offset; }
static uint32_t u32(const unsigned char *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static unsigned int u16(const unsigned char *p) { return p[0] | ((unsigned int)p[1] << 8); }

/* Independent opened-input check before libmspack parses it. In strict mode the
 * upstream parser reads CFFILE immediately after CFFOLDER, ignoring coffFiles.
 * Reject an atypical offset here rather than enable salvage or parse another list.
 */
static int check_header(struct cab_session *s, jint volume, jint set_id) {
    struct cab_file file = {0}; file.session = s; file.volume = volume;
    unsigned char fixed[36], reserved[4];
    if (cab_read(&file.base, fixed, 36) != 36) return 0;
    unsigned int flags = u16(fixed + 30), folders = u16(fixed + 26), files = u16(fixed + 28);
    uint32_t file_offset = u32(fixed + 16);
    if (u32(fixed) != 0x4643534dU || u32(fixed + 4) || u32(fixed + 12) || u32(fixed + 20)
            || fixed[24] != 3 || fixed[25] != 1 || (flags & ~7U)
            || u32(fixed + 8) != (uint64_t)s->lengths[volume]
            || u16(fixed + 32) != (unsigned int)set_id || u16(fixed + 34) != (unsigned int)volume
            || !!(flags & MSCAB_HDR_PREVCAB) != (volume > 0)
            || !!(flags & MSCAB_HDR_NEXTCAB) != (volume + 1 < s->volume_count)
            || file_offset > (uint64_t)s->lengths[volume]
            || (uint64_t)files * 17 > (uint64_t)s->lengths[volume] - file_offset) {
        io_error(s, "CAB opened header does not match the approved complete set"); return 0;
    }
    unsigned int folder_reserve = 0;
    if (flags & MSCAB_HDR_RESV) {
        if (cab_read(&file.base, reserved, 4) != 4) return 0;
        folder_reserve = reserved[2];
        if (cab_seek(&file.base, (off_t)u16(reserved), MSPACK_SYS_SEEK_CUR)) return 0;
    }
    unsigned int string_count = ((flags & MSCAB_HDR_PREVCAB) ? 2U : 0U)
            + ((flags & MSCAB_HDR_NEXTCAB) ? 2U : 0U);
    for (unsigned int field = 0; field < string_count; field++) {
        unsigned int n;
        for (n = 0; n < 256; n++) {
            unsigned char c;
            if ((uint64_t)file.offset >= file_offset || cab_read(&file.base, &c, 1) != 1) return 0;
            if (!c) break;
        }
        if (n == 256 || (n == 0 && !(field & 1U))) {
            io_error(s, "Invalid CAB linked-volume field"); return 0;
        }
    }
    uint64_t table_end = (uint64_t)file.offset + (uint64_t)folders * (8U + folder_reserve);
    if (table_end != file_offset) {
        io_error(s, "CAB file-table offset is not contiguous; strict decoder cannot read it"); return 0;
    }
    return 1;
}

static void decoder_error(struct cab_session *s, int result) {
    if (result == MSPACK_ERR_NOMEMORY) fail(s->env, "java/lang/OutOfMemoryError", "CAB decoder allocation");
    else if (result == MSPACK_ERR_CHECKSUM) io_error(s, "CAB data checksum mismatch");
    else io_error(s, "CAB decoder rejected the cabinet set or compressed data");
}

static void destroy(struct cab_session *s, JNIEnv *env) {
    if (!s) return;
    s->env = env;
    if (s->decoder) {
        if (s->cabinet) s->decoder->close(s->decoder, s->cabinet);
        mspack_destroy_cab_decompressor(s->decoder);
    }
    if (s->owner) (*env)->DeleteGlobalRef(env, s->owner);
    if (s->buffer) (*env)->DeleteGlobalRef(env, s->buffer);
    free(s->files); free(s->folder_indices); free(s->lengths); free(s->tokens); free(s);
}

JNIEXPORT jlong JNICALL Java_com_readwide_codecs_CabNativeDecoder_nativeOpen(
        JNIEnv *env, jclass cls, jobject owner, jint set_id, jlongArray lengths) {
    (void)cls;
    int selftest;
    MSPACK_SYS_SELFTEST(selftest);
    if (selftest != MSPACK_ERR_OK || sizeof(off_t) != 8) {
        fail(env, "java/io/IOException", "CAB decoder off_t ABI mismatch"); return 0;
    }
    if (!owner || !lengths || set_id < 0 || set_id > 65535) {
        fail(env, "java/io/IOException", "Invalid CAB session metadata"); return 0;
    }
    jsize volumes = (*env)->GetArrayLength(env, lengths);
    if (volumes < 1 || volumes > 65536) {
        fail(env, "java/io/IOException", "Invalid CAB volume count"); return 0;
    }
    struct cab_session *s = calloc(1, sizeof(*s));
    if (!s) { fail(env, "java/lang/OutOfMemoryError", "CAB session"); return 0; }
    struct folder_index *folder_map = NULL;
    s->env = env; s->volume_count = volumes;
    s->lengths = malloc((size_t)volumes * sizeof(jlong));
    s->tokens = calloc((size_t)volumes, 1);
    if (!s->lengths || !s->tokens) { fail(env, "java/lang/OutOfMemoryError", "CAB volume metadata"); goto failed; }
    (*env)->GetLongArrayRegion(env, lengths, 0, volumes, s->lengths);
    if ((*env)->ExceptionCheck(env)) goto failed;
    for (jint i = 0; i < volumes; i++) {
        if (s->lengths[i] < 36 || s->lengths[i] > UINT32_MAX) { io_error(s, "Invalid CAB extent"); goto failed; }
    }
    s->owner = (*env)->NewGlobalRef(env, owner);
    if (!s->owner) goto failed;
    jbyteArray buffer = (*env)->NewByteArray(env, IO_CHUNK);
    if (!buffer) goto failed;
    s->buffer = (*env)->NewGlobalRef(env, buffer);
    (*env)->DeleteLocalRef(env, buffer);
    if (!s->buffer) goto failed;
    jclass owner_class = (*env)->GetObjectClass(env, owner);
    if (!owner_class) goto failed;
    s->read_source = (*env)->GetMethodID(env, owner_class, "readSource", "(IJ[BI)I");
    if (!(*env)->ExceptionCheck(env)) s->checkpoint = (*env)->GetMethodID(env, owner_class, "checkpoint", "()V");
    (*env)->DeleteLocalRef(env, owner_class);
    if (!s->read_source || !s->checkpoint) goto failed;
    jclass output_class = (*env)->FindClass(env, "java/io/OutputStream");
    if (!output_class) goto failed;
    s->write_output = (*env)->GetMethodID(env, output_class, "write", "([BII)V");
    (*env)->DeleteLocalRef(env, output_class);
    if (!s->write_output) goto failed;
    s->system = (struct mspack_system){cab_open, cab_close, cab_read, cab_write,
            cab_seek, cab_tell, cab_message, cab_alloc, free, cab_copy, NULL};
    s->decoder = mspack_create_cab_decompressor(&s->system);
    if (!s->decoder) { fail(env, "java/lang/OutOfMemoryError", "CAB decoder"); goto failed; }
    if (s->decoder->set_param(s->decoder, MSCABD_PARAM_FIXMSZIP, 0) != MSPACK_ERR_OK
            || s->decoder->set_param(s->decoder, MSCABD_PARAM_SALVAGE, 0) != MSPACK_ERR_OK) {
        io_error(s, "Cannot configure strict CAB decoding"); goto failed;
    }
    struct mscabd_cabinet *last = NULL;
    for (jint i = 0; i < volumes; i++) {
        if (!check_header(s, i, set_id)) { io_error(s, "Invalid CAB opened header"); goto failed; }
        struct mscabd_cabinet *cab = s->decoder->open(s->decoder, s->tokens + i);
        if (!cab) { decoder_error(s, s->decoder->last_error(s->decoder)); goto failed; }
        if (cab->base_offset != 0 || cab->length != (uint64_t)s->lengths[i]
                || cab->set_id != set_id || cab->set_index != i
                || !!(cab->flags & MSCAB_HDR_PREVCAB) != (i > 0)
                || !!(cab->flags & MSCAB_HDR_NEXTCAB) != (i + 1 < volumes)) {
            s->decoder->close(s->decoder, cab); io_error(s, "CAB metadata changed while opening"); goto failed;
        }
        if (last) {
            int result = s->decoder->append(s->decoder, last, cab);
            if (result != MSPACK_ERR_OK) {
                s->decoder->close(s->decoder, cab); decoder_error(s, result); goto failed;
            }
        } else s->cabinet = cab;
        last = cab;
    }
    size_t count = 0;
    for (struct mscabd_file *file = s->cabinet->files; file; file = file->next) {
        if (!checkpoint(s)) goto failed;
        if (++count > INT_MAX || count > SIZE_MAX / sizeof(*s->files)
                || count > SIZE_MAX / sizeof(*s->folder_indices)) {
            fail(env, "java/lang/OutOfMemoryError", "CAB entry list exceeds addressable array"); goto failed;
        }
    }
    s->file_count = (jint)count;
    if (count) {
        s->files = malloc(count * sizeof(*s->files));
        s->folder_indices = malloc(count * sizeof(*s->folder_indices));
        if (!s->files || !s->folder_indices) { fail(env, "java/lang/OutOfMemoryError", "CAB entry index"); goto failed; }
    }
    size_t folder_count = 0;
    for (struct mscabd_folder *folder = s->cabinet->folders; folder; folder = folder->next) {
        if (!checkpoint(s)) goto failed;
        if (++folder_count > INT_MAX || folder_count > SIZE_MAX / sizeof(*folder_map)) {
            fail(env, "java/lang/OutOfMemoryError", "CAB folder index exceeds addressable array"); goto failed;
        }
    }
    if (folder_count) {
        folder_map = malloc(folder_count * sizeof(*folder_map));
        if (!folder_map) { fail(env, "java/lang/OutOfMemoryError", "CAB folder index"); goto failed; }
        size_t f = 0;
        for (struct mscabd_folder *folder = s->cabinet->folders; folder; folder = folder->next, f++) {
            folder_map[f] = (struct folder_index){folder, (jint)f};
        }
        qsort(folder_map, folder_count, sizeof(*folder_map), compare_folder);
    }
    jint n = 0;
    for (struct mscabd_file *file = s->cabinet->files; file; file = file->next, n++) {
        if (!checkpoint(s)) goto failed;
        s->files[n] = file;
        struct folder_index key = {file->folder, 0};
        struct folder_index *found = folder_count
                ? bsearch(&key, folder_map, folder_count, sizeof(*folder_map), compare_folder) : NULL;
        if (!found) { io_error(s, "CAB file has no complete folder"); goto failed; }
        s->folder_indices[n] = found->index;
    }
    free(folder_map);
    s->env = NULL;
    return (jlong)(intptr_t)s;
failed:
    free(folder_map);
    destroy(s, env);
    return 0;
}

JNIEXPORT jobjectArray JNICALL Java_com_readwide_codecs_CabNativeDecoder_nativeEntries(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    struct cab_session *s = (struct cab_session *)(intptr_t)handle;
    if (!s) { fail(env, "java/io/IOException", "CAB decoder is closed"); return NULL; }
    s->env = env;
    jclass entry_class = (*env)->FindClass(env, "com/readwide/codecs/CabNativeDecoder$Entry");
    if (!entry_class) { s->env = NULL; return NULL; }
    jmethodID init = (*env)->GetMethodID(env, entry_class, "<init>", "([BJIIJI)V");
    if (!init) { (*env)->DeleteLocalRef(env, entry_class); s->env = NULL; return NULL; }
    jobjectArray result = (*env)->NewObjectArray(env, s->file_count, entry_class, NULL);
    if (!result) { (*env)->DeleteLocalRef(env, entry_class); s->env = NULL; return NULL; }
    for (jint i = 0; i < s->file_count && checkpoint(s); i++) {
        struct mscabd_file *file = s->files[i];
        size_t length = strlen(file->filename);
        if (length > 255) { io_error(s, "CAB name exceeds decoder metadata range"); break; }
        jbyteArray name = (*env)->NewByteArray(env, (jsize)length);
        if (!name) break;
        (*env)->SetByteArrayRegion(env, name, 0, (jsize)length, (const jbyte *)file->filename);
        jobject entry = NULL;
        if (!(*env)->ExceptionCheck(env)) entry = (*env)->NewObject(env, entry_class, init, name,
                (jlong)file->length, (jint)file->attribs, (jint)file->folder->comp_type,
                (jlong)file->offset, (jint)s->folder_indices[i]);
        (*env)->DeleteLocalRef(env, name);
        if (!entry) break;
        (*env)->SetObjectArrayElement(env, result, i, entry);
        (*env)->DeleteLocalRef(env, entry);
        if ((*env)->ExceptionCheck(env)) break;
    }
    (*env)->DeleteLocalRef(env, entry_class);
    s->env = NULL;
    return result;
}

JNIEXPORT void JNICALL Java_com_readwide_codecs_CabNativeDecoder_nativeExtract(
        JNIEnv *env, jclass cls, jlong handle, jint index, jobject output) {
    (void)cls;
    struct cab_session *s = (struct cab_session *)(intptr_t)handle;
    if (!s || index < 0 || index >= s->file_count || !output) {
        fail(env, "java/io/IOException", "Invalid CAB extraction request"); return;
    }
    s->env = env; s->output = output; s->written = 0; s->expected = s->files[index]->length;
    if (checkpoint(s)) {
        int result = s->decoder->extract(s->decoder, s->files[index], &s->output_token);
        if (result != MSPACK_ERR_OK) decoder_error(s, result);
        else if (s->written != s->expected) io_error(s, "CAB decoded length mismatch");
    }
    s->output = NULL; s->env = NULL;
}

JNIEXPORT void JNICALL Java_com_readwide_codecs_CabNativeDecoder_nativeClose(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    destroy((struct cab_session *)(intptr_t)handle, env);
}
