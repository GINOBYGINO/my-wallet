package com.gino.mywallet

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Deep link：mywallet://grant?source=rote&level=3&date=yyyy-MM-dd&nonce=...&note=...
 * 僅接受 com.gino.rote（callingPackage；若為 null 則要求該套件已安裝）。
 */
class GrantRewardActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data == null || data.scheme != "mywallet" || data.host != "grant") {
            finishWith(Activity.RESULT_CANCELED, "無效的入帳連結")
            return
        }
        if (!callerAllowed()) {
            finishWith(Activity.RESULT_CANCELED, "未授權的來源 App")
            return
        }
        val source = data.getQueryParameter("source").orEmpty()
        val level = data.getQueryParameter("level")?.toIntOrNull() ?: 0
        val date = data.getQueryParameter("date").orEmpty()
        val nonce = data.getQueryParameter("nonce").orEmpty()
        val note = data.getQueryParameter("note").orEmpty()
        val grantId = data.getQueryParameter("grantId").orEmpty()

        val app = application as MyWalletApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (fresh, pass) = app.engine.grantExternalTicketLevel(
                    source = source,
                    date = date,
                    nonce = nonce,
                    level = level,
                    note = note
                )
                val msg = buildString {
                    if (fresh) {
                        append("死記硬背入帳成功 · ${level} 級券包")
                        pass?.let {
                            append(" · 通行證 +1")
                            if (it.claimedReward) append("（有獎勵可領）")
                        }
                    } else {
                        append("此筆獎勵已入帳過（略過重複）")
                    }
                    if (note.isNotBlank()) append("\n").append(note)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GrantRewardActivity, msg, Toast.LENGTH_LONG).show()
                    val result = intent.apply {
                        putExtra("ok", true)
                        putExtra("fresh", fresh)
                        putExtra("grantId", grantId)
                    }
                    setResult(Activity.RESULT_OK, result)
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    finishWith(Activity.RESULT_CANCELED, e.message ?: "入帳失敗")
                }
            }
        }
    }

    private fun callerAllowed(): Boolean {
        val caller = callingPackage
        if (caller == ROTE_PACKAGE) return true
        if (caller != null) return false
        // startActivity 而非 forResult 時 callingPackage 常為 null；個人用改查套件是否安裝
        return try {
            packageManager.getPackageInfo(ROTE_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun finishWith(code: Int, message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        setResult(code)
        finish()
    }

    companion object {
        const val ROTE_PACKAGE = "com.gino.rote"
    }
}
