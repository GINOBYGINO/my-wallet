package com.gino.mywallet.data.bot

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * 在 127.0.0.1 收 Google OAuth 回呼（桌面／原生應用官方做法）。
 * 會略過 favicon 等無關請求，直到收到 code 或 error。
 */
class OAuthLoopbackServer(
    private val awaitTimeoutMs: Int = 180_000
) {
    private var server: ServerSocket? = null

    sealed class Result {
        data class Code(val code: String) : Result()
        data class Error(val error: String) : Result()
    }

    /** @return 實際綁定的 redirect URI，例如 http://127.0.0.1:54321/ */
    fun start(): String {
        val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = ss
        return "http://127.0.0.1:${ss.localPort}/"
    }

    fun await(): Result {
        val ss = server ?: error("尚未 start")
        val deadline = System.currentTimeMillis() + awaitTimeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                val remain = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                ss.soTimeout = remain
                try {
                    ss.accept().use { socket ->
                        when (val hit = handle(socket)) {
                            null -> { /* favicon 等，繼續等 */ }
                            else -> return hit
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    break
                }
            }
            return Result.Error("登入逾時。請再按「登入 Drive」，授權後應出現「登入完成」頁面")
        } finally {
            stop()
        }
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
    }

    /** @return null 表示略過此連線 */
    private fun handle(socket: Socket): Result? {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return null
        val path = requestLine.split(' ').getOrNull(1).orEmpty()
        val query = path.substringAfter('?', "")
        val params = parseQuery(query)
        val code = params["code"]
        val error = params["error"]
        val outcome: Result? = when {
            !code.isNullOrBlank() -> Result.Code(code)
            !error.isNullOrBlank() -> Result.Error(error)
            else -> null
        }
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val html = if (outcome is Result.Code) {
            """
            <!DOCTYPE html><html><head><meta charset="utf-8"><title>我的錢包</title></head>
            <body style="font-family:sans-serif;padding:2rem;">
            <h2>登入完成</h2>
            <p>請立刻回到「我的錢包」App 完成連結（勿只停在此頁）。</p>
            </body></html>
            """.trimIndent()
        } else {
            """
            <!DOCTYPE html><html><head><meta charset="utf-8"><title>我的錢包</title></head>
            <body style="font-family:sans-serif;padding:2rem;"><p>…</p></body></html>
            """.trimIndent()
        }
        try {
            PrintWriter(socket.getOutputStream(), true).use { out ->
                out.print("HTTP/1.1 200 OK\r\n")
                out.print("Content-Type: text/html; charset=utf-8\r\n")
                out.print("Connection: close\r\n")
                out.print("Content-Length: ${html.toByteArray(Charsets.UTF_8).size}\r\n")
                out.print("\r\n")
                out.print(html)
            }
        } catch (_: Exception) {
        }
        return outcome
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) return@mapNotNull null
            val k = URLDecoder.decode(part.substring(0, i), Charsets.UTF_8.name())
            val v = URLDecoder.decode(part.substring(i + 1), Charsets.UTF_8.name())
            k to v
        }.toMap()
    }
}
