/* default_allocator_native.c
 * JNI implementation for native off-heap memory allocation.
 * Fork addition for SlugYardEngineConfig performance mode.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

JNIEXPORT jlong JNICALL
Java_androidx_media3_exoplayer_upstream_DefaultAllocatorNative_nativeCreateAllocation(
    JNIEnv *env, jobject thiz, jint size) {
    void *ptr = NULL;
#ifdef _WIN32
    ptr = _aligned_malloc((size_t)size, 64);
#else
    if (posix_memalign(&ptr, 64, (size_t)size) != 0) {
        return 0;
    }
#endif
    return (jlong)(intptr_t)ptr;
}

JNIEXPORT void JNICALL
Java_androidx_media3_exoplayer_upstream_DefaultAllocatorNative_nativeFreeAllocation(
    JNIEnv *env, jobject thiz, jlong handle) {
    void *ptr = (void *)(intptr_t)handle;
    if (ptr != NULL) {
#ifdef _WIN32
        _aligned_free(ptr);
#else
        free(ptr);
#endif
    }
}
