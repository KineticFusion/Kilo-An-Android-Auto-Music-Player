#pragma once

#include <atomic>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <numbers>

#include "ring_buffer.h"
#include "biquad_filter.h"

enum class DeckId : uint8_t { A = 0, B = 1 };

enum class CrossfadeState : uint8_t {
    Idle = 0,          // No crossfade active, single deck playing
    FadingOut = 1,     // Current deck fading out, next deck fading in
    FadingIn = 2,      // Crossfade complete, new deck active
    AutomixTransition = 3 // Automix with EQ sweep active
};

class NativeMixer {
public:
    NativeMixer() = default;
    ~NativeMixer() { shutdown(); }

    // Non-copyable
    NativeMixer(const NativeMixer&) = delete;
    NativeMixer& operator=(const NativeMixer&) = delete;

    /**
     * Initialize the mixer with sample rate and channel count.
     * Must be called before any other methods.
     */
    bool initialize(int32_t sampleRate, int32_t channelCount);

    /**
     * Shutdown and release all resources.
     */
    void shutdown();

    /**
     * Push PCM data from Kotlin AudioProcessor (Deck A or B).
     * Called from Kotlin via JNI - producer thread.
     * @param deckId Deck A (0) or Deck B (1)
     * @param data Interleaved float32 stereo samples
     * @param numFrames Number of frames (not samples)
     * @return Number of frames actually written
     */
    size_t pushPcm(DeckId deckId, const float* data, size_t numFrames);

    /**
     * Start a crossfade from current deck to other deck.
     * @param durationFrames Crossfade duration in frames (at sample rate)
     * @param enableAutomixEq Whether to enable Automix EQ sweep (HPF 20Hz->350Hz on outgoing)
     */
    void startCrossfade(size_t durationFrames, bool enableAutomixEq);

    /**
     * Stop a deck (clear its buffer, mark as inactive).
     */
    void stopDeck(DeckId deckId);

    /**
     * Check if crossfade is in progress.
     */
    bool isCrossfading() const {
        return state_.load(std::memory_order_acquire) != CrossfadeState::Idle;
    }

    /**
     * Get current fill ratio for backpressure monitoring.
     */
    float getFillRatio(DeckId deckId) const {
        return (deckId == DeckId::A) ? bufferA_.fillRatio() : bufferB_.fillRatio();
    }

    /**
     * Get available frames in deck buffer.
     */
    size_t getAvailableFrames(DeckId deckId) const {
        return (deckId == DeckId::A) ? bufferA_.availableFrames() : bufferB_.availableFrames();
    }

    // Called by OboeAudioStream from audio render thread
    void onAudioReady(float* audioData, int32_t numFrames);

    // Getters for state
    int32_t getSampleRate() const { return sampleRate_; }
    int32_t getChannelCount() const { return channelCount_; }

private:
    // Audio parameters
    int32_t sampleRate_ = 48000;
    int32_t channelCount_ = 2;

    // Ring buffers for each deck (stereo interleaved float32)
    SpscRingBuffer bufferA_{8192};  // ~170ms at 48kHz
    SpscRingBuffer bufferB_{8192};

    // Crossfade state (atomic for thread-safe access from audio thread)
    std::atomic<CrossfadeState> state_{CrossfadeState::Idle};
    std::atomic<DeckId> activeDeck_{DeckId::A};
    std::atomic<DeckId> nextDeck_{DeckId::B};

    // Crossfade progress (0.0 to 1.0) - updated per frame in audio thread
    std::atomic<float> crossfadeProgress_{0.0f};
    std::atomic<size_t> crossfadeTotalFrames_{0};
    std::atomic<size_t> crossfadeCurrentFrame_{0};
    std::atomic<bool> automixEqEnabled_{false};

    // Automix EQ: High-pass filter sweep on outgoing deck
    BiquadFilter automixFilter_{BiquadFilter::Type::HighPass};
    SmoothedFloat automixFreqSmoother_{20.0f, 10.0f, 48000.0f}; // 10ms smoothing
    std::atomic<float> automixFreqTarget_{20.0f};

    // Oboe output stream (raw pointer to avoid circular dependency)
    class OboeAudioStream* oboeStream_ = nullptr;
    std::mutex streamMutex_;

    // Soft clipper (tanh) for anti-clipping
    inline float softClip(float x) const {
        // Fast tanh approximation: x * (27 + x^2) / (27 + 9*x^2)
        // Or use std::tanh for accuracy
        return std::tanh(x);
    }

    // Process one frame of crossfade with equal-power curves
    inline void processCrossfadeFrame(float& outL, float& outR,
                                      float inAL, float inAR,
                                      float inBL, float inBR,
                                      float progress) {
        // Equal-power crossfade: cos(t * pi/2) and sin(t * pi/2)
        constexpr float kHalfPi = std::numbers::pi_v<float> / 2.0f;
        const float t = progress;
        const float gainA = std::cos(t * kHalfPi);
        const float gainB = std::sin(t * kHalfPi);

        float mixedL = inAL * gainA + inBL * gainB;
        float mixedR = inAR * gainA + inBR * gainB;

        // Apply Automix EQ on outgoing deck if enabled
        if (automixEqEnabled_.load(std::memory_order_relaxed)) {
            const DeckId outgoingDeck = activeDeck_.load(std::memory_order_relaxed);
            // Apply HPF sweep to the outgoing deck's contribution
            float outL_outgoing = (outgoingDeck == DeckId::A) ? inAL : inBL;
            float outR_outgoing = (outgoingDeck == DeckId::A) ? inAR : inBR;

            // Process through filter (only affects outgoing portion)
            automixFilter_.process(outL_outgoing, outR_outgoing);

            // Re-mix with filtered outgoing
            if (outgoingDeck == DeckId::A) {
                mixedL = outL_outgoing * gainA + inBL * gainB;
                mixedR = outR_outgoing * gainA + inBR * gainB;
            } else {
                mixedL = inAL * gainA + outL_outgoing * gainB;
                mixedR = inAR * gainA + outR_outgoing * gainB;
            }
        }

        // Soft clip to prevent digital clipping
        outL = softClip(mixedL);
        outR = softClip(mixedR);
    }

    // Process single deck (no crossfade)
    inline void processSingleFrame(float& outL, float& outR, float inL, float inR) {
        outL = softClip(inL);
        outR = softClip(inR);
    }
};