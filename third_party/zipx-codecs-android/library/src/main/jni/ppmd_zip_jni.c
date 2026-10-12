/* Readwide ZIP PPMd-I adapter. SPDX-License-Identifier: Apache-2.0
 * Only the pinned public-domain Ppmd8 decoder is linked. No ZIP parser/encoder.
 */
#include "Precomp.h"
#include "Ppmd8.h"
#include <jni.h>
#include <setjmp.h>
#include <stdint.h>
#include <stdlib.h>

#define BUFFER_SIZE 65536

typedef struct {
    IByteIn input_api; /* first: callback casts the public interface back to its owner */
    CPpmd8 model;
    jmp_buf abort_read;
    JNIEnv *env;
    jobject owner;         /* local references, valid only inside nativeRead */
    jbyteArray packed;
    jmethodID read_packed, checkpoint;
    unsigned order, restoration;
    int initialized, ended, failed;
    jlong expected, produced;
    size_t position, available;
    Byte input[BUFFER_SIZE], output[BUFFER_SIZE];
} PpmdStream;

static void *allocate(ISzAllocPtr allocator,size_t size) { (void)allocator;return malloc(size); }
static void deallocate(ISzAllocPtr allocator,void *memory) { (void)allocator;free(memory); }
static const ISzAlloc allocator={allocate,deallocate};

static void throw_exception(JNIEnv *env,const char *name,const char *message) {
    if((*env)->ExceptionCheck(env))return;
    jclass type=(*env)->FindClass(env,name);
    if(type!=NULL){(*env)->ThrowNew(env,type,message);(*env)->DeleteLocalRef(env,type);}
}

static void throw_io(JNIEnv *env,const char *message) {
    throw_exception(env,"java/io/IOException",message);
}

static void fail(PpmdStream *stream,const char *message) {
    throw_io(stream->env,message);
    /* Pure C decoder frames only: abort immediately rather than feed fake zero
       bytes into the range coder after EOF/cancellation or a pending Java error. */
    longjmp(stream->abort_read,1);
}

static void fail_data(PpmdStream *stream,const char *message) {
    throw_exception(stream->env,"com/readwide/codecs/PpmdZipInputStream$DataException",message);
    longjmp(stream->abort_read,1);
}

static void checkpoint(PpmdStream *stream) {
    JNIEnv *env=stream->env;
    (*env)->CallVoidMethod(env,stream->owner,stream->checkpoint);
    if((*env)->ExceptionCheck(env))fail(stream,"ZIP PPMd-I checkpoint failed");
}

static int refill(PpmdStream *stream) {
    JNIEnv *env=stream->env;
    jint count=(*env)->CallIntMethod(env,stream->owner,stream->read_packed,stream->packed);
    if((*env)->ExceptionCheck(env))fail(stream,"ZIP PPMd-I input failed");
    if(count == -1)return 0;
    if(count<=0 || count>BUFFER_SIZE)fail(stream,"Invalid ZIP PPMd-I input progress");
    (*env)->GetByteArrayRegion(env,stream->packed,0,count,(jbyte *)stream->input);
    if((*env)->ExceptionCheck(env))fail(stream,"ZIP PPMd-I input transfer failed");
    stream->position=0;stream->available=(size_t)count;
    return 1;
}

static Byte read_byte(IByteInPtr interface) {
    PpmdStream *stream=(PpmdStream *)(void *)interface;
    if(stream->position==stream->available && !refill(stream))fail_data(stream,"Truncated ZIP PPMd-I payload");
    return stream->input[stream->position++];
}

static void clear_call(PpmdStream *stream) {
    stream->env=NULL;stream->owner=NULL;stream->packed=NULL;
}

