package com.gino.mywallet.domain.command

/** 指令協定 mywallet-cmd/v1 */
object CommandSchema {
    const val ID = "mywallet-cmd/v1"
    const val RESULT_ID = "mywallet-cmd-result/v1"
    const val MAX_COMMANDS = 100
}

data class CommandPlan(
    val schema: String,
    val mode: String, // apply | preview
    val note: String? = null,
    val commands: List<WalletCommand>
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
        val penaltyFrom: String = "living"
    ) : WalletCommand()

    data class CourseUpsert(
        val title: String,
        val weekday: Int,
        val periodStart: Int,
        val periodEnd: Int,
        val location: String = ""
    ) : WalletCommand()

    data class CourseReplaceAll(
        val courses: List<CourseUpsert>
    ) : WalletCommand()

    data class EventCreate(
        val title: String,
        val date: String,
        val time: String? = null,
        val location: String = "",
        val note: String = ""
    ) : WalletCommand()

    data class TaskDelete(
        val titleContains: String,
        val status: String = "OPEN"
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
