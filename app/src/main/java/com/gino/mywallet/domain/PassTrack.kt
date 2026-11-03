package com.gino.mywallet.domain

import com.gino.mywallet.data.entity.RewardItem
import com.gino.mywallet.domain.game.GamePrizes
import com.gino.mywallet.domain.game.TicketKind

data class PassTierSpec(
    val index: Int,
    val label: String,
    val rewards: List<RewardItem>
)

/**
 * 每月通行證 80 格。預算＝券期望值，合計 [BUDGET_YUAN]（舊版 2000 的約 1/3）。
 * 休息計 0；已移除公益減基準；券種以 🎰／混券為主，少數 🃏。
 */
object PassTrack {
    const val LENGTH = 80
    /** 舊版 2000 ≈×1/3 */
    const val BUDGET_YUAN = 667
    /** 進度達此格起顯示終局衝刺（最後 10 格含通關）。 */
    const val SPRINT_FROM = 70

    fun specs(): List<PassTierSpec> = (1..LENGTH).map { index ->
        when {
            index == LENGTH -> finale()
            index >= 51 -> mid(index)
            else -> early[index] ?: rest(index)
        }
    }

    fun budgetYuan(rewards: List<RewardItem>): Int =
        rewards.sumOf { item ->
            when (item.kind) {
                "PRIZE", "TRANSFER", "TICKET_BUDGET" -> ((item.amountCents ?: 0L) / 100L).toInt()
                "TICKET_SLOT" -> (item.tickets ?: 0) * GamePrizes.EV_SLOT_YUAN
                "TICKET_BJ" -> (item.tickets ?: 0) * GamePrizes.EV_BJ_YUAN
                "TICKET_SCRATCH" -> (item.tickets ?: 0) * GamePrizes.EV_SCRATCH_YUAN
                "TICKET_DRAW" -> (item.tickets ?: 0) * GamePrizes.EV_DRAW_YUAN
                "TICKET_LOTTO" -> (item.tickets ?: 0) * GamePrizes.EV_LOTTO_YUAN
                "TICKET_MATCH" -> (item.tickets ?: 0) * GamePrizes.EV_MATCH_YUAN
                "TICKET_MATH" -> (item.tickets ?: 0) * GamePrizes.EV_MATH_YUAN
                "TICKET_LEVEL" -> GamePrizes.ticketLevelBudgetYuan(item.level ?: 1)
                else -> 0
            }
        }

    fun totalBudgetYuan(track: List<PassTierSpec> = specs()): Int =
        track.sumOf { budgetYuan(it.rewards) }

    private fun finale(): PassTierSpec = PassTierSpec(
        index = LENGTH,
        label = "通關",
        rewards = ticketBudget(120) + slot(1) // 130
    )

    /**
     * 51–79：每 5 格 → 休息／🎰／休息／混券／休息
     * 混券格交替 12／18，拉低頻率與單格價值。
     */
    private fun mid(index: Int): PassTierSpec {
        val pos = (index - 51) % 5
        val wave = (index - 51) / 5
        return when (pos) {
            1 -> slotSpec(index, 1)
            3 -> budgetSpec(index, if (wave % 2 == 0) 15L else 22L)
            else -> rest(index)
        }
    }

    private fun rest(index: Int) = PassTierSpec(index, "休息", emptyList())

    private fun slotSpec(index: Int, n: Int) = PassTierSpec(
        index,
        "${TicketKind.SLOT.symbol}×$n",
        slot(n)
    )

    private fun budgetSpec(index: Int, yuan: Long) = PassTierSpec(
        index,
        budgetLabel(yuan),
        ticketBudget(yuan)
    )

    private fun ticketBudget(yuan: Long) = listOf(
        RewardItem(kind = "TICKET_BUDGET", amountCents = yuan * 100)
    )

    private fun slot(n: Int) = listOf(RewardItem(kind = "TICKET_SLOT", tickets = n))

    private fun bj(n: Int) = listOf(RewardItem(kind = "TICKET_BJ", tickets = n))

    private fun budgetLabel(yuan: Long) = "🎟≈$$yuan"

    /**
     * 1–50：休息為主；無 TAX_RELIEF；固定券幾乎只有 🎰，偶爾 🃏×1。
     * 合計與 mid+finale 對齊 [BUDGET_YUAN]。
     */
    private val early: Map<Int, PassTierSpec> = listOf(
        rest(1),
        rest(2),
        slotSpec(3, 1), // 10
        rest(4),
        rest(5),
        budgetSpec(6, 25), // 25
        rest(7),
        rest(8),
        rest(9),
        slotSpec(10, 1), // 10
        rest(11),
        rest(12),
        budgetSpec(13, 25), // 25
        rest(14),
        rest(15),
        PassTierSpec(16, "${TicketKind.BJ.symbol}×1", bj(1)), // 5
        rest(17),
        rest(18),
        budgetSpec(19, 30), // 30
        rest(20),
        rest(21),
        slotSpec(22, 1), // 10
        rest(23),
        rest(24),
        budgetSpec(25, 25), // 25
        rest(26),
        rest(27),
        rest(28),
        budgetSpec(29, 35), // 35
        rest(30),
        rest(31),
        slotSpec(32, 1), // 10
        rest(33),
        rest(34),
        budgetSpec(35, 30), // 30
        rest(36),
        rest(37),
        PassTierSpec(38, "${TicketKind.BJ.symbol}×1", bj(1)), // 5
        rest(39),
        rest(40),
        budgetSpec(41, 35), // 35
        rest(42),
        rest(43),
        slotSpec(44, 1), // 10
        rest(45),
        rest(46),
        budgetSpec(47, 40), // 40
        rest(48),
        rest(49),
        budgetSpec(50, 61) // 61 → 合計 BUDGET_YUAN
    ).associateBy { it.index }
}
