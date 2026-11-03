package com.gino.mywallet.data.bot

import android.content.Context
import android.content.SharedPreferences

class BotSyncPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** HMAC 簽章用權杖（與 Grok Bot 共用） */
    var token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value).commit()
        }

    var refreshToken: String
        get() = prefs.getString(KEY_REFRESH, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_REFRESH, value).commit()
        }

    var accessToken: String
        get() = prefs.getString(KEY_ACCESS, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_ACCESS, value).commit()
        }

    /** access token 過期時間（epoch ms） */
    var accessExpiresAt: Long
        get() = prefs.getLong(KEY_ACCESS_EXP, 0L)
        set(value) {
            prefs.edit().putLong(KEY_ACCESS_EXP, value).commit()
        }

    var rootFolderId: String
        get() = prefs.getString(KEY_ROOT_ID, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_ROOT_ID, value).commit()
        }

    var rootFolderName: String
        get() = prefs.getString(KEY_ROOT_NAME, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_ROOT_NAME, value).commit()
        }

    var inboxFolderId: String
        get() = prefs.getString(KEY_INBOX_ID, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_INBOX_ID, value).commit()
        }

    var outboxFolderId: String
        get() = prefs.getString(KEY_OUTBOX_ID, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_OUTBOX_ID, value).commit()
        }

    var draftsFolderId: String
        get() = prefs.getString(KEY_DRAFTS_ID, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_DRAFTS_ID, value).commit()
        }

    var accountEmail: String
        get() = prefs.getString(KEY_EMAIL, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_EMAIL, value).commit()
        }

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNC, value).apply()
        }

    var lastSyncMessage: String
        get() = prefs.getString(KEY_LAST_MSG, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_LAST_MSG, value).apply()
        }

    /** 最近一次 OAuth 失敗原因（顯示在設定頁） */
    var lastAuthError: String
        get() = prefs.getString(KEY_AUTH_ERR, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_AUTH_ERR, value).commit()
        }

    /** OAuth 進行中的 PKCE verifier */
    var pendingCodeVerifier: String
        get() = prefs.getString(KEY_VERIFIER, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_VERIFIER, value).commit()
        }

    /** 瀏覽器已回傳、待前景換取的授權碼（華為常擋背景網路） */
    var pendingAuthCode: String
        get() = prefs.getString(KEY_PENDING_CODE, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_PENDING_CODE, value).commit()
        }

    var pendingRedirectUri: String
        get() = prefs.getString(KEY_PENDING_REDIRECT, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_PENDING_REDIRECT, value).commit()
        }

    val hasPendingAuthCode: Boolean
        get() = pendingAuthCode.isNotBlank() &&
            pendingCodeVerifier.isNotBlank() &&
            pendingRedirectUri.isNotBlank()

    fun savePendingAuth(code: String, verifier: String, redirectUri: String) {
        prefs.edit()
            .putString(KEY_PENDING_CODE, code)
            .putString(KEY_VERIFIER, verifier)
            .putString(KEY_PENDING_REDIRECT, redirectUri)
            .putString(KEY_AUTH_ERR, "")
            .commit()
    }

    fun clearPendingAuth() {
        prefs.edit()
            .remove(KEY_PENDING_CODE)
            .remove(KEY_VERIFIER)
            .remove(KEY_PENDING_REDIRECT)
            .commit()
    }

    /** 已取得的 OAuth scope 版本（低於 SCOPE_VERSION 須重登） */
    var scopeVersion: Int
        get() = prefs.getInt(KEY_SCOPE_VER, 0)
        set(value) {
            prefs.edit().putInt(KEY_SCOPE_VER, value).commit()
        }

    /** 有 refresh token 且權限版本足夠；資料夾可在同步時補建 */
    val isDriveAuthorized: Boolean
        get() = refreshToken.isNotBlank() && scopeVersion >= SCOPE_VERSION

    val isDriveLinked: Boolean
        get() = isDriveAuthorized

    /** 舊版 drive.file 授權作廢，強制重登完整 drive */
    fun invalidateStaleDriveScope(): Boolean {
        if (refreshToken.isBlank()) return false
        if (scopeVersion >= SCOPE_VERSION) return false
        prefs.edit()
            .remove(KEY_REFRESH)
            .remove(KEY_ACCESS)
            .remove(KEY_ACCESS_EXP)
            .remove(KEY_SCOPE_VER)
            .putString(
                KEY_AUTH_ERR,
                "Drive 權限已升級（需讀取 Bot 寫入的 inbox）。請重新「登入 Drive」並允許完整雲端硬碟存取"
            )
            .commit()
        return true
    }

    fun markScopeGranted() {
        scopeVersion = SCOPE_VERSION
    }

    fun driveStatusLine(): String = when {
        isDriveAuthorized && rootFolderId.isBlank() ->
            "已授權，資料夾未就緒（同步時會建立）"
        isDriveAuthorized && accountEmail.isNotBlank() ->
            "已連結：$FOLDER_NAME（$accountEmail）"
        isDriveAuthorized ->
            "已連結：$FOLDER_NAME"
        hasPendingAuthCode ->
            "授權碼已收到，請回到 App 完成連結…"
        lastAuthError.isNotBlank() ->
            "授權失敗：${lastAuthError.take(180)}"
        else -> "尚未授權 Google"
    }

    fun clearDrive() {
        prefs.edit()
            .remove(KEY_REFRESH)
            .remove(KEY_ACCESS)
            .remove(KEY_ACCESS_EXP)
            .remove(KEY_ROOT_ID)
            .remove(KEY_ROOT_NAME)
            .remove(KEY_INBOX_ID)
            .remove(KEY_OUTBOX_ID)
            .remove(KEY_DRAFTS_ID)
            .remove(KEY_EMAIL)
            .remove(KEY_VERIFIER)
            .remove(KEY_PENDING_CODE)
            .remove(KEY_PENDING_REDIRECT)
            .remove(KEY_AUTH_ERR)
            .remove(KEY_SCOPE_VER)
            .commit()
    }

    companion object {
        private const val PREFS = "bot_sync_v2"
        private const val KEY_TOKEN = "token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_ACCESS_EXP = "access_expires_at"
        private const val KEY_ROOT_ID = "root_folder_id"
        private const val KEY_ROOT_NAME = "root_folder_name"
        private const val KEY_INBOX_ID = "inbox_folder_id"
        private const val KEY_OUTBOX_ID = "outbox_folder_id"
        private const val KEY_DRAFTS_ID = "drafts_folder_id"
        private const val KEY_EMAIL = "account_email"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_LAST_MSG = "last_sync_msg"
        private const val KEY_AUTH_ERR = "last_auth_error"
        private const val KEY_VERIFIER = "pending_code_verifier"
        private const val KEY_PENDING_CODE = "pending_auth_code"
        private const val KEY_PENDING_REDIRECT = "pending_redirect_uri"
        private const val KEY_SCOPE_VER = "oauth_scope_version"

        const val FOLDER_NAME = "MyWallet-Bot"
        /**
         * 必須用完整 drive：Bot 寫入 inbox 的檔不是本 App 建立的，
         * drive.file 列得出資料夾卻看不到那些檔，會變成「inbox 有檔、outbox 無結果」。
         */
        const val SCOPE = "https://www.googleapis.com/auth/drive"
        /** 權限升級時遞增；低於此值的舊授權要重登 */
        const val SCOPE_VERSION = 2
    }
}
