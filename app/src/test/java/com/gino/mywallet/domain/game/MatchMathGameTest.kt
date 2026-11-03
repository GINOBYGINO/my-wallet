package com.gino.mywallet.domain.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RouletteGameTest {
    @Test
    fun newBoardHasSevenCellsWithExpectedKinds() {
        val state = RouletteGame.newRoundBoard(Random(1))
        assertEquals(7, state.cells.size)
        assertEquals(1, state.cells.count { it.kind == RouletteCellKind.SETTLE })
        assertEquals(1, state.cells.count { it.kind == RouletteCellKind.TIMES2 })
        assertEquals(1, state.cells.count { it.kind == RouletteCellKind.TIMES15 })
        assertEquals(2, state.cells.count { it.kind == RouletteCellKind.PLUS4 })
        assertEquals(2, state.cells.count { it.kind == RouletteCellKind.PLUS3 })
        assertEquals(RoulettePhase.READY, state.phase)
        assertEquals(0L, state.potCents)
        assertEquals(300L, RouletteGame.BASE_INJECT_CENTS)
        assertEquals(8, GamePrizes.EV_MATH_YUAN)
    }

    @Test
    fun spinInjectBaseThenPlus4() {
        val board = RouletteGame.newRoundBoard(Random(2))
        val plus4 = board.cells.indexOfFirst { it.kind == RouletteCellKind.PLUS4 }
        var state = board.copy(
            potCents = RouletteGame.BASE_INJECT_CENTS,
            ticketsThisRound = 1,
            phase = RoulettePhase.READY
        )
        state = state.copy(phase = RoulettePhase.SPINNING, spinTargetIndex = plus4)
        state = RouletteGame.resolveSpin(state)
        assertEquals(RoulettePhase.LIVE, state.phase)
        assertEquals(
            RouletteGame.BASE_INJECT_CENTS + RouletteGame.PLUS4_CENTS,
            state.potCents
        )
        assertTrue(state.cells[plus4].consumed)
        assertFalse(RouletteGame.remainingIndices(state).contains(plus4))
    }

    @Test
    fun times2DoublesPot() {
        val board = RouletteGame.newRoundBoard(Random(3))
        val idx = board.cells.indexOfFirst { it.kind == RouletteCellKind.TIMES2 }
        var state = board.copy(
            potCents = 300L,
            phase = RoulettePhase.SPINNING,
            spinTargetIndex = idx
        )
        state = RouletteGame.resolveSpin(state)
        assertEquals(600L, state.potCents)
        assertEquals(RouletteCellKind.TIMES2, state.lastKind)
    }

    @Test
    fun times15MultipliesByThreeHalves() {
        val board = RouletteGame.newRoundBoard(Random(4))
        val idx = board.cells.indexOfFirst { it.kind == RouletteCellKind.TIMES15 }
        var state = board.copy(
            potCents = 400L,
            phase = RoulettePhase.SPINNING,
            spinTargetIndex = idx
        )
        state = RouletteGame.resolveSpin(state)
        assertEquals(600L, state.potCents)
        assertEquals(RouletteCellKind.TIMES15, state.lastKind)
    }

    @Test
    fun plus3AddsThreeYuan() {
        val board = RouletteGame.newRoundBoard(Random(5))
        val idx = board.cells.indexOfFirst { it.kind == RouletteCellKind.PLUS3 }
        var state = board.copy(
            potCents = 300L,
            phase = RoulettePhase.SPINNING,
            spinTargetIndex = idx
        )
        state = RouletteGame.resolveSpin(state)
        assertEquals(600L, state.potCents)
    }

    @Test
    fun settleKeepsPot() {
        val board = RouletteGame.newRoundBoard(Random(6))
        val idx = board.cells.indexOfFirst { it.kind == RouletteCellKind.SETTLE }
        var state = board.copy(
            potCents = 1200L,
            phase = RoulettePhase.SPINNING,
            spinTargetIndex = idx,
            ticketsThisRound = 2
        )
        state = RouletteGame.resolveSpin(state)
        assertEquals(RoulettePhase.SETTLE, state.phase)
        assertEquals(1200L, state.potCents)
        assertEquals(RouletteCellKind.SETTLE, state.lastKind)
    }

    @Test
    fun noReplacementUntilCashOut() {
        var state = RouletteGame.newRoundBoard(Random(7)).copy(
            potCents = 300L,
            ticketsThisRound = 1
        )
        val first = state.cells.indexOfFirst { it.kind == RouletteCellKind.PLUS4 }
        state = state.copy(phase = RoulettePhase.SPINNING, spinTargetIndex = first)
        state = RouletteGame.resolveSpin(state)
        assertTrue(state.cells[first].consumed)

        repeat(20) {
            val spun = RouletteGame.beginSpin(state, Random(100 + it))
            assertTrue(spun.spinTargetIndex != first)
            assertFalse(state.cells[spun.spinTargetIndex].consumed)
        }
    }

    @Test
    fun cashOutBoardResets() {
        val next = RouletteGame.cashOutBoard(Random(8))
        assertEquals(RoulettePhase.READY, next.phase)
        assertEquals(0L, next.potCents)
        assertEquals(7, next.cells.size)
        assertTrue(next.cells.none { it.consumed })
    }

    @Test
    fun consumingAllNonSettleThenForcedWhenOnlySettleWouldRemainIsUnnecessary() {
        // 手工盤：7 格全 +3（無結算）— 消完最後一格應 needsForcedCashOut
        val cells = List(7) { i ->
            RouletteCell(i, RouletteCellKind.PLUS3)
        }
        var state = RouletteState(
            phase = RoulettePhase.LIVE,
            potCents = 300L,
            cells = cells,
            ticketsThisRound = 1
        )
        for (i in 0 until 7) {
            state = state.copy(
                phase = RoulettePhase.SPINNING,
                spinTargetIndex = i,
                potCents = state.potCents + RouletteGame.BASE_INJECT_CENTS
            )
            state = RouletteGame.resolveSpin(state)
        }
        assertEquals(RoulettePhase.LIVE, state.phase)
        assertTrue(state.needsForcedCashOut)
        assertTrue(RouletteGame.remainingIndices(state).isEmpty())
    }
}

class Match3GameTest {
    @Test
    fun adjacentSwapWithNoMatchReturnsNull() {
        val state = Match3Game.newBoard(Random(42))
        assertTrue(Match3Game.findMatches(state.grid, state.size).isEmpty())
        assertNotNull(state.grid)
    }

    @Test
    fun nonAdjacentRejected() {
        val state = Match3Game.newBoard(Random(7))
        assertNull(Match3Game.trySwap(state, 0, state.size * 2, Random(8)))
    }

    @Test
    fun successfulSwapEmitsAnimSteps() {
        val size = Match3Game.SIZE
        val grid = MutableList(size * size) { (it % 5) }
        grid[0] = 0
        grid[1] = 2
        grid[2] = 0
        grid[3] = 0
        grid[size] = 0
        grid[size + 1] = 0
        val state = Match3State(
            phase = Match3Phase.PLAYING,
            grid = grid.toList(),
            size = size
        )
        val res = Match3Game.trySwap(state, 1, size + 1, Random(9))
        assertNotNull(res)
        assertTrue(res!!.steps.any { it is Match3AnimStep.Swapped })
        assertTrue(res.steps.any { it is Match3AnimStep.Highlight })
        assertTrue(res.steps.any { it is Match3AnimStep.Cleared })
        assertTrue(res.steps.any { it is Match3AnimStep.Fallen })
        assertTrue(res.payoutCents > 0)
        assertTrue(res.cleared >= 3)
    }
}
