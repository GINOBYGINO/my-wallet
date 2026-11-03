package com.gino.mywallet.focus

import android.content.Context
import com.gino.mywallet.domain.FocusMode
import com.gino.mywallet.domain.FocusRules
import com.gino.mywallet.domain.ShortFocusDuration
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class FocusPhase {
    Idle,
    Running,
    Paused,
    /** 短期倒數結束，等待領獎或轉長期 */
    ShortComplete,
    /** 短期失敗（離 App／放棄），待 UI 自動結算 −30 */
    Failed,
    Settled
}

data class FocusUiState(
    val phase: FocusPhase = FocusPhase.Idle,
    val mode: FocusMode = FocusMode.Long,
    val shortDuration: ShortFocusDuration? = null,
    /** 本次主題名稱（畫面顯示；暫停時可改） */
    val topic: String = "",
    /** 進行中累積的顯示用毫秒（暫停不加） */
    val displayMs: Long = 0L,
    val cashYuan: Int = FocusRules.START_CASH_YUAN,
    /** 長期每檔加薪金額（已達成） */
    val cashSteps: List<Int> = emptyList(),
    /** 券單位數（每 30 分）；結算時依 EV 混發；短期為 0 */
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

/** 這一場專注的中繼資料（開始前由 UI 設定；放棄或結算入庫後清除） */
data class FocusSessionMeta(
    /** 主題名稱（寫入 focus_sessions.intent） */
    val intent: String,
    val category: String = "專注",
    val linkedTaskIds: List<Long> = emptyList(),
    val wallStartAt: Long
)

/**
 * 專注狀態機（由 [FocusForegroundService] 驅動）。
 * 長期：displayMs 僅在 Running 時累加；現金依 steps。
 * 短期：倒數；離 App 失敗；到點進入 ShortComplete。
 * 進行中狀態會落盤，行程被殺後可還原。
 */
object FocusSessionController {
    private val _state = MutableStateFlow(FocusUiState())
    val state: StateFlow<FocusUiState> = _state.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var segmentStartedAt: Long = 0L

    @Volatile
    private var accumulatedMs: Long = 0L

    @Volatile
    private var pendingMode: FocusMode = FocusMode.Long

    @Volatile
    private var pendingShortDuration: ShortFocusDuration? = null

    private var random: Random = Random.Default

    @Volatile
    var sessionMeta: FocusSessionMeta? = null
        private set

    fun snapshot(): FocusUiState = _state.value

    /** Application.onCreate 呼叫：掛上 Context 並嘗試還原未結束場次 */
    fun attach(context: Context) {
        appContext = context.applicationContext
        restoreIfNeeded()
    }

    /** 測試用；正式路徑用預設 Random */
    internal fun setRandomForTest(r: Random) {
        random = r
    }

    /** @return 是否還原了進行中場次 */
    fun restoreIfNeeded(): Boolean {
        if (_state.value.phase != FocusPhase.Idle) return false
        val ctx = appContext ?: return false
        val saved = FocusSessionStore.load(ctx) ?: return false
        val short = FocusSessionStore.shortDurationOf(saved.shortMinutes)
        if (saved.mode == FocusMode.Short && short == null &&
            saved.phase != FocusPhase.ShortComplete
        ) {
            FocusSessionStore.clear(ctx)
            return false
        }
        sessionMeta = FocusSessionMeta(
            intent = saved.topic,
            category = saved.category,
            linkedTaskIds = emptyList(),
            wallStartAt = saved.wallStartAt
        )
        pendingMode = saved.mode
        pendingShortDuration = short
        accumulatedMs = FocusRules.sanitizeAccumulatedMs(saved.accumulatedMs, saved.wallStartAt)
        // 被殺期間不計時；若原本在跑，從還原點繼續
        segmentStartedAt = if (saved.phase == FocusPhase.Running) {
            System.currentTimeMillis()
        } else {
            0L
        }
        val ms = accumulatedMs
        val steps = if (saved.mode == FocusMode.Long) {
            FocusRules.ensureCashSteps(saved.cashSteps, FocusRules.cashStepCount(ms), random)
                .let { ensured ->
                    // 已存檔的 steps 優先（保留隨機結果）
                    if (saved.cashSteps.size >= FocusRules.cashStepCount(ms)) {
                        saved.cashSteps.take(FocusRules.cashStepCount(ms))
                    } else {
                        ensured
                    }
                }
        } else {
            emptyList()
        }
        _state.value = FocusUiState(
            phase = saved.phase,
            mode = saved.mode,
            shortDuration = short,
            topic = saved.topic,
            displayMs = ms,
            cashYuan = when (saved.mode) {
                FocusMode.Long -> FocusRules.longCashYuan(steps)
                FocusMode.Short -> FocusRules.SHORT_STAKE_YUAN
            },
            cashSteps = steps,
            tickets = if (saved.mode == FocusMode.Long) FocusRules.ticketUnits(ms) else 0,
            ticketEvYuan = if (saved.mode == FocusMode.Long) FocusRules.ticketEvYuan(ms) else 0,
            rewardCapped = FocusRules.rewardMinutes(ms) >= FocusRules.MAX_REWARD_MINUTES,
            leftAppPaused = saved.leftAppPaused,
            ringSeed = saved.ringSeed,
            litCount = when (saved.mode) {
                FocusMode.Long -> FocusRules.litCount(ms)
                FocusMode.Short -> FocusRules.shortLitCount(
                    ms,
                    short?.targetMs ?: ms
                )
            }
        )
        return true
    }

    fun hasActiveSession(): Boolean {
        val p = _state.value.phase
        return p == FocusPhase.Running ||
            p == FocusPhase.Paused ||
            p == FocusPhase.ShortComplete
    }

    /** 在 startFresh 之前呼叫；主題會跟著這場專注直到結算或放棄 */
    fun setSessionMeta(topic: String) {
        sessionMeta = FocusSessionMeta(
            intent = topic.trim(),
            category = "專注",
            linkedTaskIds = emptyList(),
            wallStartAt = System.currentTimeMillis()
        )
    }

    /** 暫停／短期完成等待時可改主題 */
    fun updateTopic(topic: String) {
        val cur = sessionMeta ?: return
        sessionMeta = cur.copy(intent = topic)
        _state.update { it.copy(topic = topic) }
        persist()
    }

    /** 在 [FocusForegroundService.start] 前設定本場模式 */
    fun prepareStart(mode: FocusMode, shortDuration: ShortFocusDuration? = null) {
        pendingMode = mode
        pendingShortDuration = if (mode == FocusMode.Short) {
            requireNotNull(shortDuration) { "短期專注需指定時長" }
        } else {
            null
        }
    }

    fun startFresh() {
        val mode = pendingMode
        val shortDuration = pendingShortDuration
        val topic = sessionMeta?.intent.orEmpty()
        accumulatedMs = 0L
        segmentStartedAt = System.currentTimeMillis()
        _state.value = FocusUiState(
            phase = FocusPhase.Running,
            mode = mode,
            shortDuration = shortDuration,
            topic = topic,
            displayMs = 0L,
            cashYuan = if (mode == FocusMode.Short) {
                FocusRules.SHORT_STAKE_YUAN
            } else {
                FocusRules.START_CASH_YUAN
            },
            cashSteps = emptyList(),
            tickets = 0,
            ticketEvYuan = 0,
            ringSeed = (System.currentTimeMillis() % 1000).toInt(),
            litCount = 0
        )
        persist()
    }

    fun pause(leftApp: Boolean = false) {
        val cur = _state.value
        if (cur.mode != FocusMode.Long) return
        if (cur.phase != FocusPhase.Running) return
        // segmentStartedAt==0 時不可用 now−0，否則會把 wall-clock 寫進累積時間
        accumulatedMs = clampAccumulated(accumulatedMs + segmentElapsedMs())
        segmentStartedAt = 0L
        publishLong(FocusPhase.Paused, accumulatedMs, leftApp)
        persist()
    }

    fun resume() {
        val cur = _state.value
        if (cur.mode != FocusMode.Long) return
        if (cur.phase != FocusPhase.Paused) return
        segmentStartedAt = System.currentTimeMillis()
        publishLong(FocusPhase.Running, accumulatedMs, leftApp = false)
        persist()
    }

    /** 每秒／輪詢呼叫：推進時間、圓點、離前景 */
    fun onTick(ourAppForeground: Boolean) {
        val cur = _state.value
        when (cur.phase) {
            FocusPhase.Running -> {
                if (cur.mode == FocusMode.Short) {
                    tickShort(cur, ourAppForeground)
                } else {
                    if (!ourAppForeground) {
                        pause(leftApp = true)
                        return
                    }
                    if (segmentStartedAt <= 0L) {
                        // 狀態損壞：補上區段起點，避免顯示／落盤變成時間戳
                        segmentStartedAt = System.currentTimeMillis()
                    }
                    val display = clampAccumulated(accumulatedMs + segmentElapsedMs())
                    publishLong(FocusPhase.Running, display, leftApp = false)
                    // 跑太久也定期落盤，降低被殺損失
                    if (display / 15_000L != cur.displayMs / 15_000L) {
                        persist()
                    }
                }
            }
            FocusPhase.Paused -> {
                // 仍需手動按繼續；不自動 resume
            }
            else -> Unit
        }
    }

    private fun tickShort(cur: FocusUiState, ourAppForeground: Boolean) {
        if (!ourAppForeground) {
            failShort(leftApp = true)
            return
        }
        val target = cur.shortDuration?.targetMs ?: return
        if (segmentStartedAt <= 0L) {
            segmentStartedAt = System.currentTimeMillis()
        }
        val display = clampAccumulated(accumulatedMs + segmentElapsedMs())
        if (display >= target) {
            accumulatedMs = target
            segmentStartedAt = 0L
            _state.value = cur.copy(
                phase = FocusPhase.ShortComplete,
                displayMs = target,
                cashYuan = FocusRules.SHORT_STAKE_YUAN,
                tickets = 0,
                ticketEvYuan = 0,
                rewardCapped = false,
                leftAppPaused = false,
                litCount = FocusRules.RING_DOTS
            )
            persist()
            return
        }
        _state.value = cur.copy(
            displayMs = display,
            cashYuan = FocusRules.SHORT_STAKE_YUAN,
            tickets = 0,
            ticketEvYuan = 0,
            leftAppPaused = false,
            litCount = FocusRules.shortLitCount(display, target)
        )
        if (display / 15_000L != cur.displayMs / 15_000L) {
            persist()
        }
    }

    /** 短期中途放棄或離 App */
    fun failShort(leftApp: Boolean = false) {
        val cur = _state.value
        if (cur.mode != FocusMode.Short) return
        if (cur.phase != FocusPhase.Running) return
        val target = cur.shortDuration?.targetMs
        val raw = clampAccumulated(accumulatedMs + segmentElapsedMs())
        val ms = if (target != null) raw.coerceAtMost(target) else raw
        accumulatedMs = ms
        segmentStartedAt = 0L
        _state.value = cur.copy(
            phase = FocusPhase.Failed,
            displayMs = ms,
            cashYuan = FocusRules.SHORT_STAKE_YUAN,
            tickets = 0,
            ticketEvYuan = 0,
            leftAppPaused = leftApp,
            lastSettledCash = FocusRules.SHORT_STAKE_YUAN,
            lastSettledTickets = 0,
            litCount = FocusRules.shortLitCount(ms, target ?: ms)
        )
        clearPersisted()
    }

    /**
     * 短期完成後放棄獎金，改套長期規則繼續。
     * 已過時間留下，並補齊長期 steps（30 分前固定 8）。
     */
    fun convertShortToLong() {
        val cur = _state.value
        require(cur.phase == FocusPhase.ShortComplete) { "僅短期完成後可轉長期" }
        val ms = cur.displayMs
        accumulatedMs = ms
        segmentStartedAt = System.currentTimeMillis()
        val steps = FocusRules.ensureCashSteps(emptyList(), FocusRules.cashStepCount(ms), random)
        pendingMode = FocusMode.Long
        pendingShortDuration = null
        _state.value = FocusUiState(
            phase = FocusPhase.Running,
            mode = FocusMode.Long,
            shortDuration = null,
            topic = cur.topic,
            displayMs = ms,
            cashYuan = FocusRules.longCashYuan(steps),
            cashSteps = steps,
            tickets = FocusRules.ticketUnits(ms),
            ticketEvYuan = FocusRules.ticketEvYuan(ms),
            rewardCapped = FocusRules.rewardMinutes(ms) >= FocusRules.MAX_REWARD_MINUTES,
            leftAppPaused = false,
            ringSeed = cur.ringSeed,
            litCount = FocusRules.litCount(ms)
        )
        persist()
    }

    /** 短期完成後領取固定獎金並結束 */
    fun claimShortReward(): Pair<Int, Int> {
        val cur = _state.value
        require(cur.phase == FocusPhase.ShortComplete) { "僅短期完成後可領獎" }
        val duration = requireNotNull(cur.shortDuration)
        val cash = duration.rewardYuan
        val ms = cur.displayMs
        accumulatedMs = ms
        segmentStartedAt = 0L
        _state.value = cur.copy(
            phase = FocusPhase.Settled,
            cashYuan = cash,
            tickets = 0,
            ticketEvYuan = 0,
            lastSettledCash = cash,
            lastSettledTickets = 0,
            leftAppPaused = false
        )
        clearPersisted()
        return cash to 0
    }

    /**
     * 結束場次並結算顯示值。
     * 短期 Failed 已帶好 −30；長期依 steps；不可在 ShortComplete 直接呼叫（應 claim 或 convert）。
     */
    fun endSession(): Pair<Int, Int> {
        val cur = _state.value
        when (cur.phase) {
            FocusPhase.Failed -> {
                _state.value = cur.copy(phase = FocusPhase.Settled)
                clearPersisted()
                return (cur.cashYuan to 0)
            }
            FocusPhase.ShortComplete -> {
                error("短期完成請先領獎或轉長期")
            }
            FocusPhase.Settled -> {
                return (cur.lastSettledCash ?: cur.cashYuan) to (cur.lastSettledTickets ?: 0)
            }
            else -> Unit
        }

        val ms = clampAccumulated(
            when (cur.phase) {
                FocusPhase.Running -> accumulatedMs + segmentElapsedMs()
                FocusPhase.Paused -> accumulatedMs
                else -> cur.displayMs
            }
        )
        val steps = if (cur.mode == FocusMode.Long) {
            FocusRules.ensureCashSteps(cur.cashSteps, FocusRules.cashStepCount(ms), random)
        } else {
            emptyList()
        }
        val cash = if (cur.mode == FocusMode.Long) {
            FocusRules.longCashYuan(steps)
        } else {
            FocusRules.SHORT_STAKE_YUAN
        }
        val tickets = if (cur.mode == FocusMode.Long) FocusRules.ticketUnits(ms) else 0
        accumulatedMs = ms
        segmentStartedAt = 0L
        _state.value = cur.copy(
            phase = FocusPhase.Settled,
            displayMs = ms,
            cashYuan = cash,
            cashSteps = steps,
            tickets = tickets,
            ticketEvYuan = if (cur.mode == FocusMode.Long) FocusRules.ticketEvYuan(ms) else 0,
            rewardCapped = FocusRules.rewardMinutes(ms) >= FocusRules.MAX_REWARD_MINUTES,
            lastSettledCash = cash,
            lastSettledTickets = tickets,
            leftAppPaused = false,
            litCount = if (cur.mode == FocusMode.Long) {
                FocusRules.litCount(ms)
            } else {
                FocusRules.shortLitCount(ms, cur.shortDuration?.targetMs ?: ms)
            }
        )
        clearPersisted()
        return cash to tickets
    }

    fun resetIdle() {
        accumulatedMs = 0L
        segmentStartedAt = 0L
        sessionMeta = null
        pendingMode = FocusMode.Long
        pendingShortDuration = null
        _state.value = FocusUiState()
        clearPersisted()
    }

    /** 目前區段已過毫秒；未開始（≤0）則為 0，避免 now−0 變成時間戳 */
    private fun segmentElapsedMs(now: Long = System.currentTimeMillis()): Long {
        val start = segmentStartedAt
        if (start <= 0L) return 0L
        return (now - start).coerceAtLeast(0L)
    }

    private fun clampAccumulated(ms: Long): Long =
        FocusRules.sanitizeAccumulatedMs(ms, sessionMeta?.wallStartAt ?: 0L)

    private fun currentAccumulatedForPersist(): Long {
        val cur = _state.value
        val raw = when (cur.phase) {
            FocusPhase.Running -> accumulatedMs + segmentElapsedMs()
            else -> accumulatedMs.coerceAtLeast(cur.displayMs)
        }
        return clampAccumulated(raw)
    }

    private fun persist() {
        val ctx = appContext ?: return
        val cur = _state.value
        val meta = sessionMeta
        FocusSessionStore.save(
            ctx,
            FocusSessionStore.Snapshot(
                phase = cur.phase,
                mode = cur.mode,
                shortMinutes = cur.shortDuration?.minutes,
                topic = cur.topic.ifBlank { meta?.intent.orEmpty() },
                category = meta?.category ?: "專注",
                wallStartAt = meta?.wallStartAt ?: System.currentTimeMillis(),
                accumulatedMs = currentAccumulatedForPersist(),
                cashSteps = cur.cashSteps,
                ringSeed = cur.ringSeed,
                leftAppPaused = cur.leftAppPaused
            )
        )
    }

    private fun clearPersisted() {
        appContext?.let { FocusSessionStore.clear(it) }
    }

    private fun publishLong(
        phase: FocusPhase,
        displayMs: Long,
        leftApp: Boolean
    ) {
        _state.update { cur ->
            val steps = FocusRules.ensureCashSteps(
                cur.cashSteps,
                FocusRules.cashStepCount(displayMs),
                random
            )
            cur.copy(
                phase = phase,
                mode = FocusMode.Long,
                displayMs = displayMs,
                cashYuan = FocusRules.longCashYuan(steps),
                cashSteps = steps,
                tickets = FocusRules.ticketUnits(displayMs),
                ticketEvYuan = FocusRules.ticketEvYuan(displayMs),
                rewardCapped = FocusRules.rewardMinutes(displayMs) >= FocusRules.MAX_REWARD_MINUTES,
                leftAppPaused = leftApp,
                litCount = FocusRules.litCount(displayMs)
            )
        }
    }
}
