package com.gino.mywallet.domain

import com.gino.mywallet.domain.game.GamePrizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassTrackTest {
    @Test
    fun ticketLevelBudgetIsFlatTenPerLevel() {
        for (level in 1..9) {
            assertEquals(level * 10, GamePrizes.ticketLevelBudgetYuan(level))
        }
    }

    @Test
    fun specsHaveNoCashPrize() {
        PassTrack.specs().forEach { spec ->
            assertFalse(
                "tier ${spec.index} still has PRIZE",
                spec.rewards.any { it.kind == "PRIZE" }
            )
        }
    }

    @Test
    fun specsHaveNoTaxRelief() {
        PassTrack.specs().forEach { spec ->
            assertFalse(
                "tier ${spec.index} still has TAX_RELIEF",
                spec.rewards.any { it.kind == "TAX_RELIEF" }
            )
        }
    }

    @Test
    fun ticketBudgetCountedInBudgetYuan() {
        val rewards = listOf(
            com.gino.mywallet.data.entity.RewardItem(kind = "TICKET_BUDGET", amountCents = 3000)
        )
        assertEquals(30, PassTrack.budgetYuan(rewards))
    }

    @Test
    fun passTrackIsEightyTiersBudgetingTwoThirdsCut() {
        val track = PassTrack.specs()
        assertEquals(PassTrack.LENGTH, track.size)
        assertEquals(80, EconomyEngine.PASS_LENGTH)
        assertEquals((1..80).toList(), track.map { it.index })
        assertEquals(PassTrack.BUDGET_YUAN, PassTrack.totalBudgetYuan(track))
        assertEquals(667, PassTrack.totalBudgetYuan(track))
        assertEquals("通關", track.last().label)
        assertEquals("休息", track.first { it.index == 1 }.label)
        assertTrue(PassTrack.SPRINT_FROM <= 80)
    }
}

class MilestoneTrackTest {
    @Test
    fun taskClaimableAndCarryOver() {
        assertEquals(2, MilestoneTrack.claimableTaskCount(120, 0))
        assertEquals(20, MilestoneTrack.taskProgressInStep(120, 0))
        assertEquals(100, MilestoneTrack.afterClaimTasks(0, 2))
        assertEquals(0, MilestoneTrack.claimableTaskCount(120, 100))
        assertEquals(20, MilestoneTrack.taskProgressInStep(120, 100))
    }

    @Test
    fun focusClaimable() {
        val day = MilestoneTrack.FOCUS_STEP_MS
        assertEquals(2, MilestoneTrack.claimableFocusCount(day * 2 + 1000, 0))
        assertEquals(1, MilestoneTrack.claimableFocusCount(day * 2 + 1000, day))
        assertEquals(1000L, MilestoneTrack.focusProgressInStepMs(day * 2 + 1000, day))
        assertEquals(300, MilestoneTrack.REWARD_EV_YUAN)
        assertEquals(300, GamePrizes.MILESTONE_EV_YUAN)
    }
}
