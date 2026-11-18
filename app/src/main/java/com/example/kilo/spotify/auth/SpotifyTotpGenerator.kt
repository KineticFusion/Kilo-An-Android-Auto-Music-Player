package com.example.kilo.spotify.auth

import android.util.Base64
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SpotifyTotpGenerator {

    private const val TOTP_ALGORITHM = "HmacSHA1"
    private const val TOTP_DIGITS = 6
    private const val TOTP_PERIOD_SECONDS = 30

    fun generateTotp(secretBase32: String, timestamp: Long = System.currentTimeMillis() / 1000): String {
        val timeInterval = timestamp / TOTP_PERIOD_SECONDS
        val hmac = hmacSha1(base32Decode(secretBase32), longToBytes(timeInterval))
        return truncateAndFormat(hmac)
    }

    fun generateTotpWithOffset(secretBase32: String, offsetSeconds: Int = 0): String {
        val timestamp = (System.currentTimeMillis() / 1000) + offsetSeconds
        return generateTotp(secretBase32, timestamp)
    }

    private fun base32Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val inputUpper = input.uppercase().replace("=", "").replace(" ", "")
        val bits = StringBuilder()

        for (char in inputUpper) {
            val index = alphabet.indexOf(char)
            if (index >= 0) {
                bits.append(index.toString(2).padStart(5, '0'))
            }
        }

        val byteArray = mutableListOf<Byte>()
        for (i in 0 until bits.length step 8) {
            if (i + 8 <= bits.length) {
                val byteStr = bits.substring(i, i + 8)
                byteArray.add(byteStr.toInt(2).toByte())
            }
        }
        return byteArray.toByteArray()
    }

    private fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        try {
            val secretKey = SecretKeySpec(key, TOTP_ALGORITHM)
            val mac = Mac.getInstance(TOTP_ALGORITHM)
            mac.init(secretKey)
            return mac.doFinal(data)
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException("HmacSHA1 algorithm not available", e)
        } catch (e: InvalidKeyException) {
            throw RuntimeException("Invalid key for HmacSHA1", e)
        }
    }

    private fun longToBytes(value: Long): ByteArray {
        return ByteArray(8) { index ->
            (value shr (56 - index * 8)).toByte()
        }
    }

    private fun truncateAndFormat(hmac: ByteArray): String {
        val offset = (hmac[hmac.lastIndex].toInt() and 0x0F)
        val binary = ((hmac[offset].toInt() and 0x7F) shl 24) or
            ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
            ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
            (hmac[offset + 3].toInt() and 0xFF)

        val otp = binary % 1000000
        return "%06d".format(otp)
    }

    fun generateTotpForWebPlayer(clientId: String, timestamp: Long = System.currentTimeMillis() / 1000): String {
        // Spotify Web Player uses a specific TOTP derivation
        // This is a placeholder - actual implementation depends on Spotify's current scheme
        val secret = deriveSecretFromClientId(clientId)
        return generateTotp(secret, timestamp)
    }

    internal fun deriveSecretFromClientId(clientId: String): String {
        // Placeholder for actual Spotify Web Player TOTP secret derivation
        // In practice, this would involve a more complex derivation
        return clientId.padEnd(32, 'A').substring(0, 32).uppercase()
    }
}

class TotpAuthenticator(
    private val secretProvider: () -> String,
    private val clockDriftTolerance: Int = 1 // Allow ±1 period drift
) {
    fun generateCurrent(): String = SpotifyTotpGenerator.generateTotp(secretProvider())

    fun generateWithDrift(): List<String> {
        return (-clockDriftTolerance..clockDriftTolerance).map { offset ->
            SpotifyTotpGenerator.generateTotpWithOffset(secretProvider(), offset * 30)
        }
    }

    fun verify(code: String): Boolean {
        return generateWithDrift().contains(code)
    }
}