package com.gino.mywallet.data.bot

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Drive API v3（完整 drive 範圍）。
 * 不能用 drive.file：Bot 寫進 inbox 的檔不是本 App 建立的，列資料夾會漏掉。
 */
class GoogleDriveClient(
    private val prefs: BotSyncPrefs
) {
    data class DriveFile(val id: String, val name: String, val mimeType: String)

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val ABOUT = "https://www.googleapis.com/drive/v3/about?fields=user"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val TIMEOUT_MS = 30_000
    }

    fun ensureAccessToken(): String {
        val now = System.currentTimeMillis()
        if (prefs.accessToken.isNotBlank() && prefs.accessExpiresAt > now + 60_000L) {
            return prefs.accessToken
        }
        val refresh = prefs.refreshToken
        require(refresh.isNotBlank()) { "尚未登入 Google Drive" }
        val bundle = GoogleDriveAuth.refresh(refresh)
        prefs.accessToken = bundle.accessToken
        prefs.accessExpiresAt = now + bundle.expiresInSec * 1000L
        if (!bundle.refreshToken.isNullOrBlank()) {
            prefs.refreshToken = bundle.refreshToken
        }
        return prefs.accessToken
    }

    fun fetchAccountEmail(): String {
        val token = ensureAccessToken()
        val text = httpGet(ABOUT, token)
        val email = JsonParser.parseString(text).asJsonObject
            .getAsJsonObject("user")
            ?.get("emailAddress")
            ?.asString
            .orEmpty()
        prefs.accountEmail = email
        return email
    }

    /** 建立或沿用根資料夾 MyWallet-Bot，並確保 inbox/outbox/drafts */
    fun ensureBotFolders(): String {
        val rootId = prefs.rootFolderId.ifBlank {
            findChildFolder("root", BotSyncPrefs.FOLDER_NAME)?.id
                ?: createFolder(BotSyncPrefs.FOLDER_NAME, parentId = "root")
        }
        prefs.rootFolderId = rootId
        prefs.rootFolderName = BotSyncPrefs.FOLDER_NAME
        prefs.inboxFolderId = ensureSubfolder(rootId, "inbox", prefs.inboxFolderId)
        prefs.outboxFolderId = ensureSubfolder(rootId, "outbox", prefs.outboxFolderId)
        prefs.draftsFolderId = ensureSubfolder(rootId, "drafts", prefs.draftsFolderId)
        return rootId
    }

    private fun ensureSubfolder(parentId: String, name: String, cachedId: String): String {
        if (cachedId.isNotBlank()) {
            // 驗證仍存在
            try {
                getFile(cachedId)
                return cachedId
            } catch (_: Exception) {
                // recreate
            }
        }
        return findChildFolder(parentId, name)?.id ?: createFolder(name, parentId)
    }

    fun listJsonFiles(folderId: String): List<DriveFile> {
        val q = "'$folderId' in parents and trashed=false and mimeType!='$FOLDER_MIME'"
        val files = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("$API/files?spaces=drive&pageSize=100")
                append("&fields=nextPageToken,files(id,name,mimeType)")
                append("&q=").append(enc(q))
                if (pageToken != null) append("&pageToken=").append(enc(pageToken!!))
            }
            val obj = JsonParser.parseString(httpGet(url, ensureAccessToken())).asJsonObject
            obj.getAsJsonArray("files")?.forEach { el ->
                val o = el.asJsonObject
                val name = o.get("name")?.asString.orEmpty()
                if (name.endsWith(".json", ignoreCase = true)) {
                    files += DriveFile(
                        id = o.get("id").asString,
                        name = name,
                        mimeType = o.get("mimeType")?.asString.orEmpty()
                    )
                }
            }
            pageToken = obj.get("nextPageToken")?.takeIf { !it.isJsonNull }?.asString
        } while (pageToken != null)
        return files.sortedBy { it.name }
    }

    fun downloadText(fileId: String): String {
        val url = "$API/files/$fileId?alt=media"
        return httpGet(url, ensureAccessToken())
    }

    fun upsertTextFile(parentId: String, name: String, content: String) {
        val existing = findChildFile(parentId, name)
        if (existing != null) {
            uploadMedia(existing.id, content)
        } else {
            val meta = JsonObject().apply {
                addProperty("name", name)
                add("parents", com.google.gson.JsonArray().also { it.add(parentId) })
            }
            createWithMedia(meta.toString(), content)
        }
    }

    fun deleteFile(fileId: String) {
        val conn = (URL("$API/files/$fileId").openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer ${ensureAccessToken()}")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299 && code != 204) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                error("刪除失敗 HTTP $code：$err")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun getFile(fileId: String): DriveFile {
        val text = httpGet("$API/files/$fileId?fields=id,name,mimeType", ensureAccessToken())
        val o = JsonParser.parseString(text).asJsonObject
        return DriveFile(o.get("id").asString, o.get("name").asString, o.get("mimeType").asString)
    }

    private fun findChildFolder(parentId: String, name: String): DriveFile? {
        val q = "'$parentId' in parents and trashed=false and name='${escapeQuery(name)}' and mimeType='$FOLDER_MIME'"
        return queryOne(q)
    }

    private fun findChildFile(parentId: String, name: String): DriveFile? {
        val q = "'$parentId' in parents and trashed=false and name='${escapeQuery(name)}' and mimeType!='$FOLDER_MIME'"
        return queryOne(q)
    }

    private fun queryOne(q: String): DriveFile? {
        val url = "$API/files?pageSize=1&fields=files(id,name,mimeType)&q=${enc(q)}"
        val arr = JsonParser.parseString(httpGet(url, ensureAccessToken()))
            .asJsonObject.getAsJsonArray("files") ?: return null
        if (arr.size() == 0) return null
        val o = arr[0].asJsonObject
        return DriveFile(o.get("id").asString, o.get("name").asString, o.get("mimeType").asString)
    }

    private fun createFolder(name: String, parentId: String): String {
        val meta = JsonObject().apply {
            addProperty("name", name)
            addProperty("mimeType", FOLDER_MIME)
            add("parents", com.google.gson.JsonArray().also { it.add(parentId) })
        }
        val text = httpJson("POST", "$API/files?fields=id", meta.toString(), ensureAccessToken())
        return JsonParser.parseString(text).asJsonObject.get("id").asString
    }

    private fun createWithMedia(metadataJson: String, content: String) {
        val boundary = "mywallet_" + System.currentTimeMillis()
        val body = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadataJson).append("\r\n")
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(content).append("\r\n")
            append("--$boundary--\r\n")
        }.toByteArray(Charsets.UTF_8)
        val conn = (URL("$UPLOAD/files?uploadType=multipart&fields=id")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${ensureAccessToken()}")
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) error("上傳建立失敗 HTTP $code：$text")
        } finally {
            conn.disconnect()
        }
    }

    private fun uploadMedia(fileId: String, content: String) {
        val conn = (URL("$UPLOAD/files/$fileId?uploadType=media")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "PATCH"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${ensureAccessToken()}")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        try {
            conn.outputStream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                error("上傳更新失敗 HTTP $code：$err")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String, accessToken: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) error("Drive GET 失敗 HTTP $code：$text")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun httpJson(method: String, url: String, json: String, accessToken: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        try {
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) error("Drive $method 失敗 HTTP $code：$text")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())

    private fun escapeQuery(name: String): String =
        name.replace("\\", "\\\\").replace("'", "\\'")
}
