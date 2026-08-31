package com.gino.mywallet.focus

import com.gino.mywallet.domain.FocusRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class FocusPhase {
    Idle,
    Running,
    Paused,
    Settled
}

data class FocusUiState(
    val phase: FocusPhase = FocusPhase.Idle,
    /** 進行中累積的顯示用毫秒（暫停不加） */
    val displayMs: Long = 0L,
    val cashYuan: Int = FocusRules.START_CASH_YUAN,
    /** 券單位數（每 30 分）；結算時依 EV 混發 */
    val tickets: Int = 0,
    val ticketEvYuan: Int = 0,
    val rewardCapped: Boolean = false,
    val leftAppPaused: Boolean = false,
    val lastSettledCash: Int? = null,
    val lastSettledTickets: Int? = null,
    val ringSeed: Int = 0,
    /** 目前點亮的圓點數 0～16 */
    val litCount: Int = 0
)

/**
 * 專注狀態機（由 [FocusForegroundService] 驅動）。
 * displayMs 僅在 Running 時累加；獎勵依 min(displayMs, 120min) 計算。
 */
object FocusSessionController {
    private val _state = MutableStateFlow(FocusUiState())
    val state: StateFlow<FocusUiState> = _state.asStateFlow()

    @Volatile
    private var segmentStartedAt: Long = 0L

    @Volatile
    private var accumulatedMs: Long = 0L

    fun snapshot(): FocusUiState = _state.value

    fun startFresh() {
        accumulatedMs = 0L
        segmentStartedAt = System.currentTimeMillis()
        _state.value = FocusUiState(
            phase = FocusPhase.Running,
            displayMs = 0L,
            cashYuan = FocusRules.cashYuan(0),
            tickets = 0,
            ringSeed = (System.currentTimeMillis() % 1000).toInt(),
            litCount = 0
        )
    }

    fun pause(leftApp: Boolean = false) {
        val cur = _state.value
        if (cur.phase != FocusPhase.Running) return
        accumulatedMs += System.currentTimeMillis() - segmentStartedAt
        publish(FocusPhase.Paused, accumulatedMs, leftApp)
    }

    fun resume() {
        val cur = _state.value
        if (cur.phase != FocusPhase.Paused) return
        segmentStartedAt = System.currentTimeMillis()
        publish(FocusPhase.Running, accumulatedMs, leftApp = false)
    }

    /** 每秒／輪詢呼叫：推進時間、圓點、離前景暫停 */
    fun onTick(ourAppForeground: Boolean) {
        val cur = _state.value
        when (cur.phase) {
            FocusPhase.Running -> {
                if (!ourAppForeground) {
                    pause(leftApp = true)
                    return
                }
                val now = System.currentTimeMillis()
                val display = accumulatedMs + (now - segmentStartedAt)
                publish(FocusPhase.Running, display, leftApp = false)
            }
            FocusPhase.Paused -> {
                // 仍需手動按繼續；不自動 resume
            }
            else -> Unit
        }
    }

    fun endSession(): Pair<Int, Int> {
        val cur = _state.value
        val ms = when (cur.phase) {
            FocusPhase.Running -> accumulatedMs + (System.currentTimeMillis() - segmentStartedAt)
            FocusPhase.Paused -> accumulatedMs
            else -> cur.displayMs
        }
        val cash = FocusRules.cashYuan(ms)
        val tickets = FocusRules.ticketUnits(ms)
        accumulatedMs = ms
        segmentStartedAt = 0L
        _state.value = cur.copy(
            phase = FocusPhase.Settled,
            displayMs = ms,
            cashYuan = cash,
            tickets = tickets,
            ticketEvYuan = FocusRules.ticketEvYuan(ms),
            rewardCapped = FocusRules.rewardMinutes(ms) >= FocusRules.MAX_REWARD_MINUTES,
            lastSettledCash = cash,
            lastSettledTickets = tickets,
            leftAppPaused = false,
            litCount = FocusRules.litCount(ms)
        )
        return cash to tickets
    }

    fun resetIdle() {
        accumulatedMs = 0L
        segmentStartedAt = 0L
        _state.value = FocusUiState()
    }

    private fun publish(
        phase: FocusPhase,
        displayMs: Long,
        leftApp: Boolean
    ) {
        _state.update {
            it.copy(
                phase = phase,
                displayMs = displayMs,
                cashYuan = FocusRules.cashYuan(displayMs),
                tickets = FocusRules.ticketUnits(displayMs),
                ticketEvYuan = FocusRules.ticketEvYuan(displayMs),
                rewardCapped = FocusRules.rewardMinutes(displayMs) >= FocusRules.MAX_REWARD_MINUTES,
                leftAppPaused = leftApp,
                litCount = FocusRules.litCount(displayMs)
            )
        }
    }
}
