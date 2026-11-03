package com.gino.mywallet.domain.bot

import com.gino.mywallet.domain.command.CommandEnvelope
import com.gino.mywallet.domain.command.CommandPlan
import com.gino.mywallet.domain.command.WalletCommand

/** 驗章與 revision／mode／冪等閘門（純邏輯，可 JVM 測試） */
sealed class BotSyncReject(val reason: String) {
    data object BadSignature : BotSyncReject("簽章不符")
    data object NotApply : BotSyncReject("mode 必須為 apply")
    data class RevisionMismatch(val expected: Long, val actual: Long?) :
        BotSyncReject("basedOnRevision=$actual 不符目前 revision=$expected")
    data object AlreadyApplied : BotSyncReject("指令 id 已套用過")
    data object MissingId : BotSyncReject("缺少指令 id")
    data class IdMismatch(val envelopeId: String, val payloadId: String?) :
        BotSyncReject("信封 id=$envelopeId 與 payload id=$payloadId 不一致")
    data object MixedSoftHard : BotSyncReject(
        "不可在同一信封混用軟／硬指令；請拆成不同信封（軟指令可忽略 revision，硬指令必須對齊）"
    )
    data object EmptyCommands : BotSyncReject("commands 不可為空")
}

enum class CommandSoftness {
    Soft,
    Hard
}

object BotSyncGate {
    /**
     * @param batchBaseRevision 同一次同步開始時的 revision；允許多個 inbox 檔
     *   皆以該值為 basedOnRevision 依序套用（即使中間 current 已前進）。
     */
    fun validate(
        secret: String,
        envelope: CommandEnvelope,
        plan: CommandPlan,
        currentRevision: Long,
        alreadyApplied: Boolean,
        batchBaseRevision: Long? = null
    ): BotSyncReject? {
        if (!BotHmac.verify(secret, envelope.payload, envelope.sig)) {
            return BotSyncReject.BadSignature
        }
        if (plan.id.isNullOrBlank()) return BotSyncReject.MissingId
        if (plan.id != envelope.id) {
            return BotSyncReject.IdMismatch(envelope.id, plan.id)
        }
        if (plan.mode != "apply") return BotSyncReject.NotApply
        if (alreadyApplied) return BotSyncReject.AlreadyApplied
        if (plan.commands.isEmpty()) return BotSyncReject.EmptyCommands

        val softness = planSoftness(plan) ?: return BotSyncReject.MixedSoftHard
        if (softness == CommandSoftness.Soft) {
            // 軟指令可省略／忽略 basedOnRevision
            return null
        }

        val based = plan.basedOnRevision
        val revisionOk = based != null && (
            based == currentRevision ||
                (batchBaseRevision != null && based == batchBaseRevision)
            )
        if (!revisionOk) {
            return BotSyncReject.RevisionMismatch(currentRevision, based)
        }
        return null
    }

    /**
     * @return Soft／Hard；混裝或無法分類時回傳 null
     */
    fun planSoftness(plan: CommandPlan): CommandSoftness? {
        if (plan.commands.isEmpty()) return null
        var soft = false
        var hard = false
        for (cmd in plan.commands) {
            when (commandSoftness(cmd)) {
                CommandSoftness.Soft -> soft = true
                CommandSoftness.Hard -> hard = true
            }
            if (soft && hard) return null
        }
        return if (hard) CommandSoftness.Hard else CommandSoftness.Soft
    }

    fun commandSoftness(cmd: WalletCommand): CommandSoftness = when (cmd) {
        is WalletCommand.TaskCreate,
        is WalletCommand.TaskUpdate,
        is WalletCommand.TaskDelete,
        is WalletCommand.CourseUpsert,
        is WalletCommand.CourseReplaceAll,
        is WalletCommand.CourseDelete,
        is WalletCommand.EventCreate,
        is WalletCommand.EventUpdate,
        is WalletCommand.EventDelete,
        is WalletCommand.SemesterSet,
        is WalletCommand.PlanUpsert,
        is WalletCommand.PlanImport,
        is WalletCommand.Note,
        is WalletCommand.ExamSaveCourse,
        is WalletCommand.ExamEnqueue,
        is WalletCommand.ExamDequeue,
        is WalletCommand.ExamDeleteItem -> CommandSoftness.Soft

        is WalletCommand.IncomeRecord,
        is WalletCommand.RewardGrant,
        is WalletCommand.WalletTransfer,
        is WalletCommand.WalletRate,
        is WalletCommand.WalletConfirmPhysical,
        is WalletCommand.TaskComplete,
        is WalletCommand.TaskFail,
        is WalletCommand.FocusSettle,
        is WalletCommand.TicketAdd,
        is WalletCommand.LoanBorrow,
        is WalletCommand.LoanRepay,
        is WalletCommand.LoanExtend,
        is WalletCommand.ExamComplete -> CommandSoftness.Hard
    }
}
