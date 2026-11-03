package com.gino.mywallet.domain.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LottoGameTest {
    private val zone = ZoneId.of("Asia/Taipei")

    private fun ms(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zone).toInstant().toEpochMilli()

    @Test
    fun nextDrawBeforeEighteenIsEighteen() {
        val now = ms(2026, 9, 19, 12, 0)
        assertEquals(ms(2026, 9, 19, 18, 0), LottoGame.nextDrawAt(now, zone))
    }

    @Test
    fun nextDrawAtEighteenIsNextDay() {
        val now = ms(2026, 9, 19, 18, 0)
        assertEquals(ms(2026, 9, 20, 18, 0), LottoGame.nextDrawAt(now, zone))
    }

    @Test
    fun nextDrawAfterEighteenIsNextDay() {
        val now = ms(2026, 9, 19, 20, 0)
        assertEquals(ms(2026, 9, 20, 18, 0), LottoGame.nextDrawAt(now, zone))
    }

    @Test
    fun dueDrawAtOnlyWhenPast() {
        val draw = ms(2026, 9, 19, 18, 0)
        assertNull(LottoGame.dueDrawAt(draw, ms(2026, 9, 19, 17, 59)))
        assertEquals(draw, LottoGame.dueDrawAt(draw, ms(2026, 9, 19, 18, 0)))
        assertNull(LottoGame.dueDrawAt(0L, ms(2026, 9, 19, 18, 0)))
    }

    @Test
    fun normalizePickRequiresThreeDigits() {
        assertNull(LottoGame.normalizePick("42"))
        assertNull(LottoGame.normalizePick("4"))
        assertEquals("042", LottoGame.normalizePick("042"))
        assertEquals("999", LottoGame.normalizePick("999a"))
        assertNull(LottoGame.normalizePick("12"))
        assertEquals("007", LottoGame.normalizePick("007"))
    }

    @Test
    fun positionalTicketEv() {
        assertEquals(3, LottoGame.matchCount("123", "123"))
        assertEquals(LottoGame.PRIZE_3_EV_YUAN, LottoGame.resolvePick("123", "123").ticketEvYuan)
        assertEquals(2, LottoGame.matchCount("123", "129"))
        assertEquals(LottoGame.PRIZE_2_EV_YUAN, LottoGame.resolvePick("123", "129").ticketEvYuan)
        assertEquals(1, LottoGame.matchCount("123", "145"))
        assertEquals(LottoGame.PRIZE_1_EV_YUAN, LottoGame.resolvePick("123", "145").ticketEvYuan)
        assertEquals(0, LottoGame.matchCount("123", "456"))
        assertEquals(0, LottoGame.resolvePick("123", "456").ticketEvYuan)
    }

    @Test
    fun zeroMatchHasNoPrize() {
        assertEquals(0, LottoGame.PRIZE_0_EV_YUAN)
        assertTrue(LottoGame.resolvePick("123", "456").cashCents == 0L)
    }
}
