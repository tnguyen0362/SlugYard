/* sample_data_queue_native.c
 * JNI implementation for zero-copy buffer operations.
 * Fork addition for SlugYardEngineConfig performance mode.
 */
#include <jni.h>
#include <string.h>

JNIEXPORT jint JNICALL
Java_androidx_media3_exoplayer_source_SampleDataQueueNative_copyFromArray(
    JNIEnv *env, jobject thiz, jlong dstAddress, jbyteArray src, jint srcOffset, jint length) {
    jbyte *dst = (jbyte *)(intptr_t)dstAddress;
    jbyte *srcBytes = (*env)->GetByteArrayElements(env, src, NULL);
    memcpy(dst, srcBytes + srcOffset, (size_t)length);
    (*env)->ReleaseByteArrayElements(env, src, srcBytes, JNI_ABORT);
    return length;
}

JNIEXPORT jint JNICALL
Java_androidx_media3_exoplayer_source_SampleDataQueueNative_copyToArray(
    JNIEnv *env, jobject thiz, jbyteArray dst, jint dstOffset, jlong srcAddress, jint length) {
    jbyte *src = (jbyte *)(intptr_t)srcAddress;
    (*env)->SetByteArrayRegion(env, dst, dstOffset, length, src);
    return length;
}

JNIEXPORT jint JNICALL
Java_androidx_media3_exoplayer_source_SampleDataQueueNative_copyBetweenDirectBuffers(
    JNIEnv *env, jobject thiz, jobject dst, jint dstOffset, jobject src, jint srcOffset, jint length) {
    jbyte *dstPtr = (jbyte *)(*env)->GetDirectBufferAddress(env, dst);
    jbyte *srcPtr = (jbyte *)(*env)->GetDirectBufferAddress(env, src);
    if (!dstPtr || !srcPtr) return -1;
    memcpy(dstPtr + dstOffset, srcPtr + srcOffset, (size_t)length);
    return length;
}

JNIEXPORT jint JNICALL
Java_androidx_media3_exoplayer_source_SampleDataQueueNative_copyBetweenAddresses(
    JNIEnv *env, jobject thiz, jlong dstAddress, jlong srcAddress, jint length) {
    void *dst = (void *)(intptr_t)dstAddress;
    void *src = (void *)(intptr_t)srcAddress;
    memcpy(dst, src, (size_t)length);
    return length;
}

JNIEXPORT jlong JNICALL
Java_androidx_media3_exoplayer_source_SampleDataQueueNative_getDirectBufferAddress(
    JNIEnv *env, jobject thiz, jobject buffer) {
    return (jlong)(intptr_t)(*env)->GetDirectBufferAddress(env, buffer);
}

JNIEXPORT jlong JNICALL
Java_androidx_media3_exoplayer_source_SampleDataQueueNative_getDirectBufferAddressCached(
    JNIEnv *env, jobject thiz, jobject buffer) {
    return (jlong)(intptr_t)(*env)->GetDirectBufferAddress(env, buffer);
}
