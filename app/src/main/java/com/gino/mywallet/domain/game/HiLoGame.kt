package com.gino.mywallet.domain.game

import kotlin.math.roundToLong
import kotlin.random.Random

enum class HiLoPick { HIGH, LOW }

enum class HiLoOutcome { WIN, LOSE, TIE }

data class HiLoState(
    /** 基準數 1～100；0 表示尚未開局 */
    val base: Int = 0,
    val pick: HiLoPick? = null,
    val reveal: Int? = null,
    val outcome: HiLoOutcome? = null,
    /** 本次猜對應發的獎金（分）；平手為 TIE_PRIZE */
    val payoutCents: Long = 0L,
    val phase: HiLoPhase = HiLoPhase.IDLE
)

enum class HiLoPhase { IDLE, READY, REVEALING, RESULT }

object HiLoGame {
    const val MIN = 1
    const val MAX = 100
    /** 目標期望值（元） */
    const val TARGET_EV_YUAN = 20L
    /** 開出相同數字的獎金（元） */
    const val TIE_PRIZE_YUAN = 500L

    fun newBase(random: Random = Random.Default): HiLoState =
        HiLoState(
            base = random.nextInt(MIN, MAX + 1),
            phase = HiLoPhase.READY
        )

    /** 選「更高」時，猜對可拿的獎金（元） */
    fun payoutYuanIfHigh(base: Int): Long? {
        val wins = (MAX - base).coerceAtLeast(0)
        if (wins <= 0) return null
        return fairWinYuan(wins)
    }

    /** 選「更低」時，猜對可拿的獎金（元） */
    fun payoutYuanIfLow(base: Int): Long? {
        val wins = (base - MIN).coerceAtLeast(0)
        if (wins <= 0) return null
        return fairWinYuan(wins)
    }

    /**
     * 使 EV = P(win)×W + P(tie)×平手獎 ≈ TARGET
     * P(tie)=1/100；其餘由勝場均攤。
     */
    private fun fairWinYuan(winningOutcomes: Int): Long {
        val tieEv = TIE_PRIZE_YUAN.toDouble() / (MAX - MIN + 1)
        val needFromWins = (TARGET_EV_YUAN - tieEv).coerceAtLeast(0.0)
        val pWin = winningOutcomes.toDouble() / (MAX - MIN + 1)
        return (needFromWins / pWin).roundToLong().coerceAtLeast(1L)
    }

    fun resolve(
        state: HiLoState,
        pick: HiLoPick,
        random: Random = Random.Default
    ): HiLoState {
        require(state.phase == HiLoPhase.READY) { "非待猜狀態" }
        require(state.base in MIN..MAX)
        when (pick) {
            HiLoPick.HIGH -> require(payoutYuanIfHigh(state.base) != null) { "已是最高，無法選更高" }
            HiLoPick.LOW -> require(payoutYuanIfLow(state.base) != null) { "已是最低，無法選更低" }
        }
        val reveal = random.nextInt(MIN, MAX + 1)
        val outcome = when {
            reveal == state.base -> HiLoOutcome.TIE
            pick == HiLoPick.HIGH && reveal > state.base -> HiLoOutcome.WIN
            pick == HiLoPick.LOW && reveal < state.base -> HiLoOutcome.WIN
            else -> HiLoOutcome.LOSE
        }
        val payoutYuan = when (outcome) {
            HiLoOutcome.TIE -> TIE_PRIZE_YUAN
            HiLoOutcome.WIN -> when (pick) {
                HiLoPick.HIGH -> payoutYuanIfHigh(state.base)!!
                HiLoPick.LOW -> payoutYuanIfLow(state.base)!!
            }
            HiLoOutcome.LOSE -> 0L
        }
        return state.copy(
            pick = pick,
            reveal = reveal,
            outcome = outcome,
            payoutCents = payoutYuan * 100,
            phase = HiLoPhase.RESULT
        )
    }
}
