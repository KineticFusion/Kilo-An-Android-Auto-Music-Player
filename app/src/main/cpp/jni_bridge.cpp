#include <jni.h>
#include "native_mixer.h"
#include <android/log.h>

#define LOG_TAG "KiloJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global mixer instance
static NativeMixer* gMixer = nullptr;
static std::mutex gMixerMutex;

// Deck IDs matching Kotlin enum
enum JniDeckId { JNI_DECK_A = 0, JNI_DECK_B = 1 };

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_kilo_playback_NativeAudioEngine_nativeInit(JNIEnv* env, jclass clazz,
                                                            jint sampleRate, jint channelCount) {
    std::lock_guard<std::mutex> lock(gMixerMutex);
    if (gMixer) {
        LOGD("NativeAudioEngine already initialized");
        return JNI_TRUE;
    }

    gMixer = new NativeMixer();
    bool success = gMixer->initialize(sampleRate, channelCount);
    if (!success) {
        delete gMixer;
        gMixer = nullptr;
        LOGE("NativeAudioEngine initialization failed");
        return JNI_FALSE;
    }
    LOGD("NativeAudioEngine initialized successfully");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_example_kilo_playback_NativeAudioEngine_nativeRelease(JNIEnv* env, jclass clazz) {
    std::lock_guard<std::mutex> lock(gMixerMutex);
    if (gMixer) {
        gMixer->shutdown();
        delete gMixer;
        gMixer = nullptr;
        LOGD("NativeAudioEngine released");
    }
}

JNIEXPORT jint JNICALL
Java_com_example_kilo_playback_NativeAudioEngine_nativePushPcm(JNIEnv* env, jclass clazz,
                                                                jint deckId, jfloatArray buffer,
                                                                jint numFrames) {
    if (!gMixer) {
        LOGE("nativePushPcm: mixer not initialized");
        return -1;
    }

    if (!buffer || numFrames <= 0) {
        return 0;
    }

    // Get direct access to float array (no copy if possible)
    void* elements = env->GetPrimitiveArrayCritical(buffer, nullptr);
    if (!elements) {
        LOGE("nativePushPcm: GetPrimitiveArrayCritical failed");
        return -1;
    }

    DeckId deck = (deckId == JNI_DECK_A) ? DeckId::A : DeckId::B;
    size_t written = gMixer->pushPcm(deck, static_cast<jfloat*>(elements), static_cast<size_t>(numFrames));

    env->ReleasePrimitiveArrayCritical(buffer, elements, JNI_ABORT); // No copy back needed

    return static_cast<jint>(written);
}

JNIEXPORT void JNICALL
Java_com_example_kilo_playback_NativeAudioEngine_nativeStartCrossfade(JNIEnv* env, jclass clazz,
                                                                       jlong durationFrames,
                                                                       jboolean enableAutomixEq) {
    if (!gMixer) {
        LOGE("nativeStartCrossfade: mixer not initialized");
        return;
    }

    gMixer->startCrossfade(static_cast<size_t>(durationFrames), enableAutomixEq);
}

JNIEXPORT void JNICALL
Java_com_example_kilo_playback_NativeAudioEngine_nativeStopDeck(JNIEnv* env, jclass clazz,
                                                                 jint deckId) {
    if (!gMixer) {
        LOGE("nativeStopDeck: mixer not initialized");
        return;
    }

    DeckId deck = (deckId == JNI_DECK_A) ? DeckId::A : DeckId::B;
    gMixer->stopDeck(deck);
}

JNIEXPORT jboolean JNICALL
Java_com_example_kilo_playback_NativeAudioEngine_nativeIsCrossfading(JNIEnv* env, jclass clazz) {
    if (!gMixer) return JNI_FALSE;
    return gMixer->isCrossfading() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_example_kilo_playback_NativeAudioEngine_nativeGetFillRatio(JNIEnv* env, jclass clazz,
                                                                     jint deckId) {
    if (!gMixer) return 0.0f;
    DeckId deck = (deckId == JNI_DECK_A) ? DeckId::A : DeckId::B;
    return gMixer->getFillRatio(deck);
}

JNIEXPORT jint JNICALL
Java_com_example_kilo_playback_NativeAudioEngine_nativeGetAvailableFrames(JNIEnv* env, jclass clazz,
                                                                           jint deckId) {
    if (!gMixer) return 0;
    DeckId deck = (deckId == JNI_DECK_A) ? DeckId::A : DeckId::B;
    return static_cast<jint>(gMixer->getAvailableFrames(deck));
}

JNIEXPORT jint JNICALL
Java_com_example_kilo_playback_NativeAudioEngine_nativeGetSampleRate(JNIEnv* env, jclass clazz) {
    if (!gMixer) return 48000;
    return gMixer->getSampleRate();
}

} // extern "C"