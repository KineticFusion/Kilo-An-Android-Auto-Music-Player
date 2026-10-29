package com.example.kilo.audio.engine

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import com.example.kilo.androidauto.performance.AndroidAutoPerformanceMode

enum class AudioOutputKind(val label: String) {
    ANDROID_AUTO("Android Auto"),
    CAR_BLUETOOTH("Bluetooth car audio"),
    BLUETOOTH_SPEAKER("Bluetooth speaker"),
    EARBUDS("Earbuds"),
    HEADPHONES("Headphones"),
    WIRED("Wired audio"),
    USB_DAC("USB audio"),
    BUILT_IN("Built-in speaker"),
    OTHER("Audio output")
}

data class AudioOutputRoute(
    val kind: AudioOutputKind = AudioOutputKind.OTHER,
    val deviceName: String = "",
    val deviceType: Int = AudioDeviceInfo.TYPE_UNKNOWN
)

/** Observes the active output without changing Android routing or volume. */
class OutputDeviceManager(context: Context, private val handler: Handler, private val onChanged: (AudioOutputRoute) -> Unit) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = publish()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = publish()
    }

    fun start() {
        handler.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioManager.registerAudioDeviceCallback(callback, handler)
            publish()
        }
    }

    fun current(): AudioOutputRoute {
        val androidAuto = runCatching { AndroidAutoPerformanceMode.enabled(appContext) }.getOrDefault(false)
        val device = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.isSink }
                .maxByOrNull(::priority)
        }.getOrNull()
        val name = device?.productName?.toString().orEmpty()
        val kind = when {
            androidAuto -> AudioOutputKind.ANDROID_AUTO
            device == null -> AudioOutputKind.OTHER
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && isCarName(name) -> AudioOutputKind.CAR_BLUETOOTH
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && isEarbudName(name) -> AudioOutputKind.EARBUDS
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioOutputKind.BLUETOOTH_SPEAKER
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioOutputKind.OTHER
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioOutputKind.WIRED
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE -> AudioOutputKind.USB_DAC
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> AudioOutputKind.BUILT_IN
            else -> AudioOutputKind.OTHER
        }
        return AudioOutputRoute(kind, name, device?.type ?: AudioDeviceInfo.TYPE_UNKNOWN)
    }

    fun close() {
        runCatching { audioManager.unregisterAudioDeviceCallback(callback) }
    }

    private fun publish() {
        val route = current()
        handler.post { onChanged(route) }
    }

    private fun priority(device: AudioDeviceInfo): Int = when (device.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_HDMI -> 3
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1
        else -> 0
    }

    private fun isCarName(value: String): Boolean = value.contains(Regex("car|auto|vehicle|hands.?free|kia|hyundai|toyota|honda|ford|bmw|audi|benz|volvo|mahindra", RegexOption.IGNORE_CASE))
    private fun isEarbudName(value: String): Boolean = value.contains(Regex("buds|airpod|earbud|tws|headphone|headset|beats|sony wh|jabra", RegexOption.IGNORE_CASE))
}
