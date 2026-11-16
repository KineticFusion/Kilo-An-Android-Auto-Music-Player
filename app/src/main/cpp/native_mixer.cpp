#include "native_mixer.h"
#include "oboe_audio_stream.h"
#include <android/log.h>
#include <algorithm>

#define LOG_TAG "KiloNativeMixer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

bool NativeMixer::initialize(int32_t sampleRate, int32_t channelCount) {
    sampleRate_ = sampleRate;
    channelCount_ = channelCount;

    // Configure automix filter (starts at 20Hz, sweeps to 350Hz)
    automixFilter_.setParams(sampleRate, 20.0f, 0.707f);
    automixFreqSmoother_.reset(20.0f);
    automixFreqSmoother_.setSmoothingTime(10.0f, sampleRate);
    automixFreqTarget_.store(20.0f, std::memory_order_relaxed);

    // Initialize Oboe stream
    oboeStream_ = new OboeAudioStream(*this);
    oboe::Result result = oboeStream_->openStream(sampleRate, channelCount);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open Oboe stream: %s", oboe::convertToText(result));
        delete oboeStream_;
        oboeStream_ = nullptr;
        return false;
    }

    result = oboeStream_->start();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start Oboe stream: %s", oboe::convertToText(result));
        delete oboeStream_;
        oboeStream_ = nullptr;
        return false;
    }

    LOGD("NativeMixer initialized: %d Hz, %d channels", sampleRate, channelCount);
    return true;
}

void NativeMixer::shutdown() {
    if (oboeStream_) {
        oboeStream_->close();
        delete oboeStream_;
        oboeStream_ = nullptr;
    }
    bufferA_.clear();
    bufferB_.clear();
    state_.store(CrossfadeState::Idle, std::memory_order_relaxed);
    LOGD("NativeMixer shutdown complete");
}

size_t NativeMixer::pushPcm(DeckId deckId, const float* data, size_t numFrames) {
    if (deckId == DeckId::A) {
        return bufferA_.push(data, numFrames);
    } else {
        return bufferB_.push(data, numFrames);
    }
}

void NativeMixer::startCrossfade(size_t durationFrames, bool enableAutomixEq) {
    // Determine current and next deck
    DeckId current = activeDeck_.load(std::memory_order_relaxed);
    DeckId next = (current == DeckId::A) ? DeckId::B : DeckId::A;

    nextDeck_.store(next, std::memory_order_release);
    crossfadeTotalFrames_.store(durationFrames, std::memory_order_release);
    crossfadeCurrentFrame_.store(0, std::memory_order_relaxed);
    crossfadeProgress_.store(0.0f, std::memory_order_relaxed);
    automixEqEnabled_.store(enableAutomixEq, std::memory_order_relaxed);

    if (enableAutomixEq) {
        // Start HPF sweep from 20Hz to 350Hz over crossfade duration
        automixFreqTarget_.store(350.0f, std::memory_order_release);
        automixFreqSmoother_.setTarget(350.0f);
        state_.store(CrossfadeState::AutomixTransition, std::memory_order_release);
    } else {
        state_.store(CrossfadeState::FadingOut, std::memory_order_release);
    }

    LOGD("Crossfade started: duration=%zu frames, automixEQ=%d", durationFrames, enableAutomixEq);
}

void NativeMixer::stopDeck(DeckId deckId) {
    if (deckId == DeckId::A) {
        bufferA_.clear();
    } else {
        bufferB_.clear();
    }
}

