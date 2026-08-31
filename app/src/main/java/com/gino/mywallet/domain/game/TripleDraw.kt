package com.gino.mywallet.domain.game

import kotlin.random.Random

/** rank: 2..14（14＝A）；suit: 0♠ 1♥ 2♦ 3♣ */
data class PlayingCard(val rank: Int, val suit: Int) {
    val rankLabel: String
        get() = when (rank) {
            14 -> "A"
            13 -> "K"
            12 -> "Q"
            11 -> "J"
            else -> rank.toString()
        }
    val suitLabel: String
        get() = when (suit) {
            0 -> "♠"
            1 -> "♥"
            2 -> "♦"
            else -> "♣"
        }
    val isRed: Boolean get() = suit == 1 || suit == 2
}

enum class PokerHand(val label: String, val payoutYuan: Long) {
    HIGH_CARD("散牌", 0),
    PAIR("一對", 12),
    TWO_PAIR("兩對", 22),
    THREE("三條", 36),
    STRAIGHT("順子", 50),
    FLUSH("同花", 70),
    FULL_HOUSE("葫蘆", 100),
    FOUR("四條", 200),
    STRAIGHT_FLUSH("同花順", 400),
    ROYAL("皇家同花順", 800)
}

enum class TripleDrawPhase { IDLE, PLAYING, SETTLED }

data class TripleDrawState(
    val cards: List<PlayingCard> = emptyList(),
    /** 牌堆剩餘（發牌後固定，換牌只從這裡抽） */
    val stock: List<PlayingCard> = emptyList(),
    /** 要換掉的位置 */
    val discard: IntArray = intArrayOf(),
    val phase: TripleDrawPhase = TripleDrawPhase.IDLE,
    val hand: PokerHand = PokerHand.HIGH_CARD,
    val payoutCents: Long = 0L,
    val drawTimes: Int = 0
) {
    fun settleButtonLabel(): String {
        val yuan = payoutCents / 100
        return if (yuan > 0) "結算 · \$$yuan" else "結算 · \$0"
    }
}

object TripleDrawGame {
    const val HAND_SIZE = 5

    fun newDeck(): MutableList<PlayingCard> {
        val deck = mutableListOf<PlayingCard>()
        for (s in 0..3) {
            for (r in 2..14) deck += PlayingCard(r, s)
        }
        return deck
    }

    fun deal(random: Random = Random.Default): TripleDrawState {
        val deck = newDeck().also { it.shuffle(random) }
        val cards = deck.take(HAND_SIZE).toList()
        val stock = deck.drop(HAND_SIZE).toList()
        val hand = evaluate(cards)
        return TripleDrawState(
            cards = cards,
            stock = stock,
            discard = intArrayOf(),
            phase = TripleDrawPhase.PLAYING,
            hand = hand,
            payoutCents = hand.payoutYuan * 100,
            drawTimes = 0
        )
    }

    fun toggleDiscard(state: TripleDrawState, index: Int): TripleDrawState {
        require(state.phase == TripleDrawPhase.PLAYING)
        require(index in state.cards.indices)
        val next = state.discard.toMutableSet()
        if (index in next) next.remove(index) else next.add(index)
        return state.copy(discard = next.toIntArray())
    }

    fun redraw(state: TripleDrawState, random: Random = Random.Default): TripleDrawState {
        require(state.phase == TripleDrawPhase.PLAYING)
        require(state.discard.isNotEmpty()) { "請先點選要換的牌" }
        val need = state.discard.size
        require(state.stock.size >= need) { "牌堆不足" }
        val stock = state.stock.toMutableList()
        val cards = state.cards.toMutableList()
        state.discard.sorted().forEach { i ->
            cards[i] = stock.removeAt(0)
        }
        val hand = evaluate(cards)
        return state.copy(
            cards = cards,
            stock = stock,
            discard = intArrayOf(),
            hand = hand,
            payoutCents = hand.payoutYuan * 100,
            drawTimes = state.drawTimes + 1
        )
    }

    fun settle(state: TripleDrawState): TripleDrawState {
        require(state.phase == TripleDrawPhase.PLAYING)
        val hand = evaluate(state.cards)
        return state.copy(
            discard = intArrayOf(),
            phase = TripleDrawPhase.SETTLED,
            hand = hand,
            payoutCents = hand.payoutYuan * 100
        )
    }

    fun evaluate(cards: List<PlayingCard>): PokerHand {
        require(cards.size == HAND_SIZE)
        val ranks = cards.map { it.rank }.sorted()
        val flush = cards.map { it.suit }.distinct().size == 1
        val straight = isStraight(ranks)
        val counts = ranks.groupingBy { it }.eachCount().values.sortedDescending()
        val royal = flush && ranks == listOf(10, 11, 12, 13, 14)
        return when {
            royal -> PokerHand.ROYAL
            flush && straight -> PokerHand.STRAIGHT_FLUSH
            counts[0] == 4 -> PokerHand.FOUR
            counts[0] == 3 && counts.getOrElse(1) { 0 } == 2 -> PokerHand.FULL_HOUSE
            flush -> PokerHand.FLUSH
            straight -> PokerHand.STRAIGHT
            counts[0] == 3 -> PokerHand.THREE
            counts[0] == 2 && counts.getOrElse(1) { 0 } == 2 -> PokerHand.TWO_PAIR
            counts[0] == 2 -> PokerHand.PAIR
            else -> PokerHand.HIGH_CARD
        }
    }

    private fun isStraight(sortedRanks: List<Int>): Boolean {
        if (sortedRanks.distinct().size != 5) return false
        if (sortedRanks.last() - sortedRanks.first() == 4) return true
        return sortedRanks == listOf(2, 3, 4, 5, 14)
    }
}
