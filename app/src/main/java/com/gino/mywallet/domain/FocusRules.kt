package com.gino.mywallet.domain

import com.gino.mywallet.domain.game.GamePrizes
import com.gino.mywallet.domain.game.TicketKind

object FocusRules {
    const val START_CASH_YUAN = -50
    const val CASH_PER_4_MIN = 8
    const val MAX_REWARD_MINUTES = 120
    const val TICKET_EVERY_MINUTES = 30
    const val CASH_EVERY_MINUTES = 4

    const val RING_DOTS = 16
    const val DOT_INTERVAL_MS = 15_000L
    /** 16×15s = 4 分鐘一檔 */
    const val RING_CYCLE_MS = DOT_INTERVAL_MS * RING_DOTS

    /** 獎勵用分鐘（上限 120） */
    fun rewardMinutes(effectiveMs: Long): Int =
        (effectiveMs / 60_000L).toInt().coerceIn(0, MAX_REWARD_MINUTES)

    fun cashYuan(effectiveMs: Long): Int {
        val m = rewardMinutes(effectiveMs)
        return START_CASH_YUAN + CASH_PER_4_MIN * (m / CASH_EVERY_MINUTES)
    }

    /** 每 30 分鐘 1 單位混券；另有保證 🃏／🎫／🎴 */
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
     * 圓環已點亮顆數（0～16）。
     * 每 15 秒多亮 1 顆；滿 16 顆後維持全亮，直到下一檔開始再清空。
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

    fun formatElapsed(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
