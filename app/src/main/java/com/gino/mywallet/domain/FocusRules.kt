package com.gino.mywallet.domain

import com.gino.mywallet.domain.game.GamePrizes
import com.gino.mywallet.domain.game.TicketKind
import kotlin.random.Random

enum class FocusMode {
    Long,
    Short
}

/** 短期專注可選時長（分鐘）與完成現金獎 */
enum class ShortFocusDuration(val minutes: Int, val rewardYuan: Int) {
    M10(10, 20),
    M20(20, 40),
    M30(30, 60);

    val targetMs: Long get() = minutes * 60_000L

    companion object {
        fun fromMinutes(minutes: Int): ShortFocusDuration =
            entries.firstOrNull { it.minutes == minutes }
                ?: error("不支援的短期時長：$minutes")
    }
}

object FocusRules {
    const val START_CASH_YUAN = -50
    /** 此分鐘（含）以前的加薪檔為固定額；之後改隨機 */
    const val FIXED_CASH_UNTIL_MINUTES = 30
    const val CASH_EVERY_MINUTES = 4
    const val CASH_PER_STEP_FIXED = 8
    const val CASH_PER_STEP_MIN = 10
    const val CASH_PER_STEP_MAX = 12
    const val MAX_REWARD_MINUTES = 120
    const val TICKET_EVERY_MINUTES = 30

    /** 短期進行中／失敗時顯示與結算的押金 */
    const val SHORT_STAKE_YUAN = -30

    const val RING_DOTS = 16
    const val DOT_INTERVAL_MS = 15_000L
    /** 16×15s = 4 分鐘一檔 */
    const val RING_CYCLE_MS = DOT_INTERVAL_MS * RING_DOTS

    /**
     * 單場累積毫秒合理上限（含暫停外的實際專注）。
     * 超過可視為資料損壞（例如誤把 wall-clock 寫入 accumulatedMs）。
     */
    const val MAX_PLAUSIBLE_ACCUMULATED_MS = 24L * 60 * 60 * 1000

    /** 約 2001 起的 epoch ms；大於此幾乎不可能是「經過時間」 */
    private const val EPOCH_MS_FLOOR = 1_000_000_000_000L

    /**
     * 消毒累積毫秒：拒絕時間戳／超大值。
     * 若損壞且 [wallStartAt] 合理，改以 now−wall 回推（上限 [MAX_PLAUSIBLE_ACCUMULATED_MS]）。
     */
    fun sanitizeAccumulatedMs(
        accumulatedMs: Long,
        wallStartAt: Long = 0L,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        if (accumulatedMs in 0..MAX_PLAUSIBLE_ACCUMULATED_MS) return accumulatedMs
        if (wallStartAt in EPOCH_MS_FLOOR..nowMs) {
            return (nowMs - wallStartAt).coerceIn(0L, MAX_PLAUSIBLE_ACCUMULATED_MS)
        }
        return 0L
    }

    /** 獎勵用分鐘（上限 120） */
    fun rewardMinutes(effectiveMs: Long): Int =
        (effectiveMs / 60_000L).toInt().coerceIn(0, MAX_REWARD_MINUTES)

    /**
     * 長期加薪檔數：每 4 分鐘一檔（4、8、…、120）。
     * 10 分＝2；30 分＝7；32 分＝8。
     */
    fun cashStepCount(effectiveMs: Long): Int {
        val m = rewardMinutes(effectiveMs)
        return m / CASH_EVERY_MINUTES
    }

    /** 第 [stepIndex] 檔（0-based）達成時的分鐘數 */
    fun stepEarnMinute(stepIndex: Int): Int = (stepIndex + 1) * CASH_EVERY_MINUTES

    /** 該檔是否仍用固定 8 元（達成分鐘 ≤ 30） */
    fun isFixedCashStep(stepIndex: Int): Boolean =
        stepEarnMinute(stepIndex) <= FIXED_CASH_UNTIL_MINUTES

    fun rollCashStep(stepIndex: Int, random: Random = Random.Default): Int =
        if (isFixedCashStep(stepIndex)) {
            CASH_PER_STEP_FIXED
        } else {
            random.nextInt(CASH_PER_STEP_MIN, CASH_PER_STEP_MAX + 1)
        }

    /**
     * 將 [steps] 補到 [targetCount]；新檔依規則抽固定 8 或隨機 10～12。
     */
    fun ensureCashSteps(
        steps: List<Int>,
        targetCount: Int,
        random: Random = Random.Default
    ): List<Int> {
        if (targetCount <= steps.size) return steps.take(targetCount)
        val out = steps.toMutableList()
        while (out.size < targetCount) {
            out += rollCashStep(out.size, random)
        }
        return out
    }

    fun longCashYuan(steps: List<Int>): Int =
        START_CASH_YUAN + steps.sum()

    /** 短期完成現金；未完成則為押金 */
    fun shortCashYuan(duration: ShortFocusDuration, completed: Boolean): Int =
        if (completed) duration.rewardYuan else SHORT_STAKE_YUAN

    /** 每 30 分鐘 1 單位混券；另有保證 🃏／🎫／🎴（僅長期） */
    fun ticketUnits(effectiveMs: Long): Int {
        val m = rewardMinutes(effectiveMs)
        return m / TICKET_EVERY_MINUTES
    }

    fun ticketEvYuan(effectiveMs: Long): Int =
        ticketUnits(effectiveMs) * GamePrizes.EV_PER_LEVEL_YUAN

    /**
     * 保證券：30 分 🃏×1、60 分再 🃏×1、90 分 🎫×1、120 分再 🎫×1；
     * 滿 60 分另給 🎴×1。
     */
    fun guaranteedTickets(effectiveMs: Long): Map<TicketKind, Int> {
        val m = rewardMinutes(effectiveMs)
        val out = mutableMapOf<TicketKind, Int>()
        val bj = m / TICKET_EVERY_MINUTES
        if (bj > 0) out[TicketKind.BJ] = bj
        if (m >= 60) out[TicketKind.DRAW] = 1
        if (m >= 90) out[TicketKind.SCRATCH] = 1
        if (m >= 120) {
            out[TicketKind.SCRATCH] = (out[TicketKind.SCRATCH] ?: 0) + 1
        }
        return out
    }

    @Deprecated("改用 ticketUnits", ReplaceWith("ticketUnits(effectiveMs)"))
    fun tickets(effectiveMs: Long): Int = ticketUnits(effectiveMs)

    /**
     * 長期圓環：每 15 秒多亮 1 顆；滿 16 顆後維持全亮，直到下一檔開始再清空。
     */
    fun litCount(effectiveMs: Long): Int {
        val ms = effectiveMs.coerceAtLeast(0L)
        val rem = ms % RING_CYCLE_MS
        return if (rem == 0L && ms > 0L) {
            RING_DOTS
        } else {
            (rem / DOT_INTERVAL_MS).toInt().coerceIn(0, RING_DOTS)
        }
    }

    /** 短期圓環：依目標時長均分 16 點進度 */
    fun shortLitCount(elapsedMs: Long, targetMs: Long): Int {
        if (targetMs <= 0L) return 0
        val ratio = elapsedMs.toDouble() / targetMs.toDouble()
        return (ratio * RING_DOTS).toInt().coerceIn(0, RING_DOTS)
    }

    fun formatElapsed(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun formatCountdown(remainingMs: Long): String =
        formatElapsed(remainingMs.coerceAtLeast(0L))
}
