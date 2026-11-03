package com.gino.mywallet.domain

import com.gino.mywallet.data.entity.RewardItem
import com.gino.mywallet.domain.game.GamePrizes
import com.gino.mywallet.domain.game.TicketKind

/**
 * 本月專注時數達標（依已結算場次 effectiveMs 加總）。
 * 獎勵用既有 [RewardItem]：`TICKET_*` + tickets 即可發「某款娛樂券 ×N」。
 */
data class FocusMonthGoal(
    val id: String,
    val requiredMs: Long,
    val label: String,
    val rewards: List<RewardItem>
) {
    fun rewardLabel(): String =
        rewards.joinToString("、") { item ->
            when (item.kind) {
                "PRIZE" -> "💰${(item.amountCents ?: 0L) / 100L} 元"
                "TICKET_SLOT" -> "${TicketKind.SLOT.symbol}×${item.tickets ?: 0}"
                "TICKET_BJ" -> "${TicketKind.BJ.symbol}×${item.tickets ?: 0}"
                "TICKET_SCRATCH" -> "${TicketKind.SCRATCH.symbol}×${item.tickets ?: 0}"
                "TICKET_DRAW" -> "${TicketKind.DRAW.symbol}×${item.tickets ?: 0}"
                "TICKET_LOTTO" -> "${TicketKind.LOTTO.symbol}×${item.tickets ?: 0}"
                "TICKET_MATCH" -> "${TicketKind.MATCH.symbol}×${item.tickets ?: 0}"
                "TICKET_MATH" -> "${TicketKind.MATH.symbol}×${item.tickets ?: 0}"
                "TICKET_BUDGET" -> "🎟≈$${(item.amountCents ?: 0L) / 100L}"
                else -> item.kind
            }
        }
}

object FocusMonthGoals {
    private const val HOUR_MS = 60L * 60L * 1000L

    val ALL: List<FocusMonthGoal> = listOf(
        goal("h5", 5, "本月專注 5 小時", tickets(TicketKind.BJ, 10)),
        goal(
            "h10",
            10,
            "本月專注 10 小時",
            prize(50) + tickets(TicketKind.SLOT, 10)
        ),
        goal(
            "h20",
            20,
            "本月專注 20 小時",
            prize(100) + tickets(TicketKind.SCRATCH, 10)
        ),
        goal(
            "h40",
            40,
            "本月專注 40 小時",
            prize(200) + tickets(TicketKind.DRAW, 10) + tickets(TicketKind.LOTTO, 5)
        )
    )

    fun byId(id: String): FocusMonthGoal? = ALL.find { it.id == id }

    /** 方便任務／通行證等地方組「指定娛樂券 ×N」 */
    fun tickets(kind: TicketKind, n: Int): List<RewardItem> {
        val kindKey = when (kind) {
            TicketKind.SLOT -> "TICKET_SLOT"
            TicketKind.BJ -> "TICKET_BJ"
            TicketKind.SCRATCH -> "TICKET_SCRATCH"
            TicketKind.DRAW -> "TICKET_DRAW"
            TicketKind.LOTTO -> "TICKET_LOTTO"
            TicketKind.MATCH -> "TICKET_MATCH"
            TicketKind.MATH -> "TICKET_MATH"
        }
        return listOf(RewardItem(kind = kindKey, tickets = n))
    }

    fun budgetYuan(rewards: List<RewardItem>): Int =
        rewards.sumOf { item ->
            when (item.kind) {
                "PRIZE", "TICKET_BUDGET" -> ((item.amountCents ?: 0L) / 100L).toInt()
                "TICKET_SLOT" -> (item.tickets ?: 0) * GamePrizes.EV_SLOT_YUAN
                "TICKET_BJ" -> (item.tickets ?: 0) * GamePrizes.EV_BJ_YUAN
                "TICKET_SCRATCH" -> (item.tickets ?: 0) * GamePrizes.EV_SCRATCH_YUAN
                "TICKET_DRAW" -> (item.tickets ?: 0) * GamePrizes.EV_DRAW_YUAN
                "TICKET_LOTTO" -> (item.tickets ?: 0) * GamePrizes.EV_LOTTO_YUAN
                "TICKET_MATCH" -> (item.tickets ?: 0) * GamePrizes.EV_MATCH_YUAN
                "TICKET_MATH" -> (item.tickets ?: 0) * GamePrizes.EV_MATH_YUAN
                else -> 0
            }
        }

    private fun goal(
        id: String,
        hours: Int,
        label: String,
        rewards: List<RewardItem>
    ) = FocusMonthGoal(
        id = id,
        requiredMs = hours * HOUR_MS,
        label = label,
        rewards = rewards
    )

    private fun prize(yuan: Long) = listOf(
        RewardItem(kind = "PRIZE", amountCents = yuan * 100)
    )
}
