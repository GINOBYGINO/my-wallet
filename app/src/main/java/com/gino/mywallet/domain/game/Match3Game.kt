package com.gino.mywallet.domain.game

import kotlin.random.Random

enum class Match3Phase { IDLE, PLAYING }

/** 單格落下／生成資訊（供 UI 動畫） */
data class Match3Motion(
    val toIndex: Int,
    val color: Int,
    /** 動畫起始列；新子為負（盤面上方） */
    val fromRow: Int,
    val toRow: Int,
    val col: Int
)

sealed class Match3AnimStep {
    data class Swapped(val grid: List<Int>, val a: Int, val b: Int) : Match3AnimStep()
    data class Highlight(val grid: List<Int>, val cells: Set<Int>) : Match3AnimStep()
    data class Cleared(val grid: List<Int>, val cells: Set<Int>) : Match3AnimStep()
    data class Fallen(val grid: List<Int>, val motions: List<Match3Motion>) : Match3AnimStep()
}

data class Match3Resolution(
    val steps: List<Match3AnimStep>,
    val finalGrid: List<Int>,
    val payoutCents: Long,
    val cleared: Int,
    val cascades: Int
)

/**
 * 消消樂：7×7、6 色；交換相鄰且產生消除才算有效（耗 1 券）。
 * 獎金對齊 EV≈[GamePrizes.EV_MATCH_YUAN]／有效滑動。
 */
data class Match3State(
    val phase: Match3Phase = Match3Phase.IDLE,
    val grid: List<Int> = emptyList(),
    val size: Int = Match3Game.SIZE,
    val lastPayoutCents: Long = 0L,
    val sessionCents: Long = 0L,
    val lastCleared: Int = 0,
    val lastCascades: Int = 0,
    val animating: Boolean = false
)

object Match3Game {
    const val SIZE = 7
    const val COLOR_COUNT = 6
    /** 單次有效滑動基礎：每消 1 格 */
    const val CENTS_PER_CELL = 35L
    /** 連鎖加成（第 2 段起） */
    const val CASCADE_BONUS_CENTS = 80L

    fun idle(): Match3State = Match3State()

    fun newBoard(random: Random = Random.Default): Match3State {
        var grid: MutableList<Int>
        do {
            grid = MutableList(SIZE * SIZE) { random.nextInt(COLOR_COUNT) }
            resolveNoMatches(grid, random)
        } while (findMatches(grid, SIZE).isNotEmpty())
        return Match3State(phase = Match3Phase.PLAYING, grid = grid.toList(), size = SIZE)
    }

    fun trySwap(
        state: Match3State,
        from: Int,
        to: Int,
        random: Random = Random.Default
    ): Match3Resolution? {
        require(state.phase == Match3Phase.PLAYING)
        if (state.animating) return null
        if (!areAdjacent(from, to, state.size)) return null
        val grid = state.grid.toMutableList()
        swap(grid, from, to)
        if (findMatches(grid, state.size).isEmpty()) {
            swap(grid, from, to)
            return null
        }
        val steps = mutableListOf<Match3AnimStep>()
        steps += Match3AnimStep.Swapped(grid.toList(), from, to)

        var totalCents = 0L
        var cascades = 0
        var cleared = 0
        while (true) {
            val matches = findMatches(grid, state.size)
            if (matches.isEmpty()) break
            cascades++
            cleared += matches.size
            val payout = matches.size * CENTS_PER_CELL +
                if (cascades > 1) CASCADE_BONUS_CENTS else 0L
            totalCents += payout
            steps += Match3AnimStep.Highlight(grid.toList(), matches)
            matches.forEach { grid[it] = -1 }
            steps += Match3AnimStep.Cleared(grid.toList(), matches)
            val (next, motions) = collapseAndFill(grid, state.size, random)
            for (i in grid.indices) grid[i] = next[i]
            steps += Match3AnimStep.Fallen(grid.toList(), motions)
        }
        return Match3Resolution(
            steps = steps,
            finalGrid = grid.toList(),
            payoutCents = totalCents,
            cleared = cleared,
            cascades = cascades
        )
    }

    fun applyResolution(state: Match3State, resolution: Match3Resolution): Match3State =
        state.copy(
            grid = resolution.finalGrid,
            lastPayoutCents = resolution.payoutCents,
            sessionCents = state.sessionCents + resolution.payoutCents,
            lastCleared = resolution.cleared,
            lastCascades = resolution.cascades,
            animating = false
        )

    fun areAdjacent(a: Int, b: Int, size: Int): Boolean {
        val ar = a / size
        val ac = a % size
        val br = b / size
        val bc = b % size
        return (ar == br && kotlin.math.abs(ac - bc) == 1) ||
            (ac == bc && kotlin.math.abs(ar - br) == 1)
    }

    private fun swap(grid: MutableList<Int>, a: Int, b: Int) {
        val t = grid[a]
        grid[a] = grid[b]
        grid[b] = t
    }

    fun findMatches(grid: List<Int>, size: Int): Set<Int> {
        val out = mutableSetOf<Int>()
        for (r in 0 until size) {
            var c = 0
            while (c < size) {
                val start = r * size + c
                val color = grid[start]
                var len = 1
                while (c + len < size && grid[r * size + c + len] == color) len++
                if (color >= 0 && len >= 3) {
                    for (i in 0 until len) out += r * size + c + i
                }
                c += len
            }
        }
        for (c in 0 until size) {
            var r = 0
            while (r < size) {
                val start = r * size + c
                val color = grid[start]
                var len = 1
                while (r + len < size && grid[(r + len) * size + c] == color) len++
                if (color >= 0 && len >= 3) {
                    for (i in 0 until len) out += (r + i) * size + c
                }
                r += len
            }
        }
        return out
    }

    /**
     * @return 新盤面 + 每個有色格子的移動（含新生成）
     */
    private fun collapseAndFill(
        grid: MutableList<Int>,
        size: Int,
        random: Random
    ): Pair<List<Int>, List<Match3Motion>> {
        val next = MutableList(size * size) { -1 }
        val motions = mutableListOf<Match3Motion>()
        for (c in 0 until size) {
            val surviving = mutableListOf<Pair<Int, Int>>() // color to fromRow
            for (r in size - 1 downTo 0) {
                val idx = r * size + c
                val v = grid[idx]
                if (v >= 0) surviving += v to r
            }
            var writeRow = size - 1
            for ((color, fromRow) in surviving) {
                val toIndex = writeRow * size + c
                next[toIndex] = color
                if (fromRow != writeRow) {
                    motions += Match3Motion(toIndex, color, fromRow, writeRow, c)
                }
                writeRow--
            }
            var spawnFrom = -1
            while (writeRow >= 0) {
                val color = random.nextInt(COLOR_COUNT)
                val toIndex = writeRow * size + c
                next[toIndex] = color
                motions += Match3Motion(toIndex, color, spawnFrom, writeRow, c)
                spawnFrom--
                writeRow--
            }
        }
        return next.toList() to motions
    }

    private fun resolveNoMatches(grid: MutableList<Int>, random: Random) {
        var guard = 0
        while (findMatches(grid, SIZE).isNotEmpty() && guard++ < 40) {
            findMatches(grid, SIZE).forEach { grid[it] = random.nextInt(COLOR_COUNT) }
        }
    }
}
