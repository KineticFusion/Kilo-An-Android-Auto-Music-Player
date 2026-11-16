#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

/**
 * Lock-free Single-Producer Single-Consumer (SPSC) Ring Buffer for float32 audio samples.
 * Designed for real-time audio with zero allocations in the hot path.
 * Supports stereo interleaved (L,R,L,R,...) format.
 */
class SpscRingBuffer {
public:
    explicit SpscRingBuffer(size_t capacityFrames) {
        // Round up to power of 2 for fast modulo via bitmask
        size_t capacity = 1;
        while (capacity < capacityFrames * 2) { // *2 for stereo interleaved
            capacity <<= 1;
        }
        buffer_.resize(capacity);
        mask_ = capacity - 1;
        capacityFrames_ = capacityFrames;
    }

    // Non-copyable, non-movable (due to atomic members)
    SpscRingBuffer(const SpscRingBuffer&) = delete;
    SpscRingBuffer& operator=(const SpscRingBuffer&) = delete;
    SpscRingBuffer(SpscRingBuffer&&) = delete;
    SpscRingBuffer& operator=(SpscRingBuffer&&) = delete;

    /**
     * Push interleaved stereo frames (float32) from producer thread (AudioProcessor).
     * Returns number of frames actually written (may be less than requested if full).
     */
    size_t push(const float* data, size_t numFrames) {
        const size_t numSamples = numFrames * 2; // stereo interleaved
        const size_t writePos = writeIndex_.load(std::memory_order_relaxed);
        const size_t readPos = readIndex_.load(std::memory_order_acquire);

        const size_t available = (readPos > writePos)
            ? (readPos - writePos - 1)
            : (mask_ + 1 + readPos - writePos - 1);

        const size_t framesAvailable = available / 2;
        const size_t framesToWrite = (numFrames < framesAvailable) ? numFrames : framesAvailable;
        const size_t samplesToWrite = framesToWrite * 2;

        if (samplesToWrite == 0) return 0;

        const size_t writeIdx = writePos & mask_;
        const size_t firstChunk = std::min(samplesToWrite, buffer_.size() - writeIdx);

        // Copy first chunk
        std::copy(data, data + firstChunk, buffer_.begin() + writeIdx);

        // Copy wrap-around chunk if needed
        if (firstChunk < samplesToWrite) {
            std::copy(data + firstChunk, data + samplesToWrite, buffer_.begin());
        }

        writeIndex_.store((writePos + samplesToWrite) & mask_, std::memory_order_release);
        return framesToWrite;
    }

    /**
     * Pop interleaved stereo frames to consumer thread (audio render callback).
     * Returns number of frames actually read (may be less than requested if empty).
     */
    size_t pop(float* data, size_t numFrames) {
        const size_t numSamples = numFrames * 2;
        const size_t readPos = readIndex_.load(std::memory_order_relaxed);
        const size_t writePos = writeIndex_.load(std::memory_order_acquire);

        const size_t available = (writePos >= readPos)
            ? (writePos - readPos)
            : (mask_ + 1 + writePos - readPos);

        const size_t framesAvailable = available / 2;
        const size_t framesToRead = (numFrames < framesAvailable) ? numFrames : framesAvailable;
        const size_t samplesToRead = framesToRead * 2;

        if (samplesToRead == 0) return 0;

        const size_t readIdx = readPos & mask_;
        const size_t firstChunk = std::min(samplesToRead, buffer_.size() - readIdx);

        // Copy first chunk
        std::copy(buffer_.begin() + readIdx, buffer_.begin() + readIdx + firstChunk, data);

        // Copy wrap-around chunk if needed
        if (firstChunk < samplesToRead) {
            std::copy(buffer_.begin(), buffer_.begin() + (samplesToRead - firstChunk), data + firstChunk);
        }

        readIndex_.store((readPos + samplesToRead) & mask_, std::memory_order_release);
        return framesToRead;
    }

    /**
     * Peek at available frames without consuming (for monitoring/backpressure).
     */
    size_t availableFrames() const {
        const size_t writePos = writeIndex_.load(std::memory_order_acquire);
        const size_t readPos = readIndex_.load(std::memory_order_acquire);
        const size_t available = (writePos >= readPos)
            ? (writePos - readPos)
            : (mask_ + 1 + writePos - readPos);
        return available / 2;
    }

    /**
     * Get capacity in frames.
     */
    size_t capacityFrames() const { return capacityFrames_; }

    /**
     * Get fill ratio [0.0, 1.0] for backpressure decisions.
     */
    float fillRatio() const {
        return static_cast<float>(availableFrames()) / static_cast<float>(capacityFrames_);
    }

    /**
     * Clear the buffer (call from producer thread only).
     */
    void clear() {
        writeIndex_.store(0, std::memory_order_relaxed);
        readIndex_.store(0, std::memory_order_relaxed);
    }

private:
    std::vector<float> buffer_;
    size_t mask_ = 0;
    size_t capacityFrames_ = 0;

    // Cache-line padded atomics to avoid false sharing
    alignas(64) std::atomic<size_t> writeIndex_{0};
    alignas(64) std::atomic<size_t> readIndex_{0};
};