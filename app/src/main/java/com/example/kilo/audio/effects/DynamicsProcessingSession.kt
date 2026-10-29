package com.example.kilo.audio.effects

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import androidx.annotation.RequiresApi
import com.example.kilo.audio.dsp.EQ_FREQUENCIES_HZ
import com.example.kilo.audio.dsp.SmartLimiter

/** Android 9+ session effect adapter. Isolated so older Android versions can load the app safely. */
@RequiresApi(Build.VERSION_CODES.P)
class DynamicsProcessingSession(sessionId: Int) {
    private val limiterSettings = SmartLimiter().configuration(enabled = true)
    private val effect: DynamicsProcessing

    init {
        val equalizer = DynamicsProcessing.Eq(true, true, EQ_FREQUENCIES_HZ.size)
        EQ_FREQUENCIES_HZ.forEachIndexed { index, frequency ->
            equalizer.setBand(index, DynamicsProcessing.EqBand(true, frequency, 0f))
        }
        val limiter = DynamicsProcessing.Limiter(
            true,
            limiterSettings.enabled,
            0,
            limiterSettings.attackMs,
            limiterSettings.releaseMs,
            limiterSettings.ratio,
            limiterSettings.thresholdDb,
            limiterSettings.postGainDb
        )
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
            2,
            true, EQ_FREQUENCIES_HZ.size,
            false, 0,
            false, 0,
            true
        )
            .setPreEqAllChannelsTo(equalizer)
            .setLimiterAllChannelsTo(limiter)
            .build()
        effect = DynamicsProcessing(sessionId, DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, config).apply { enabled = true }
    }

    fun apply(gainsDb: List<Float>, inputGainDb: Float) {
        EQ_FREQUENCIES_HZ.forEachIndexed { index, frequency ->
            val gain = gainsDb.getOrElse(index) { 0f }.coerceIn(-3f, 2.7f)
            effect.setPreEqBandAllChannelsTo(index, DynamicsProcessing.EqBand(true, frequency, gain))
        }
        effect.setInputGainAllChannelsTo(inputGainDb.coerceIn(0f, 5.5f))
        effect.enabled = true
    }

    fun release() = effect.release()
}
