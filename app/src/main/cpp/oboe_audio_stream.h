#pragma once

#include <oboe/Oboe.h>

// Forward declaration to break circular dependency
class NativeMixer;

/**
 * Oboe AudioStream callback for low-latency output.
 * Runs on Oboe's high-priority audio thread.
 */
class OboeAudioStream : public oboe::AudioStreamCallback {
public:
    explicit OboeAudioStream(NativeMixer& mixer) : mixer_(mixer) {}
    ~OboeAudioStream() override = default;

    // AudioStreamCallback implementation
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* audioStream, void* audioData, int32_t numFrames) override {
        float* floatData = static_cast<float*>(audioData);
        mixer_.onAudioReady(floatData, numFrames);
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream* audioStream, oboe::Result result) override {
        // Stream closed unexpectedly - could attempt restart here
        (void)audioStream;
        (void)result;
    }

    /**
     * Open the Oboe output stream.
     * @return oboe::Result
     */
    oboe::Result openStream(int32_t sampleRate, int32_t channelCount) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
               ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
               ->setSharingMode(oboe::SharingMode::Exclusive)
               ->setFormat(oboe::AudioFormat::Float)
               ->setChannelCount(channelCount)
               ->setSampleRate(sampleRate)
               ->setCallback(this)
               ->setUsage(oboe::Usage::Media)
               ->setContentType(oboe::ContentType::Music)
               ->setFramesPerCallback(0); // Let Oboe choose optimal buffer size

        return builder.openStream(stream_);
    }

    /**
     * Start the audio stream.
     */
    oboe::Result start() {
        if (stream_) {
            return stream_->requestStart();
        }
        return oboe::Result::ErrorNull;
    }

    /**
     * Stop and close the audio stream.
     */
    void close() {
        if (stream_) {
            stream_->requestStop();
            stream_->close();
            stream_.reset();
        }
    }

    bool isOpen() const { return stream_ != nullptr; }
    oboe::AudioStream* getStream() const { return stream_.get(); }

private:
    NativeMixer& mixer_;
    std::shared_ptr<oboe::AudioStream> stream_;
};