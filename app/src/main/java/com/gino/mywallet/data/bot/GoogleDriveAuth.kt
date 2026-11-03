package com.gino.mywallet.data.bot

import android.net.Uri
import android.util.Base64
import com.gino.mywallet.BuildConfig
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

object GoogleDriveAuth {
    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val TIMEOUT_MS = 20_000

    data class Pkce(val verifier: String, val challenge: String)

    data class TokenBundle(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSec: Long
    )

    fun clientIdConfigured(): Boolean = BuildConfig.DRIVE_OAUTH_CLIENT_ID.isNotBlank()

    fun newPkce(): Pkce {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val verifier = base64Url(bytes)
        val dig = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pkce(verifier = verifier, challenge = base64Url(dig))
    }

    fun buildAuthorizeUri(pkce: Pkce, redirectUri: String): Uri {
        val clientId = BuildConfig.DRIVE_OAUTH_CLIENT_ID
        require(clientId.isNotBlank()) {
            "尚未設定 Drive OAuth Client ID（local.properties 的 drive.oauth.clientId）"
        }
        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", BotSyncPrefs.SCOPE)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .appendQueryParameter("code_challenge", pkce.challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
    }

    fun exchangeCode(code: String, codeVerifier: String, redirectUri: String): TokenBundle {
        val body = buildString {
            append("code=").append(enc(code))
            append("&client_id=").append(enc(BuildConfig.DRIVE_OAUTH_CLIENT_ID))
            val secret = BuildConfig.DRIVE_OAUTH_CLIENT_SECRET
            if (secret.isNotBlank()) {
                append("&client_secret=").append(enc(secret))
            }
            append("&redirect_uri=").append(enc(redirectUri))
            append("&grant_type=authorization_code")
            append("&code_verifier=").append(enc(codeVerifier))
        }
        return postToken(body)
    }

    fun refresh(refreshToken: String): TokenBundle {
        val body = buildString {
            append("client_id=").append(enc(BuildConfig.DRIVE_OAUTH_CLIENT_ID))
            val secret = BuildConfig.DRIVE_OAUTH_CLIENT_SECRET
            if (secret.isNotBlank()) {
                append("&client_secret=").append(enc(secret))
            }
            append("&refresh_token=").append(enc(refreshToken))
            append("&grant_type=refresh_token")
        }
        return postToken(body)
    }

    private fun postToken(formBody: String): TokenBundle {
        val conn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                error(formatTokenError(code, text))
            }
            val obj = JsonParser.parseString(text).asJsonObject
            val access = obj.get("access_token")?.asString
                ?: error("回應缺少 access_token")
            val refresh = obj.get("refresh_token")?.takeIf { !it.isJsonNull }?.asString
            val exp = obj.get("expires_in")?.asLong ?: 3600L
            return TokenBundle(access, refresh, exp)
        } finally {
            conn.disconnect()
        }
    }

    private fun formatTokenError(httpCode: Int, body: String): String {
        val parsed = runCatching {
            val o = JsonParser.parseString(body).asJsonObject
            val err = o.get("error")?.asString
            val desc = o.get("error_description")?.asString
            listOfNotNull(err, desc).joinToString("：")
        }.getOrNull()
        val detail = parsed?.ifBlank { null } ?: body.take(240)
        return "換取權杖失敗 HTTP $httpCode：$detail"
    }

    private fun enc(s: String): String =
        URLEncoder.encode(s, Charsets.UTF_8.name())

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
