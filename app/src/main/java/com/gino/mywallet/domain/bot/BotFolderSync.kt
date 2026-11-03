package com.gino.mywallet.domain.bot

import android.content.Context
import com.gino.mywallet.data.bot.BotSyncPrefs
import com.gino.mywallet.data.bot.GoogleDriveClient
import com.gino.mywallet.data.dao.MetaDao
import com.gino.mywallet.data.entity.AppMetaEntity
import com.gino.mywallet.data.entity.MetaKeys
import com.gino.mywallet.domain.EconomyEngine
import com.gino.mywallet.domain.command.CommandExecutor
import com.gino.mywallet.domain.command.CommandParser
import com.gino.mywallet.domain.command.CommandSchema
import com.gino.mywallet.notify.NotificationHelper
import com.gino.mywallet.notify.NotifyIds
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BotSyncReport(
    val ok: Boolean,
    val message: String,
    val appliedBatches: Int = 0,
    val rejectedBatches: Int = 0
)

class BotFolderSync(
    private val context: Context,
    private val engine: EconomyEngine,
    private val metaDao: MetaDao,
    private val commandExecutor: CommandExecutor,
    private val prefs: BotSyncPrefs,
    private val parser: CommandParser = CommandParser(),
    private val gson: Gson = Gson()
) {
    suspend fun sync(): BotSyncReport = withContext(Dispatchers.IO) {
        if (prefs.token.isBlank()) {
            return@withContext BotSyncReport(false, "尚未設定權杖")
        }
        if (prefs.refreshToken.isBlank()) {
            return@withContext BotSyncReport(false, "尚未登入 Google Drive")
        }

        val drive = GoogleDriveClient(prefs)
        drive.ensureAccessToken()
        drive.ensureBotFolders()

        val inboxId = prefs.inboxFolderId
        val outboxId = prefs.outboxFolderId
        val rootId = prefs.rootFolderId
        // 同趟同步：多個 inbox 檔可共用此 basedOnRevision 依序套用
        val batchBaseRevision = engine.currentRevision()

        var applied = 0
        var rejected = 0
        var appliedCommands = 0
        val inboxFiles = drive.listJsonFiles(inboxId)

        for (file in inboxFiles) {
            val raw = try {
                drive.downloadText(file.id)
            } catch (_: Exception) {
                continue
            }
            try {
                val envelope = parser.parseEnvelope(raw)
                val plan = parser.parse(envelope.payload)
                val already = metaDao.get(MetaKeys.BOT_CMD_PREFIX + envelope.id) != null
                val reject = BotSyncGate.validate(
                    secret = prefs.token,
                    envelope = envelope,
                    plan = plan,
                    currentRevision = engine.currentRevision(),
                    alreadyApplied = already,
                    batchBaseRevision = batchBaseRevision
                )
                if (reject != null) {
                    rejected++
                    drive.upsertTextFile(
                        outboxId,
                        file.name,
                        errorResult(
                            id = envelope.id,
                            reason = reject.reason,
                            revision = engine.currentRevision(),
                            basedOnRevision = plan.basedOnRevision
                        )
                    )
                    drive.deleteFile(file.id)
                    continue
                }
                val run = commandExecutor.run(plan.copy(mode = "apply"), dryRun = false)
                metaDao.upsert(
                    AppMetaEntity(
                        MetaKeys.BOT_CMD_PREFIX + envelope.id,
                        System.currentTimeMillis().toString()
                    )
                )
                applied++
                appliedCommands += run.applied
                val revisionAfter = engine.currentRevision()
                val body = gson.toJson(
                    buildMap {
                        put("schema", CommandSchema.RESULT_ID)
                        put("ok", run.ok)
                        put("id", envelope.id)
                        put("mode", run.mode)
                        put("applied", run.applied)
                        put("failed", run.failed)
                        put("revision", revisionAfter)
                        if (plan.basedOnRevision != null) {
                            put("basedOnRevision", plan.basedOnRevision)
                        }
                        put(
                            "results",
                            run.results.map {
                                mapOf(
                                    "index" to it.index,
                                    "op" to it.op,
                                    "status" to it.status,
                                    "message" to it.message,
                                    "id" to it.id,
                                    "title" to it.title
                                )
                            }
                        )
                    }
                )
                drive.upsertTextFile(outboxId, file.name, body)
                drive.deleteFile(file.id)
            } catch (e: Exception) {
                rejected++
                drive.upsertTextFile(
                    outboxId,
                    file.name,
                    errorResult(
                        id = null,
                        reason = e.message ?: e.toString(),
                        revision = engine.currentRevision()
                    )
                )
                try {
                    drive.deleteFile(file.id)
                } catch (_: Exception) {
                }
            }
        }

        drive.upsertTextFile(rootId, "snapshot.json", engine.exportSnapshotJson())

        if (applied > 0) {
            NotificationHelper.show(
                context,
                NotifyIds.CHANNEL_TASK,
                NotifyIds.NOTIF_BOT_SYNC,
                "Bot 同步",
                "已套用 $applied 批（共 $appliedCommands 條指令）"
            )
        }

        val msg = buildString {
            append("摘要已上傳到 Drive")
            if (applied > 0) append(" · 套用 $applied 批／$appliedCommands 條")
            if (rejected > 0) append(" · 拒絕 $rejected 批")
        }
        BotSyncReport(true, msg, appliedBatches = applied, rejectedBatches = rejected)
    }

    private fun errorResult(
        id: String?,
        reason: String,
        revision: Long,
        basedOnRevision: Long? = null
    ): String =
        gson.toJson(
            buildMap {
                put("schema", CommandSchema.RESULT_ID)
                put("ok", false)
                if (id != null) put("id", id)
                put("error", reason)
                put("mode", "apply")
                put("applied", 0)
                put("failed", 1)
                put("revision", revision)
                if (basedOnRevision != null) put("basedOnRevision", basedOnRevision)
            }
        )
}
