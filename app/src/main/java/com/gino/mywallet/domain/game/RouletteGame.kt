package com.gino.mywallet.domain.game

import kotlin.random.Random

enum class RouletteCellKind {
    SETTLE,
    TIMES2,
    TIMES15,
    PLUS4,
    PLUS3;

    fun label(): String = when (this) {
        SETTLE -> "結算"
        TIMES2 -> "×2"
        TIMES15 -> "×1.5"
        PLUS4 -> "+4"
        PLUS3 -> "+3"
    }
}

data class RouletteCell(
    val id: Int,
    val kind: RouletteCellKind,
    val consumed: Boolean = false
)

enum class RoulettePhase { READY, SPINNING, LIVE, SETTLE }

data class RouletteState(
    val phase: RoulettePhase = RoulettePhase.READY,
    val potCents: Long = 0L,
    val cells: List<RouletteCell> = emptyList(),
    val lastLandedIndex: Int = -1,
    val lastDeltaCents: Long = 0L,
    val lastKind: RouletteCellKind? = null,
    val spinTargetIndex: Int = -1,
    val ticketsThisRound: Int = 0,
    /** 本輪非結算格皆已消耗，應手動結算 */
    val needsForcedCashOut: Boolean = false
)

/**
 * 俄羅斯輪盤：7 格無放回；每次再來注入底金後抽一格。
 * 格：+4/+4/+3/+3/×2/×1.5/結算。帳面 EV 對齊 [GamePrizes.EV_MATH_YUAN]。
 */
object RouletteGame {
    const val CELL_COUNT = 7
    /** 每次再來固定底金 $3 */
    const val BASE_INJECT_CENTS = 300L
    const val PLUS4_CENTS = 400L
    const val PLUS3_CENTS = 300L

    private val TEMPLATE: List<RouletteCellKind> = listOf(
        RouletteCellKind.SETTLE,
        RouletteCellKind.TIMES2,
        RouletteCellKind.TIMES15,
        RouletteCellKind.PLUS4,
        RouletteCellKind.PLUS4,
        RouletteCellKind.PLUS3,
        RouletteCellKind.PLUS3
    )

    fun idle(random: Random = Random.Default): RouletteState = newRoundBoard(random)

    fun newRoundBoard(random: Random = Random.Default): RouletteState {
        val kinds = TEMPLATE.shuffled(random)
        val cells = kinds.mapIndexed { i, kind -> RouletteCell(id = i, kind = kind) }
        return RouletteState(
            phase = RoulettePhase.READY,
            potCents = 0L,
            cells = cells,
            lastLandedIndex = -1,
            lastDeltaCents = 0L,
            lastKind = null,
            spinTargetIndex = -1,
            ticketsThisRound = 0,
            needsForcedCashOut = false
        )
    }

    /** 從未消耗格均勻抽目標；呼叫前應已注入底金。 */
    fun beginSpin(state: RouletteState, random: Random = Random.Default): RouletteState {
        require(state.phase != RoulettePhase.SPINNING) { "旋轉中" }
        require(!state.needsForcedCashOut) { "請先結算" }
        val available = state.cells.indices.filter { !state.cells[it].consumed }
        require(available.isNotEmpty()) { "無剩餘格" }
        val target = available[random.nextInt(available.size)]
        return state.copy(phase = RoulettePhase.SPINNING, spinTargetIndex = target)
    }

    fun resolveSpin(state: RouletteState): RouletteState {
        require(state.phase == RoulettePhase.SPINNING) { "非旋轉中" }
        val idx = state.spinTargetIndex
        require(idx in state.cells.indices) { "無效目標" }
        val cell = state.cells[idx]
        require(!cell.consumed) { "格已消耗" }
        val marked = state.cells.mapIndexed { i, c ->
            if (i == idx) c.copy(consumed = true) else c
        }
        return when (cell.kind) {
            RouletteCellKind.SETTLE -> state.copy(
                phase = RoulettePhase.SETTLE,
                potCents = state.potCents,
                cells = marked,
                lastLandedIndex = idx,
                lastDeltaCents = 0L,
                lastKind = RouletteCellKind.SETTLE,
                spinTargetIndex = -1,
                needsForcedCashOut = false
            )
            RouletteCellKind.TIMES2 -> {
                val before = state.potCents
                val after = before * 2
                liveAfter(state, marked, idx, after - before, RouletteCellKind.TIMES2, after)
            }
            RouletteCellKind.TIMES15 -> {
                val before = state.potCents
                val after = before * 3 / 2
                liveAfter(state, marked, idx, after - before, RouletteCellKind.TIMES15, after)
            }
            RouletteCellKind.PLUS4 -> liveAfter(
                state, marked, idx, PLUS4_CENTS, RouletteCellKind.PLUS4, state.potCents + PLUS4_CENTS
            )
            RouletteCellKind.PLUS3 -> liveAfter(
                state, marked, idx, PLUS3_CENTS, RouletteCellKind.PLUS3, state.potCents + PLUS3_CENTS
            )
        }
    }

    fun cashOutBoard(random: Random = Random.Default): RouletteState = newRoundBoard(random)

    fun remainingIndices(state: RouletteState): List<Int> =
        state.cells.indices.filter { !state.cells[it].consumed }

    private fun liveAfter(
        state: RouletteState,
        marked: List<RouletteCell>,
        idx: Int,
        delta: Long,
        kind: RouletteCellKind,
        pot: Long
    ): RouletteState {
        val remaining = marked.any { !it.consumed }
        return state.copy(
            phase = RoulettePhase.LIVE,
            potCents = pot,
            cells = marked,
            lastLandedIndex = idx,
            lastDeltaCents = delta,
            lastKind = kind,
            spinTargetIndex = -1,
            needsForcedCashOut = !remaining
        )
    }
}
