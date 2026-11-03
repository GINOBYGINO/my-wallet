package com.gino.mywallet.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharitySkipTest {
    @Test
    fun belowOneYuan_skips() {
        assertTrue(EconomyEngine.shouldSkipMonthlyCharity(0))
        assertTrue(EconomyEngine.shouldSkipMonthlyCharity(99))
        assertTrue(
            EconomyEngine.shouldSkipMonthlyCharity(EconomyEngine.MIN_CHARITY_DONATION_CENTS - 1)
        )
    }

    @Test
    fun atOrAboveOneYuan_doesNotSkip() {
        assertFalse(
            EconomyEngine.shouldSkipMonthlyCharity(EconomyEngine.MIN_CHARITY_DONATION_CENTS)
        )
        assertFalse(EconomyEngine.shouldSkipMonthlyCharity(274_00L))
    }
}
