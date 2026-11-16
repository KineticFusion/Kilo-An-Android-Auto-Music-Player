#pragma once

#include <cmath>
#include <cstddef>
#include <numbers>

/**
 * 2-pole Biquad filter (Direct Form II Transposed) for real-time audio.
 * Used for Automix EQ sweep (high-pass filter 20Hz -> 350Hz).
 */
class BiquadFilter {
public:
    enum class Type { LowPass, HighPass, BandPass, Notch, Peak, LowShelf, HighShelf };

    BiquadFilter() = default;
    explicit BiquadFilter(Type type) : type_(type) { reset(); }

    void setType(Type type) { type_ = type; }

    /**
     * Configure filter coefficients.
     * @param sampleRate Sample rate in Hz (e.g., 48000)
     * @param frequency Center/corner frequency in Hz
     * @param q Quality factor (resonance), typically 0.707 for Butterworth
     * @param gainDb Gain in dB (for shelf/peak filters)
     */
    void setParams(float sampleRate, float frequency, float q = 0.707f, float gainDb = 0.0f) {
        constexpr float kTwoPi = 2.0f * std::numbers::pi_v<float>;
        const float w0 = kTwoPi * frequency / sampleRate;
        const float cosW0 = std::cos(w0);
        const float sinW0 = std::sin(w0);
        const float alpha = sinW0 / (2.0f * q);
        const float A = std::pow(10.0f, gainDb / 40.0f);

        float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a0 = 1.0f, a1 = 0.0f, a2 = 0.0f;

        switch (type_) {
            case Type::LowPass:
                b0 = (1.0f - cosW0) * 0.5f;
                b1 = 1.0f - cosW0;
                b2 = (1.0f - cosW0) * 0.5f;
                a0 = 1.0f + alpha;
                a1 = -2.0f * cosW0;
                a2 = 1.0f - alpha;
                break;
            case Type::HighPass:
                b0 = (1.0f + cosW0) * 0.5f;
                b1 = -(1.0f + cosW0);
                b2 = (1.0f + cosW0) * 0.5f;
                a0 = 1.0f + alpha;
                a1 = -2.0f * cosW0;
                a2 = 1.0f - alpha;
                break;
            case Type::BandPass:
                b0 = alpha;
                b1 = 0.0f;
                b2 = -alpha;
                a0 = 1.0f + alpha;
                a1 = -2.0f * cosW0;
                a2 = 1.0f - alpha;
                break;
            case Type::Notch:
                b0 = 1.0f;
                b1 = -2.0f * cosW0;
                b2 = 1.0f;
                a0 = 1.0f + alpha;
                a1 = -2.0f * cosW0;
                a2 = 1.0f - alpha;
                break;
            case Type::Peak:
                b0 = 1.0f + alpha * A;
                b1 = -2.0f * cosW0;
                b2 = 1.0f - alpha * A;
                a0 = 1.0f + alpha / A;
                a1 = -2.0f * cosW0;
                a2 = 1.0f - alpha / A;
                break;
            case Type::LowShelf:
                b0 = A * ((A + 1.0f) - (A - 1.0f) * cosW0 + 2.0f * std::sqrt(A) * alpha);
                b1 = 2.0f * A * ((A - 1.0f) - (A + 1.0f) * cosW0);
                b2 = A * ((A + 1.0f) - (A - 1.0f) * cosW0 - 2.0f * std::sqrt(A) * alpha);
                a0 = (A + 1.0f) + (A - 1.0f) * cosW0 + 2.0f * std::sqrt(A) * alpha;
                a1 = -2.0f * ((A - 1.0f) + (A + 1.0f) * cosW0);
                a2 = (A + 1.0f) + (A - 1.0f) * cosW0 - 2.0f * std::sqrt(A) * alpha;
                break;
            case Type::HighShelf:
                b0 = A * ((A + 1.0f) + (A - 1.0f) * cosW0 + 2.0f * std::sqrt(A) * alpha);
                b1 = -2.0f * A * ((A - 1.0f) + (A + 1.0f) * cosW0);
                b2 = A * ((A + 1.0f) + (A - 1.0f) * cosW0 - 2.0f * std::sqrt(A) * alpha);
                a0 = (A + 1.0f) - (A - 1.0f) * cosW0 + 2.0f * std::sqrt(A) * alpha;
                a1 = 2.0f * ((A - 1.0f) - (A + 1.0f) * cosW0);
                a2 = (A + 1.0f) - (A - 1.0f) * cosW0 - 2.0f * std::sqrt(A) * alpha;
                break;
        }

        // Normalize
        b0_ = b0 / a0;
        b1_ = b1 / a0;
        b2_ = b2 / a0;
        a1_ = a1 / a0;
        a2_ = a2 / a0;
    }

    /**
     * Process a single sample (stereo - processes both channels identically).
     * Inline for zero overhead in hot path.
     */
    inline void process(float& left, float& right) {
        // Left channel
        const float outL = b0_ * left + z1L_;
        z1L_ = b1_ * left - a1_ * outL + z2L_;
        z2L_ = b2_ * left - a2_ * outL;
        left = outL;

        // Right channel
        const float outR = b0_ * right + z1R_;
        z1R_ = b1_ * right - a1_ * outR + z2R_;
        z2R_ = b2_ * right - a2_ * outR;
        right = outR;
    }

    /**
     * Process mono sample.
     */
    inline float process(float input) {
        const float out = b0_ * input + z1L_;
        z1L_ = b1_ * input - a1_ * out + z2L_;
        z2L_ = b2_ * input - a2_ * out;
        return out;
    }

    /**
     * Update frequency in real-time (for automix sweep).
     * Recalculates coefficients on the fly - safe for audio thread if called infrequently.
     */
    inline void setFrequency(float sampleRate, float frequency, float q = 0.707f) {
        setParams(sampleRate, frequency, q);
    }

    void reset() {
        z1L_ = z2L_ = z1R_ = z2R_ = 0.0f;
    }

private:
    Type type_ = Type::HighPass;

    // Normalized coefficients
    float b0_ = 1.0f, b1_ = 0.0f, b2_ = 0.0f;
    float a1_ = 0.0f, a2_ = 0.0f;

    // Direct Form II Transposed state variables (per channel)
    float z1L_ = 0.0f, z2L_ = 0.0f;
    float z1R_ = 0.0f, z2R_ = 0.0f;
};

/**
 * Smoothed parameter for automix frequency sweep.
 * Provides smooth transitions without zipper noise.
 */
class SmoothedFloat {
public:
    explicit SmoothedFloat(float initialValue = 0.0f, float smoothingTimeMs = 50.0f, float sampleRate = 48000.0f)
        : value_(initialValue), target_(initialValue) {
        setSmoothingTime(smoothingTimeMs, sampleRate);
    }

    void setTarget(float target) { target_ = target; }
    void setSmoothingTime(float timeMs, float sampleRate) {
        const float tau = timeMs * 0.001f;
        coeff_ = 1.0f - std::exp(-1.0f / (tau * sampleRate));
    }

    inline float next() {
        value_ += (target_ - value_) * coeff_;
        return value_;
    }

    float getValue() const { return value_; }
    float getTarget() const { return target_; }

    void reset(float value) {
        value_ = target_ = value;
    }

private:
    float value_;
    float target_;
    float coeff_ = 0.0f;
};