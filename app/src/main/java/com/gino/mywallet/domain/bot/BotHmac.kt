package com.gino.mywallet.domain.bot

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object BotHmac {
    private const val ALG = "HmacSHA256"

    fun signHex(secret: String, payloadUtf8: String): String {
        val mac = Mac.getInstance(ALG)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALG))
        val raw = mac.doFinal(payloadUtf8.toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { b -> "%02x".format(b) }
    }

    fun verify(secret: String, payloadUtf8: String, sigHex: String): Boolean {
        if (secret.isBlank() || sigHex.isBlank()) return false
        val expected = signHex(secret, payloadUtf8)
        return constantTimeEquals(expected, sigHex.trim().lowercase())
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}
