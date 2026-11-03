package com.gino.mywallet.domain.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DailyWordTest {
    private val zone = ZoneId.of("Asia/Taipei")

    private fun ms(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zone).toInstant().toEpochMilli()

    @Test
    fun periodBeforeNoonUsesYesterday() {
        val before = DailyWord.periodId(ms(2026, 9, 19, 11, 59), zone)
        val after = DailyWord.periodId(ms(2026, 9, 19, 12, 0), zone)
        assertEquals("2026-09-18", before)
        assertEquals("2026-09-19", after)
    }

    @Test
    fun nextResetAtPointsToNoon() {
        val before = DailyWord.nextResetAt(ms(2026, 9, 19, 11, 0), zone)
        assertEquals(ms(2026, 9, 19, 12, 0), before)
        val after = DailyWord.nextResetAt(ms(2026, 9, 19, 12, 0), zone)
        assertEquals(ms(2026, 9, 20, 12, 0), after)
    }

    @Test
    fun evaluateMarksCorrectPresentAbsent() {
        val marks = DailyWord.evaluate("PAPAL", "APPLE")
        assertEquals(
            listOf(
                LetterMark.PRESENT,
                LetterMark.PRESENT,
                LetterMark.CORRECT,
                LetterMark.ABSENT,
                LetterMark.PRESENT
            ),
            marks
        )
    }

    @Test
    fun dictionaryLoadedWithTiersAndAllowedSize() {
        assertTrue(DailyWord.ANSWERS.size >= 100)
        assertTrue(DailyWord.ALLOWED.size >= 10000)
        assertTrue(DailyWord.isValidGuess("SOLAR"))
        assertTrue(DailyWord.isValidGuess("UNITE"))
        assertTrue(DailyWord.isValidGuess("solar"))
        val solar = DailyWord.lookupAnswer("SOLAR")
        assertTrue(solar != null)
        assertTrue(solar!!.zh.isNotBlank())
    }

    @Test
    fun newRoundCarriesPrizeAndZh() {
        val state = DailyWord.newRound("2026-09-19")
        assertEquals(5, state.answer.length)
        assertTrue(state.answerZh.isNotBlank())
        assertTrue(state.prizeYuan in setOf(5, 8, 15))
        assertTrue(state.tier in setOf("EASY", "NORMAL", "HARD"))
    }

    @Test
    fun tierPrizeTable() {
        assertEquals(5, WordTier.EASY.prizeYuan())
        assertEquals(8, WordTier.NORMAL.prizeYuan())
        assertEquals(15, WordTier.HARD.prizeYuan())
    }

    @Test
    fun submitGuessWinsAndRejectsInvalid() {
        val period = "2026-09-19"
        var state = DailyWord.newRound(period)
        val answer = state.answer
        assertTrue(DailyWord.isValidGuess(answer))
        assertFalse(DailyWord.isValidGuess("AAAAA"))
        state = DailyWord.submitGuess(state, answer)
        assertEquals(DailyWordPhase.WON, state.phase)
    }

    @Test
    fun sixWrongGuessesLose() {
        var state = DailyWord.newRound("test-period")
        val answer = state.answer
        val filler = DailyWord.ALLOWED.first { it != answer && it.length == 5 }
        repeat(DailyWord.MAX_GUESSES) {
            if (state.phase == DailyWordPhase.PLAYING) {
                state = DailyWord.submitGuess(state, filler)
            }
        }
        assertEquals(DailyWordPhase.LOST, state.phase)
    }
}
