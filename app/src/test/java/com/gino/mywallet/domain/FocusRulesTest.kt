package com.gino.mywallet.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusRulesTest {
    @Test
    fun cashStepCount_every4Minutes() {
        assertEquals(0, FocusRules.cashStepCount(3 * 60_000L))
        assertEquals(1, FocusRules.cashStepCount(4 * 60_000L))
        assertEquals(2, FocusRules.cashStepCount(10 * 60_000L))
        assertEquals(7, FocusRules.cashStepCount(30 * 60_000L))
        assertEquals(8, FocusRules.cashStepCount(32 * 60_000L))
    }

    @Test
    fun fixedSteps_until30Minutes() {
        // 4～28 分檔固定；32 分檔改隨機
        assertTrue(FocusRules.isFixedCashStep(0)) // 4
        assertTrue(FocusRules.isFixedCashStep(6)) // 28
        assertFalse(FocusRules.isFixedCashStep(7)) // 32
    }

    @Test
    fun longCash_at10Minutes_isMinus34() {
        val steps = FocusRules.ensureCashSteps(emptyList(), FocusRules.cashStepCount(10 * 60_000L))
        assertEquals(listOf(8, 8), steps)
        assertEquals(-34, FocusRules.longCashYuan(steps))
    }

    @Test
    fun longCash_after30_usesRandom10to12() {
        val r = Random(1)
        val steps = FocusRules.ensureCashSteps(
            emptyList(),
            FocusRules.cashStepCount(32 * 60_000L),
            r
        )
        assertEquals(8, steps.size)
        assertTrue(steps.take(7).all { it == 8 })
        assertTrue(steps[7] in 10..12)
    }

    @Test
    fun shortRewards() {
        assertEquals(20, FocusRules.shortCashYuan(ShortFocusDuration.M10, completed = true))
        assertEquals(40, FocusRules.shortCashYuan(ShortFocusDuration.M20, completed = true))
        assertEquals(60, FocusRules.shortCashYuan(ShortFocusDuration.M30, completed = true))
        assertEquals(-30, FocusRules.shortCashYuan(ShortFocusDuration.M10, completed = false))
    }

    @Test
    fun sanitizeAccumulatedMs_keepsPlausible() {
        assertEquals(90 * 60_000L, FocusRules.sanitizeAccumulatedMs(90 * 60_000L))
        assertEquals(0L, FocusRules.sanitizeAccumulatedMs(0L))
    }

    @Test
    fun sanitizeAccumulatedMs_rejectsWallClockLeak() {
        val now = 1_793_107_991_613L
        val wall = now - 2 * 60 * 60_000L
        val leaked = now // pause 時 segmentStartedAt==0 會寫入這種值
        assertEquals(
            2 * 60 * 60_000L,
            FocusRules.sanitizeAccumulatedMs(leaked, wallStartAt = wall, nowMs = now)
        )
        assertEquals(
            0L,
            FocusRules.sanitizeAccumulatedMs(leaked, wallStartAt = 0L, nowMs = now)
        )
    }
}
