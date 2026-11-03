package com.gino.mywallet.domain.command

/** 指令協定 mywallet-cmd/v1 */
object CommandSchema {
    const val ID = "mywallet-cmd/v1"
    const val RESULT_ID = "mywallet-cmd-result/v1"
    const val ENVELOPE_ID = "mywallet-cmd-envelope/v1"
    const val MAX_COMMANDS = 300
    const val MAX_PLAN_ITEMS = 200
}

data class CommandPlan(
    val schema: String,
    val mode: String, // apply | preview
    val note: String? = null,
    val commands: List<WalletCommand>,
    /** Bot 信封用：指令批次 id */
    val id: String? = null,
    /** Bot 信封用：必須等於手機目前 revision */
    val basedOnRevision: Long? = null
)

data class CommandEnvelope(
    val schema: String,
    val id: String,
    val sig: String,
    val payload: String
)

/** 絕對日期或學期週數（擇一） */
data class CommandWhen(
    val date: String? = null,
    val week: Int? = null,
    val weekday: Int? = null,
    val time: String? = null
)

sealed class WalletCommand {
    data class TaskCreate(
        val title: String,
        val category: String = "其他",
        val note: String = "",
        val ticketLevel: Int = 2,
        val repeatable: Boolean = false,
        val repeatFreq: String = "daily",
        val repeatN: Int? = null,
        val rewardYuan: Long? = null,
        val rewardTo: String = "living",
        val penaltyYuan: Long? = null,
        val penaltyFrom: String = "living",
        val planned: CommandWhen? = null,
        val planId: String? = null
    ) : WalletCommand()

    data class CourseUpsert(
        val title: String,
        val weekday: Int,
        val periodStart: Int,
        val periodEnd: Int,
        val location: String = "",
        val weeks: List<Int> = emptyList()
    ) : WalletCommand()

    data class CourseReplaceAll(
        val courses: List<CourseUpsert>
    ) : WalletCommand()

    data class EventCreate(
        val title: String,
        val date: String? = null,
        val week: Int? = null,
        val weekday: Int? = null,
        val time: String? = null,
        val location: String = "",
        val note: String = ""
    ) : WalletCommand()

    data class EventUpdate(
        val id: Long,
        val title: String? = null,
        val date: String? = null,
        val week: Int? = null,
        val weekday: Int? = null,
        val time: String? = null,
        val clearTime: Boolean = false,
        val location: String? = null,
        val note: String? = null
    ) : WalletCommand()

    data class EventDelete(
        val id: Long
    ) : WalletCommand()

    /** id 或 titleContains 擇一（都有時優先 id）；titleContains 須唯一命中 OPEN 任務 */
    data class TaskUpdate(
        val id: Long? = null,
        val titleContains: String? = null,
        val title: String? = null,
        val category: String? = null,
        val note: String? = null,
        val ticketLevel: Int? = null,
        val rewardYuan: Long? = null,
        val rewardTo: String? = null,
        val clearReward: Boolean = false,
        val penaltyYuan: Long? = null,
        val penaltyFrom: String? = null,
        val clearPenalty: Boolean = false,
        val repeatable: Boolean? = null,
        val repeatFreq: String? = null,
        val repeatN: Int? = null,
        val planned: CommandWhen? = null,
        val clearPlanned: Boolean = false,
        val planId: String? = null,
        val clearPlanId: Boolean = false
    ) : WalletCommand()

    data class CourseDelete(
        val id: Long
    ) : WalletCommand()

    data class IncomeRecord(
        val yuan: Double,
        val note: String = "",
        val deferCentral: Boolean = true
    ) : WalletCommand()

    data class RewardGrant(
        val nonce: String,
        val yuan: Double? = null,
        val to: String? = null,
        val ticketKind: String? = null,
        val ticketN: Int = 0,
        val note: String = ""
    ) : WalletCommand()

    /** id 或 titleContains 擇一（都有時優先 id）；titleContains 須唯一命中 */
    data class TaskDelete(
        val id: Long? = null,
        val titleContains: String? = null,
        val status: String = "OPEN"
    ) : WalletCommand()

    data class SemesterSet(
        val week1Monday: String,
        val weeks: Int = 18
    ) : WalletCommand()

    data class PlanUpsert(
        val id: String,
        val title: String,
        val note: String = ""
    ) : WalletCommand()

    data class PlanImport(
        val id: String,
        val title: String,
        val note: String = "",
        val items: List<TaskCreate>
    ) : WalletCommand()

    /** 錢包轉帳（元）；撲滿↔現金會進待實體轉移 */
    data class WalletTransfer(
        val from: String,
        val to: String,
        val yuan: Double,
        val note: String = ""
    ) : WalletCommand()

    /** 完成進行中任務；id 與 title 擇一（都有時優先 id） */
    data class TaskComplete(
        val id: Long? = null,
        val title: String? = null
    ) : WalletCommand()

    data class TaskFail(
        val id: Long? = null,
        val title: String? = null
    ) : WalletCommand()

    /**
     * 專注結算。
     * @param cashYuan 省略則依長期規則由分鐘推算
     */
    data class FocusSettle(
        val minutes: Int,
        val cashYuan: Int? = null,
        val grantTickets: Boolean = true,
        val intent: String = "PC暫代",
        val category: String = "其他"
    ) : WalletCommand()

    /** 手動加券；kind = SLOT/BJ/SCRATCH/DRAW/LOTTO/MATCH/MATH */
    data class TicketAdd(
        val kind: String,
        val n: Int
    ) : WalletCommand()

    /** 僅記錄於執行結果，不改 DB */
    data class Note(
        val text: String
    ) : WalletCommand()

    data class ExamSaveCourse(
        val subject: String,
        val note: String = "",
        val units: List<String> = emptyList()
    ) : WalletCommand()

    /** id 或 subject+title 擇一 */
    data class ExamComplete(
        val id: Long? = null,
        val subject: String? = null,
        val title: String? = null
    ) : WalletCommand()

    data class ExamEnqueue(
        val id: Long? = null,
        val subject: String? = null,
        val title: String? = null
    ) : WalletCommand()

    data class ExamDequeue(
        val id: Long? = null,
        val subject: String? = null,
        val title: String? = null
    ) : WalletCommand()

    data class ExamDeleteItem(
        val id: Long? = null,
        val subject: String? = null,
        val title: String? = null
    ) : WalletCommand()

    data class LoanBorrow(
        val lender: String,
        val borrower: String,
        val yuan: Double,
        val dueThisMonth: Boolean = false
    ) : WalletCommand()

    data class LoanRepay(
        val id: Long,
        val yuan: Double
    ) : WalletCommand()

    data class LoanExtend(
        val id: Long
    ) : WalletCommand()

    /** 更新戶頭月利率（percent：5 = 5%/月） */
    data class WalletRate(
        val walletId: String,
        val percent: Double
    ) : WalletCommand()

    /**
     * 確認待實體轉移。
     * @param yuan null＝全額；0＝僅在淨額為 0 時結清相抵
     */
    data class WalletConfirmPhysical(
        val yuan: Double? = null
    ) : WalletCommand()
}

data class CommandResultItem(
    val index: Int,
    val op: String,
    val status: String, // ok | error | preview
    val message: String,
    val id: Long? = null,
    val title: String? = null
)

data class CommandRunResult(
    val schema: String = CommandSchema.RESULT_ID,
    val ok: Boolean,
    val mode: String,
    val applied: Int,
    val failed: Int,
    val results: List<CommandResultItem>
)
