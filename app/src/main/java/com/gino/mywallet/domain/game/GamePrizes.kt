package com.gino.mywallet.domain.game

/** 娛樂次數券 */
enum class TicketKind(val symbol: String, val label: String) {
    SLOT("🎰", "拉霸"),
    BJ("🃏", "二十一點"),
    SCRATCH("🎫", "比大小"),
    DRAW("🎴", "殺撲克");

    fun title(): String = "$symbol $label"
}

object GamePrizes {
    /** 目標 EV（元／次） */
    const val EV_SLOT_YUAN = 10
    const val EV_BJ_YUAN = 5
    const val EV_SCRATCH_YUAN = 20
    const val EV_DRAW_YUAN = 10

    /** 任務券包：每級預算＝level × 此值（元） */
    const val EV_PER_LEVEL_YUAN = 10
    const val MAX_TICKET_LEVEL = 9

    /** 二十一點：每張券 EV≈5；大獎／擊敗／安慰同步下修 */
    const val BJ_JACKPOT_CENTS = 88_00L
    const val BJ_BEAT_CENTS = 45_00L
    const val BJ_CONSOLATION_STEP_CENTS = 2_00L

    fun evYuanOf(kind: TicketKind): Int = when (kind) {
        TicketKind.SLOT -> EV_SLOT_YUAN
        TicketKind.BJ -> EV_BJ_YUAN
        TicketKind.SCRATCH -> EV_SCRATCH_YUAN
        TicketKind.DRAW -> EV_DRAW_YUAN
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

    /** 混券權重：多發 🃏／🎫 */
    fun mixWeightOf(kind: TicketKind): Int = when (kind) {
        TicketKind.BJ -> 5
        TicketKind.SCRATCH -> 3
        TicketKind.DRAW -> 3
        TicketKind.SLOT -> 2
    }

    fun expandTicketLevel(level: Int): Map<TicketKind, Int> =
        expandEvBudget(level.coerceIn(1, MAX_TICKET_LEVEL) * EV_PER_LEVEL_YUAN)

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

    fun ticketLevelBudgetYuan(level: Int): Int =
        level.coerceIn(1, MAX_TICKET_LEVEL) * EV_PER_LEVEL_YUAN

    fun ticketTierLabel(level: Int): String = when (level.coerceIn(1, MAX_TICKET_LEVEL)) {
        in 1..3 -> "初級"
        in 4..7 -> "中級"
        else -> "高級"
    }
}

enum class EntertainmentGame(val kind: TicketKind) {
    SLOT(TicketKind.SLOT),
    BLACKJACK21(TicketKind.BJ),
    SCRATCH(TicketKind.SCRATCH),
    TRIPLE_DRAW(TicketKind.DRAW);

    val title: String get() = kind.title()

    fun next(): EntertainmentGame {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}
