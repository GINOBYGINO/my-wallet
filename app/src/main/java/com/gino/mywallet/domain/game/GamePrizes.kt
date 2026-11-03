package com.gino.mywallet.domain.game

/** 娛樂次數券 */
enum class TicketKind(val symbol: String, val label: String) {
    SLOT("🎰", "拉霸"),
    BJ("🃏", "二十一點"),
    SCRATCH("🎫", "比大小"),
    DRAW("🎴", "殺撲克"),
    LOTTO("🎟", "樂透"),
    MATCH("🍬", "消消樂"),
    MATH("🧮", "輪盤");

    fun title(): String = "$symbol $label"
}

object GamePrizes {
    /** 目標 EV（元／次） */
    const val EV_SLOT_YUAN = 10
    const val EV_BJ_YUAN = 5
    const val EV_SCRATCH_YUAN = 20
    const val EV_DRAW_YUAN = 10
    const val EV_LOTTO_YUAN = 10
    const val EV_MATCH_YUAN = 10
    /** 輪盤：底金 +3；格 +4/+4/+3/+3/×2/×1.5/結算；續轉至結算 Monte Carlo ≈7.5 → 取整 8 */
    const val EV_MATH_YUAN = 8
    /** 每日五字猜中獎勵（券 EV；依難度；EV_WORDLE 為 NORMAL 別名） */
    const val WORDLE_EASY_YUAN = 5
    const val WORDLE_NORMAL_YUAN = 8
    const val WORDLE_HARD_YUAN = 15
    const val EV_WORDLE_YUAN = WORDLE_NORMAL_YUAN

    /** 專注等「每單位」混券預算（元）。任務券級見 [ticketLevelBudgetYuan]。 */
    const val EV_PER_LEVEL_YUAN = 10
    const val MAX_TICKET_LEVEL = 9
    /** 累積里程碑每次發券預算（元） */
    const val MILESTONE_EV_YUAN = 300

    /** 二十一點：每張券 EV≈5；大獎／擊敗／安慰同步下修 */
    const val BJ_JACKPOT_CENTS = 88_00L
    const val BJ_BEAT_CENTS = 45_00L
    const val BJ_CONSOLATION_STEP_CENTS = 2_00L

    fun evYuanOf(kind: TicketKind): Int = when (kind) {
        TicketKind.SLOT -> EV_SLOT_YUAN
        TicketKind.BJ -> EV_BJ_YUAN
        TicketKind.SCRATCH -> EV_SCRATCH_YUAN
        TicketKind.DRAW -> EV_DRAW_YUAN
        TicketKind.LOTTO -> EV_LOTTO_YUAN
        TicketKind.MATCH -> EV_MATCH_YUAN
        TicketKind.MATH -> EV_MATH_YUAN
    }

    fun <T> pickWeighted(items: List<T>, weightOf: (T) -> Int): T {
        val total = items.sumOf(weightOf)
        require(total > 0) { "權重總和須 > 0" }
        var r = kotlin.random.Random.nextInt(total)
        return items.first {
            r -= weightOf(it)
            r < 0
        }
    }

    /** 混券權重：多發 🃏／🎫；樂透／拉霸／消消／輪盤同權 */
    fun mixWeightOf(kind: TicketKind): Int = when (kind) {
        TicketKind.BJ -> 5
        TicketKind.SCRATCH -> 3
        TicketKind.DRAW -> 3
        TicketKind.SLOT -> 2
        TicketKind.LOTTO -> 2
        TicketKind.MATCH -> 2
        TicketKind.MATH -> 2
    }

    fun expandTicketLevel(level: Int): Map<TicketKind, Int> =
        expandEvBudget(ticketLevelBudgetYuan(level))

    /** 依 EV 預算（元）混發券；優先 🃏／🎫 */
    fun expandEvBudget(yuan: Int): Map<TicketKind, Int> {
        var remaining = yuan.coerceAtLeast(0)
        val out = TicketKind.entries.associateWith { 0 }.toMutableMap()
        val minEv = TicketKind.entries.minOf { evYuanOf(it) }
        while (remaining >= minEv) {
            val candidates = TicketKind.entries.filter { evYuanOf(it) <= remaining }
            if (candidates.isEmpty()) break
            val k = pickWeighted(candidates) { mixWeightOf(it) }
            out[k] = (out[k] ?: 0) + 1
            remaining -= evYuanOf(k)
        }
        return out.filterValues { it > 0 }
    }

    fun mergeTicketMaps(vararg maps: Map<TicketKind, Int>): Map<TicketKind, Int> {
        val out = mutableMapOf<TicketKind, Int>()
        maps.forEach { m ->
            m.forEach { (k, n) -> if (n > 0) out[k] = (out[k] ?: 0) + n }
        }
        return out.filterValues { it > 0 }
    }

    fun formatTicketMap(map: Map<TicketKind, Int>): String =
        TicketKind.entries.mapNotNull { k ->
            val n = map[k] ?: return@mapNotNull null
            if (n <= 0) null else "${k.symbol}×$n"
        }.joinToString(" ")

    /** 每一級固定 +10 元 EV。 */
    fun ticketLevelBudgetYuan(level: Int): Int =
        level.coerceIn(1, MAX_TICKET_LEVEL) * EV_PER_LEVEL_YUAN

    fun ticketTierLabel(level: Int): String = when (level.coerceIn(1, MAX_TICKET_LEVEL)) {
        in 1..3 -> "初級"
        in 4..7 -> "中級"
        else -> "高級"
    }
}

enum class EntertainmentGame(val kind: TicketKind?) {
    BLACKJACK21(TicketKind.BJ),
    SCRATCH(TicketKind.SCRATCH),
    TRIPLE_DRAW(TicketKind.DRAW),
    MATCH3(TicketKind.MATCH),
    ROULETTE(TicketKind.MATH);

    val title: String
        get() = kind!!.title()

    fun next(): EntertainmentGame {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}
