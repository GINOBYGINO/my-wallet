package com.gino.mywallet.domain

import com.gino.mywallet.domain.game.GamePrizes

/** 打卡累積領獎：任務數／專注時長里程碑 */
object MilestoneTrack {
    const val TASK_STEP = 50
    const val FOCUS_STEP_MS = 24L * 60 * 60 * 1000
    const val REWARD_EV_YUAN = GamePrizes.MILESTONE_EV_YUAN

    fun claimableTaskCount(doneTasks: Int, redeemedTasks: Int): Int {
        val pending = (doneTasks - redeemedTasks).coerceAtLeast(0)
        return pending / TASK_STEP
    }

    fun claimableFocusCount(focusMs: Long, redeemedMs: Long): Int {
        val pending = (focusMs - redeemedMs).coerceAtLeast(0L)
        return (pending / FOCUS_STEP_MS).toInt()
    }

    fun taskProgressInStep(doneTasks: Int, redeemedTasks: Int): Int {
        val pending = (doneTasks - redeemedTasks).coerceAtLeast(0)
        return pending % TASK_STEP
    }

    fun focusProgressInStepMs(focusMs: Long, redeemedMs: Long): Long {
        val pending = (focusMs - redeemedMs).coerceAtLeast(0L)
        return pending % FOCUS_STEP_MS
    }

    fun afterClaimTasks(redeemedTasks: Int, claimCount: Int): Int =
        redeemedTasks + claimCount.coerceAtLeast(0) * TASK_STEP

    fun afterClaimFocusMs(redeemedMs: Long, claimCount: Int): Long =
        redeemedMs + claimCount.coerceAtLeast(0) * FOCUS_STEP_MS
}

data class MilestoneSnapshot(
    val doneTasks: Int,
    val redeemedTasks: Int,
    val claimableTasks: Int,
    val taskProgress: Int,
    val focusMs: Long,
    val redeemedFocusMs: Long,
    val claimableFocus: Int,
    val focusProgressMs: Long
)
