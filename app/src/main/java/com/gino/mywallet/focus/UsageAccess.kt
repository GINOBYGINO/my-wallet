package com.gino.mywallet.focus

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import android.app.usage.UsageStatsManager

object UsageAccess {
    fun hasPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /** 最近使用中的前景套件是否為本 App */
    fun isOurAppForeground(context: Context): Boolean {
        if (!hasPermission(context)) return true
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return true
        val end = System.currentTimeMillis()
        val start = end - 60_000L
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            ?: return true
        if (stats.isEmpty()) return true
        val recent = stats.maxByOrNull { it.lastTimeUsed } ?: return true
        return recent.packageName == context.packageName
    }
}