JNIEXPORT jlong JNICALL Java_com_readwide_codecs_PpmdZipInputStream_nativeOpen(
        JNIEnv *env,jclass type,jobject owner,jint properties,jlong expected,jint freeze_revision) {
    (void)type;
    unsigned order=((unsigned)properties&15)+1;
    unsigned memory=(((unsigned)properties>>4)&255)+1;
    unsigned restoration=(unsigned)properties>>12;
    if(properties<0 || properties>65535 || expected<0 || order<PPMD8_MIN_ORDER
            || order>PPMD8_MAX_ORDER || restoration>2 || freeze_revision<0 || freeze_revision>2
            || (restoration==2 ? freeze_revision==0 : freeze_revision!=0)){
        throw_io(env,restoration==2 && freeze_revision==0
                ? "ZIP PPMd-I freeze requires an explicit verified revision" : "Invalid ZIP PPMd-I properties/profile");
        return 0;
    }
    PpmdStream *stream=(PpmdStream *)calloc(1,sizeof(*stream));
    if(stream==NULL){throw_io(env,"Cannot allocate ZIP PPMd-I session");return 0;}
    Ppmd8_Construct(&stream->model);
    stream->model.ReadwideFreezeRevision1=(freeze_revision==1);
    jclass owner_type=(*env)->GetObjectClass(env,owner);
    if(owner_type!=NULL){
        stream->read_packed=(*env)->GetMethodID(env,owner_type,"readPacked","([B)I");
        if(!(*env)->ExceptionCheck(env))stream->checkpoint=(*env)->GetMethodID(env,owner_type,"checkCancelled","()V");
        (*env)->DeleteLocalRef(env,owner_type);
    }
    if((*env)->ExceptionCheck(env) || !stream->read_packed || !stream->checkpoint){free(stream);return 0;}
    if(!Ppmd8_Alloc(&stream->model,(UInt32)memory<<20,&allocator)){
        free(stream);throw_io(env,"Not enough memory for the ZIP PPMd-I model");return 0;
    }
    stream->input_api.Read=read_byte;
    stream->model.Stream.In=&stream->input_api;
    stream->order=order;stream->restoration=restoration;stream->expected=expected;
    return (jlong)(intptr_t)stream;
}

JNIEXPORT jint JNICALL Java_com_readwide_codecs_PpmdZipInputStream_nativeRead(
        JNIEnv *env,jclass type,jlong handle,jobject owner,jbyteArray packed,
        jbyteArray output,jint offset,jint length) {
    (void)type;
    PpmdStream *stream=(PpmdStream *)(intptr_t)handle;
    if(stream==NULL || stream->failed || owner==NULL || packed==NULL || output==NULL
            || length<=0 || length>BUFFER_SIZE || offset<0
            || (*env)->GetArrayLength(env,packed)!=BUFFER_SIZE
            || offset>(*env)->GetArrayLength(env,output)-length){
        throw_io(env,"Invalid ZIP PPMd-I read state");return -1;
    }
    if(stream->ended)return -1;
    stream->env=env;stream->owner=owner;stream->packed=packed;
    if(setjmp(stream->abort_read)){
        stream->failed=1;clear_call(stream);return -1;
    }
    if(!stream->initialized){
        if(!Ppmd8_Init_RangeDec(&stream->model))fail_data(stream,"Invalid ZIP PPMd-I range header");
        Ppmd8_Init(&stream->model,stream->order,stream->restoration);
        stream->initialized=1;
    }
    int count=0;
    while(count<length){
        if((count&1023)==0)checkpoint(stream);
        int symbol=Ppmd8_DecodeSymbol(&stream->model);
        if(symbol==PPMD8_SYM_END){
            if(stream->produced!=stream->expected || !Ppmd8_RangeDec_IsFinishedOK(&stream->model))
                fail_data(stream,"ZIP PPMd-I final size or range state mismatch");
            if(stream->position!=stream->available || refill(stream))fail_data(stream,"Trailing ZIP PPMd-I packed data");
            stream->ended=1;break;
        }
        if(symbol<0 || symbol>255)fail_data(stream,"Corrupt ZIP PPMd-I symbol");
        if(stream->produced==stream->expected)fail_data(stream,"ZIP PPMd-I output exceeds declared size");
        stream->output[count++]=(Byte)symbol;stream->produced++;
    }
    if(count>0)(*env)->SetByteArrayRegion(env,output,offset,count,(const jbyte *)stream->output);
    if((*env)->ExceptionCheck(env)){stream->failed=1;clear_call(stream);return -1;}
    clear_call(stream);
    return count==0 ? -1 : count;
}

JNIEXPORT void JNICALL Java_com_readwide_codecs_PpmdZipInputStream_nativeFree(
        JNIEnv *env,jclass type,jlong handle) {
    (void)env;(void)type;
    PpmdStream *stream=(PpmdStream *)(intptr_t)handle;
    if(stream!=NULL){Ppmd8_Free(&stream->model,&allocator);free(stream);}
}