void NativeMixer::onAudioReady(float* audioData, int32_t numFrames) {
    const CrossfadeState currentState = state_.load(std::memory_order_acquire);
    const DeckId activeDeck = activeDeck_.load(std::memory_order_acquire);

    // Temporary buffers for reading from ring buffers
    static thread_local std::vector<float> tempBufferA;
    static thread_local std::vector<float> tempBufferB;
    tempBufferA.resize(numFrames * 2);
    tempBufferB.resize(numFrames * 2);

    size_t framesReadA = 0;
    size_t framesReadB = 0;
    bool deckAActive = false;
    bool deckBActive = false;

    // Read from active deck(s)
    if (currentState == CrossfadeState::Idle) {
        // Single deck playback
        if (activeDeck == DeckId::A) {
            framesReadA = bufferA_.pop(tempBufferA.data(), numFrames);
            deckAActive = (framesReadA > 0);
            // Fill silence if underrun
            if (framesReadA < static_cast<size_t>(numFrames)) {
                std::fill(tempBufferA.begin() + framesReadA * 2, tempBufferA.end(), 0.0f);
            }
        } else {
            framesReadB = bufferB_.pop(tempBufferB.data(), numFrames);
            deckBActive = (framesReadB > 0);
            if (framesReadB < static_cast<size_t>(numFrames)) {
                std::fill(tempBufferB.begin() + framesReadB * 2, tempBufferB.end(), 0.0f);
            }
        }
    } else {
        // Crossfade active - read from both decks
        framesReadA = bufferA_.pop(tempBufferA.data(), numFrames);
        framesReadB = bufferB_.pop(tempBufferB.data(), numFrames);
        deckAActive = (framesReadA > 0);
        deckBActive = (framesReadB > 0);

        // Fill silence for underruns
        if (framesReadA < static_cast<size_t>(numFrames)) {
            std::fill(tempBufferA.begin() + framesReadA * 2, tempBufferA.end(), 0.0f);
        }
        if (framesReadB < static_cast<size_t>(numFrames)) {
            std::fill(tempBufferB.begin() + framesReadB * 2, tempBufferB.end(), 0.0f);
        }
    }

    // Process frames
    size_t currentFrame = crossfadeCurrentFrame_.load(std::memory_order_relaxed);
    size_t totalFrames = crossfadeTotalFrames_.load(std::memory_order_acquire);

    for (int32_t i = 0; i < numFrames; ++i) {
        const float inAL = tempBufferA[i * 2];
        const float inAR = tempBufferA[i * 2 + 1];
        const float inBL = tempBufferB[i * 2];
        const float inBR = tempBufferB[i * 2 + 1];
        float outL = 0.0f, outR = 0.0f;

        if (currentState == CrossfadeState::Idle) {
            // Single deck - no crossfade
            if (activeDeck == DeckId::A) {
                processSingleFrame(outL, outR, inAL, inAR);
            } else {
                processSingleFrame(outL, outR, inBL, inBR);
            }
        } else {
            // Crossfade in progress
            float progress = (totalFrames > 0) ? static_cast<float>(currentFrame) / static_cast<float>(totalFrames) : 1.0f;
            progress = std::clamp(progress, 0.0f, 1.0f);
            crossfadeProgress_.store(progress, std::memory_order_relaxed);

            // Update automix filter frequency if enabled
            if (automixEqEnabled_.load(std::memory_order_relaxed)) {
                float targetFreq = automixFreqTarget_.load(std::memory_order_relaxed);
                float smoothedFreq = automixFreqSmoother_.next();
                if (std::abs(smoothedFreq - targetFreq) < 1.0f) {
                    smoothedFreq = targetFreq;
                }
                automixFilter_.setFrequency(sampleRate_, smoothedFreq);
            }

            processCrossfadeFrame(outL, outR, inAL, inAR, inBL, inBR, progress);

            currentFrame++;

            // Check if crossfade complete
            if (currentFrame >= totalFrames) {
                // Crossfade done - switch active deck
                DeckId next = nextDeck_.load(std::memory_order_acquire);
                activeDeck_.store(next, std::memory_order_release);
                state_.store(CrossfadeState::Idle, std::memory_order_release);
                crossfadeCurrentFrame_.store(0, std::memory_order_relaxed);

                // Reset automix filter for next transition
                if (automixEqEnabled_.load(std::memory_order_relaxed)) {
                    automixFilter_.setParams(sampleRate_, 20.0f, 0.707f);
                    automixFreqSmoother_.reset(20.0f);
                    automixFreqTarget_.store(20.0f, std::memory_order_relaxed);
                    automixEqEnabled_.store(false, std::memory_order_relaxed);
                }

                LOGD("Crossfade complete, active deck switched to %d", static_cast<int>(next));
            } else {
                crossfadeCurrentFrame_.store(currentFrame, std::memory_order_relaxed);
            }
        }

        // Write output (stereo interleaved)
        audioData[i * 2] = outL;
        audioData[i * 2 + 1] = outR;
    }
}