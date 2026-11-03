package com.gino.mywallet.domain.bot

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gino.mywallet.MyWalletApp
import com.gino.mywallet.data.bot.BotSyncPrefs
import com.gino.mywallet.data.bot.GoogleDriveClient
import com.gino.mywallet.domain.command.CommandExecutor
import com.gino.mywallet.notify.NotificationScheduler

/** 背景讀取 Drive inbox；失敗只寫 lastSyncMessage，不彈 toast */
class BotSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = BotSyncPrefs(applicationContext)
        if (!prefs.isDriveAuthorized || prefs.token.isBlank()) {
            return Result.success()
        }
        val app = applicationContext as? MyWalletApp
            ?: runCatching { MyWalletApp.instance }.getOrNull()
            ?: return Result.retry()

        return try {
            val drive = GoogleDriveClient(prefs)
            drive.ensureAccessToken()
            drive.ensureBotFolders()
            val executor = CommandExecutor(
                engine = app.engine,
                taskDao = app.database.taskDao(),
                courseDao = app.database.courseDao(),
                calendarEventDao = app.database.calendarEventDao(),
                planDao = app.database.planDao(),
                examDao = app.database.examDao()
            )
            val report = BotFolderSync(
                context = applicationContext,
                engine = app.engine,
                metaDao = app.database.metaDao(),
                commandExecutor = executor,
                prefs = prefs
            ).sync()
            prefs.lastSyncAt = System.currentTimeMillis()
            prefs.lastSyncMessage = report.message
            if (report.appliedBatches > 0) {
                NotificationScheduler.rescheduleAll(applicationContext)
            }
            if (report.ok) Result.success() else Result.retry()
        } catch (_: Exception) {
            prefs.lastSyncMessage = "背景同步失敗（稍後重試）"
            Result.retry()
        }
    }
}
