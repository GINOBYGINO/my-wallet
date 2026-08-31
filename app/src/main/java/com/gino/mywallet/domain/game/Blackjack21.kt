package com.gino.mywallet.domain.game

import kotlin.random.Random

enum class BjPhase { IDLE, PLAYING, RESULT }

enum class BjResult { BUST, WIN_21, WIN_BEAT, LOSE }

data class Blackjack21State(
    val dealer: Int = 18,
    val score: Int = 0,
    val phase: BjPhase = BjPhase.IDLE,
    val result: BjResult? = null,
    val lastDelta: Int? = null,
    /** 推進步數（沒贏莊家時安慰獎＝steps×10 元） */
    val steps: Int = 0,
    /** 安慰累積（分）＝steps × BJ_CONSOLATION_STEP_CENTS；爆牌不發 */
    val potCents: Long = 0L
) {
    /** 現在停牌會拿到的金額（擊敗獎／否則安慰累積） */
    fun standPayoutCents(): Long = when {
        score > dealer && score in 1..20 -> GamePrizes.BJ_BEAT_CENTS
        else -> potCents
    }

    fun standButtonLabel(): String {
        if (phase != BjPhase.PLAYING || score <= 0) return "停牌"
        return if (score > dealer && score < 21) {
            "停牌 · 擊敗 \$${GamePrizes.BJ_BEAT_CENTS / 100}"
        } else {
            val yuan = potCents / 100
            if (yuan > 0) "停牌 · \$$yuan" else "停牌"
        }
    }
}

object Blackjack21 {
    fun newRound(random: Random = Random.Default): Blackjack21State =
        Blackjack21State(
            dealer = random.nextInt(18, 21),
            score = 0,
            phase = BjPhase.PLAYING,
            result = null,
            lastDelta = null,
            steps = 0,
            potCents = 0L
        )

    fun rollStep(random: Random = Random.Default): Int = random.nextInt(1, 11)

    fun advance(state: Blackjack21State, delta: Int): Blackjack21State {
        require(state.phase == BjPhase.PLAYING) { "非進行中" }
        require(delta in 1..10) { "步長須為 1..10" }
        val nextScore = state.score + delta
        val nextSteps = state.steps + 1
        val nextPot = nextSteps * GamePrizes.BJ_CONSOLATION_STEP_CENTS
        return when {
            nextScore > 21 -> state.copy(
                score = nextScore,
                phase = BjPhase.RESULT,
                result = BjResult.BUST,
                lastDelta = delta,
                steps = nextSteps,
                potCents = nextPot // 爆牌沒收，結算時不發
            )
            nextScore == 21 -> state.copy(
                score = 21,
                phase = BjPhase.RESULT,
                result = BjResult.WIN_21,
                lastDelta = delta,
                steps = nextSteps,
                potCents = nextPot // 只拿大獎，安慰不加發
            )
            else -> state.copy(
                score = nextScore,
                lastDelta = delta,
                steps = nextSteps,
                potCents = nextPot
            )
        }
    }

    fun stand(state: Blackjack21State): Blackjack21State {
        require(state.phase == BjPhase.PLAYING) { "非進行中" }
        require(state.score > 0) { "尚未推進" }
        val result = when {
            state.score > state.dealer && state.score < 21 -> BjResult.WIN_BEAT
            else -> BjResult.LOSE
        }
        return state.copy(phase = BjPhase.RESULT, result = result)
    }
}
