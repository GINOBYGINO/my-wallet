package com.gino.mywallet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gino.mywallet.MyWalletApp
import com.gino.mywallet.data.entity.AllocationRuleEntity
import com.gino.mywallet.data.entity.AppMetaEntity
import com.gino.mywallet.data.entity.CharityStatus
import com.gino.mywallet.data.entity.CalendarEventEntity
import com.gino.mywallet.data.entity.CourseEntity
import com.gino.mywallet.data.entity.LoanEntity
import com.gino.mywallet.data.entity.LotteryStateEntity
import com.gino.mywallet.data.entity.MetaKeys
import com.gino.mywallet.data.entity.PassAdvanceResult
import com.gino.mywallet.data.entity.PassSeasonEntity
import com.gino.mywallet.data.entity.PassTierEntity
import com.gino.mywallet.data.entity.RepeatRule
import com.gino.mywallet.data.entity.RewardItem
import com.gino.mywallet.data.entity.SlotDrawResult
import com.gino.mywallet.data.entity.TaskEntity
import com.gino.mywallet.data.entity.TransactionEntity
import com.gino.mywallet.data.entity.WalletCodes
import com.gino.mywallet.data.entity.WalletEntity
import com.gino.mywallet.data.entity.WalletRateEntity
import com.gino.mywallet.domain.game.BjPhase
import com.gino.mywallet.domain.game.BjResult
import com.gino.mywallet.domain.game.Blackjack21State
import com.gino.mywallet.domain.game.EntertainmentGame
import com.gino.mywallet.domain.game.HiLoGame
import com.gino.mywallet.domain.game.HiLoOutcome
import com.gino.mywallet.domain.game.HiLoPhase
import com.gino.mywallet.domain.game.HiLoPick
import com.gino.mywallet.domain.game.HiLoState
import com.gino.mywallet.domain.game.PlayingCard
import com.gino.mywallet.domain.game.PokerHand
import com.gino.mywallet.domain.game.TripleDrawPhase
import com.gino.mywallet.domain.game.TripleDrawState
import com.gino.mywallet.domain.command.CommandExecutor
import com.gino.mywallet.domain.command.CommandParser
import com.gino.mywallet.domain.command.CommandPlan
import com.gino.mywallet.domain.command.CommandRunResult
import com.gino.mywallet.domain.command.LlmCommandPrompt
import com.gino.mywallet.domain.command.WalletCommand
import com.gino.mywallet.focus.FocusForegroundService
import com.gino.mywallet.focus.FocusSessionController
import com.gino.mywallet.notify.NotificationScheduler
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiMessage(val text: String, val id: Long = System.currentTimeMillis())

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val db = MyWalletApp.instance.database
    private val engine get() = MyWalletApp.instance.engine

    val wallets: StateFlow<List<WalletEntity>> =
        db.walletDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val walletRates: StateFlow<List<WalletRateEntity>> =
        db.walletRateDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allocation: StateFlow<List<AllocationRuleEntity>> =
        db.allocationDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val loans: StateFlow<List<LoanEntity>> =
        db.loanDao().observeLoans().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tasks: StateFlow<List<TaskEntity>> =
        db.taskDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val lotteryState: StateFlow<LotteryStateEntity?> =
        db.lotteryDao().observeState().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val season: StateFlow<PassSeasonEntity?> =
        db.passDao().observeActiveSeason().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val tiers: StateFlow<List<PassTierEntity>> =
        season.flatMapLatest { s ->
            if (s == null) flowOf(emptyList()) else db.passDao().observeTiers(s.id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentTx: StateFlow<List<TransactionEntity>> =
        db.transactionDao().observeRecent(40).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val courses: StateFlow<List<CourseEntity>> =
        db.courseDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val calendarEvents: StateFlow<List<CalendarEventEntity>> =
        db.calendarEventDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _notificationsEnabled = MutableStateFlow(true)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message

    private val _lastDraw = MutableStateFlow<SlotDrawResult?>(null)
    val lastDraw: StateFlow<SlotDrawResult?> = _lastDraw

    private val _passClaimReveal = MutableStateFlow<PassAdvanceResult?>(null)
    val passClaimReveal: StateFlow<PassAdvanceResult?> = _passClaimReveal.asStateFlow()

    private val _drawing = MutableStateFlow(false)
    val drawing: StateFlow<Boolean> = _drawing

    private val _entertainmentGame = MutableStateFlow(EntertainmentGame.SLOT)
    val entertainmentGame: StateFlow<EntertainmentGame> = _entertainmentGame.asStateFlow()

    private val _bjState = MutableStateFlow(Blackjack21State())
    val bjState: StateFlow<Blackjack21State> = _bjState.asStateFlow()

    private val _bjBusy = MutableStateFlow(false)
    val bjBusy: StateFlow<Boolean> = _bjBusy.asStateFlow()

    private val _hiLoState = MutableStateFlow(HiLoState())
    val hiLoState: StateFlow<HiLoState> = _hiLoState.asStateFlow()

    private val _hiLoBusy = MutableStateFlow(false)
    val hiLoBusy: StateFlow<Boolean> = _hiLoBusy.asStateFlow()

    private val _tripleDrawState = MutableStateFlow(TripleDrawState())
    val tripleDrawState: StateFlow<TripleDrawState> = _tripleDrawState.asStateFlow()

    private val _tripleDrawBusy = MutableStateFlow(false)
    val tripleDrawBusy: StateFlow<Boolean> = _tripleDrawBusy.asStateFlow()

    private val _exportText = MutableStateFlow<String?>(null)
    val exportText: StateFlow<String?> = _exportText

    private val _commandInput = MutableStateFlow("")
    val commandInput: StateFlow<String> = _commandInput.asStateFlow()

    private val _commandPreview = MutableStateFlow<CommandRunResult?>(null)
    val commandPreview: StateFlow<CommandRunResult?> = _commandPreview.asStateFlow()

    private val _commandResultText = MutableStateFlow<String?>(null)
    val commandResultText: StateFlow<String?> = _commandResultText.asStateFlow()

    private val _pendingReplaceAll = MutableStateFlow<CommandPlan?>(null)
    val pendingReplaceAll: StateFlow<CommandPlan?> = _pendingReplaceAll.asStateFlow()

    private val commandParser = CommandParser()
    private val commandExecutor by lazy {
        CommandExecutor(
            engine = engine,
            taskDao = db.taskDao(),
            courseDao = db.courseDao(),
            calendarEventDao = db.calendarEventDao()
        )
    }

    private val _charity = MutableStateFlow(CharityStatus(month = "", done = false, ratePercent = 10))
    val charity: StateFlow<CharityStatus> = _charity

    private val gson = Gson()

    init {
        refreshBootstrap()
        viewModelScope.launch {
            _notificationsEnabled.value =
                db.metaDao().get(MetaKeys.NOTIFICATIONS_ENABLED)?.value != "0"
            loadEntertainmentState()
        }
    }

    fun refreshBootstrap() = run {
        engine.ensureCharitySettlement()
        engine.refreshCurrentPassRewards()
        _charity.value = engine.charityStatus()
        NotificationScheduler.rescheduleAll(getApplication())
    }

    fun setNotificationsEnabled(enabled: Boolean) = run {
        db.metaDao().upsert(AppMetaEntity(MetaKeys.NOTIFICATIONS_ENABLED, if (enabled) "1" else "0"))
        _notificationsEnabled.value = enabled
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage(if (enabled) "已開啟通知" else "已關閉通知")
    }

    fun claimCooldown() = run {
        val kind = engine.claimCooldownTicketIfNeeded()
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage(
            if (kind != null) "已領取冷卻 ${kind.symbol} ${kind.label}" else "冷卻中"
        )
    }

    fun clearMessage() { _message.value = null }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _message.value = UiMessage(e.message ?: e.toString())
            }
        }
    }

    fun incomeStandard(amountYuan: String, note: String, onDone: (() -> Unit)? = null) = run {
        val cents = parseYuan(amountYuan)
        val splits = engine.recordIncomeStandard(cents, note)
        _message.value = if (splits.isEmpty()) {
            UiMessage("入帳完成")
        } else {
            val parts = splits.entries.joinToString("、") { (id, amt) ->
                val name = wallets.value.find { it.id == id }?.name ?: id
                "$name ${formatTwd(amt)}"
            }
            UiMessage("已分配：$parts")
        }
        onDone?.let { withContext(Dispatchers.Main) { it() } }
    }

    fun transfer(from: String, to: String, amountYuan: String, note: String, onDone: (() -> Unit)? = null) = run {
        engine.transfer(from, to, parseYuan(amountYuan), note)
        _message.value = UiMessage("轉帳完成")
        onDone?.let { withContext(Dispatchers.Main) { it() } }
    }

    fun completeCharity() = run {
        engine.completeMonthlyCharity()
        _charity.value = engine.charityStatus()
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage("本月公益已完成")
    }

    fun previewRepay(principalYuan: String, lenderId: String, dueThisMonth: Boolean): Long {
        val principal = try {
            parseYuan(principalYuan)
        } catch (_: Exception) {
            return 0L
        }
        val rate = walletRates.value.find { it.walletId == lenderId }?.monthlyRateBp ?: 0
        return engine.previewRepayCents(principal, rate, dueThisMonth)
    }

    fun borrowDirect(lender: String, borrower: String, amountYuan: String, dueThisMonth: Boolean) = run {
        engine.borrowFromWallet(lender, borrower, parseYuan(amountYuan), dueThisMonth)
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage("借款成立")
    }

    fun updateRate(walletId: String, ratePercent: String, notify: Boolean = true) = run {
        engine.updateWalletRate(walletId, (ratePercent.toDouble() * 100).toInt())
        if (notify) _message.value = UiMessage("利率已更新")
    }

    fun repay(loanId: Long, amountYuan: String) = run {
        engine.repayLoan(loanId, parseYuan(amountYuan))
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage("還款完成")
    }

    fun repayFull(loanId: Long) = run {
        val loan = db.loanDao().getLoan(loanId) ?: error("借據不存在")
        engine.repayLoan(loanId, loan.remainingCents)
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage("已還清")
    }

    fun markDefault(loanId: Long) = run {
        engine.markDefault(loanId)
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage("已標記違約")
    }

    fun createTask(
        title: String,
        note: String,
        category: String,
        repeatable: Boolean,
        repeatFreq: String,
        repeatN: String,
        rewardYuan: String,
        rewardTo: String,
        ticketLevel: Int = 1,
        dueAt: Long? = null,
        repeatFirstAt: Long? = null
    ) = run {
        val rewards = mutableListOf<RewardItem>()
        val level = ticketLevel.coerceIn(1, com.gino.mywallet.domain.game.GamePrizes.MAX_TICKET_LEVEL)
        rewards += RewardItem(kind = "TICKET_LEVEL", level = level)
        if (rewardYuan.isNotBlank()) {
            rewards += RewardItem(
                kind = "TRANSFER",
                amountCents = parseYuan(rewardYuan),
                fromWalletId = WalletCodes.CENTRAL,
                toWalletId = rewardTo
            )
        }
        val ruleJson = if (repeatable) {
            val delayMs = when (repeatFreq) {
                "weekly" -> 7L * 24 * 60 * 60 * 1000
                "every_n" -> (repeatN.toIntOrNull() ?: 1).coerceAtLeast(1) * 24L * 60 * 60 * 1000
                else -> 24L * 60 * 60 * 1000
            }
            val first = repeatFirstAt ?: (System.currentTimeMillis() + delayMs)
            engine.repeatRuleToJson(
                RepeatRule(
                    freq = repeatFreq,
                    n = repeatN.toIntOrNull(),
                    nextAt = first
                )
            )
        } else ""
        db.taskDao().insert(
            TaskEntity(
                title = title,
                note = note,
                category = category,
                repeatable = repeatable,
                repeatRuleJson = ruleJson,
                rewardJson = engine.rewardsToJson(rewards),
                dueAt = dueAt
            )
        )
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage("任務已建立（券包 Lv.$level）")
    }

    fun createCalendarEvent(
        title: String,
        dateYmd: String,
        timeMinutes: Int,
        location: String,
        note: String
    ) = run {
        require(title.isNotBlank()) { "請輸入標題" }
        require(dateYmd.isNotBlank()) { "請選擇日期" }
        db.calendarEventDao().insert(
            CalendarEventEntity(
                title = title.trim(),
                dateYmd = dateYmd.trim(),
                timeMinutes = timeMinutes,
                location = location.trim(),
                note = note.trim()
            )
        )
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage("行程已新增")
    }

    fun deleteCalendarEvent(id: Long) = run {
        db.calendarEventDao().deleteById(id)
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage("行程已刪除")
    }

    fun saveCourse(
        id: Long = 0,
        title: String,
        weekday: Int,
        periodStart: Int,
        periodEnd: Int,
        location: String
    ) = run {
        require(title.isNotBlank()) { "請輸入課程名稱" }
        require(weekday in 1..7) { "請選擇星期" }
        require(periodStart in 1..12 && periodEnd in 1..12 && periodEnd >= periodStart) {
            "請輸入有效節次"
        }
        db.courseDao().upsert(
            CourseEntity(
                id = id,
                title = title.trim(),
                weekday = weekday,
                periodStart = periodStart,
                periodEnd = periodEnd,
                location = location.trim()
            )
        )
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage(if (id == 0L) "課程已新增" else "課程已更新")
    }

    fun deleteCourse(id: Long) = run {
        db.courseDao().deleteById(id)
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage("課程已刪除")
    }

    fun deleteTasks(ids: Collection<Long>) = run {
        val list = ids.filter { it > 0 }.distinct()
        require(list.isNotEmpty()) { "請選擇要刪除的任務" }
        db.taskDao().deleteByIds(list)
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage("已刪除 ${list.size} 筆任務")
    }

    fun completeTask(id: Long) = run {
        val pass = engine.completeTask(id)
        NotificationScheduler.rescheduleAll(getApplication())
        val passText = pass?.let { p ->
            when {
                p.claimedReward -> "通行證 +1 · 有獎勵可領"
                p.label.contains("休息") -> "通行證 +1 · 休息"
                else -> "通行證 +1"
            }
        }
        _message.value = UiMessage(
            listOfNotNull("任務完成", passText).joinToString(" · ")
        )
    }

    fun endFocusSession(onDone: () -> Unit) = run {
        val appCtx = getApplication<Application>()
        val (cash, _) = FocusSessionController.endSession()
        val ms = FocusSessionController.snapshot().displayMs
        val pack = engine.settleFocus(cash, ms)
        FocusForegroundService.stop(appCtx)
        FocusSessionController.resetIdle()
        NotificationScheduler.rescheduleAll(appCtx)
        val cashText = if (cash >= 0) "+$cash 元" else "$cash 元"
        val ticketText = com.gino.mywallet.domain.game.GamePrizes.formatTicketMap(pack)
            .ifBlank { "無券" }
        _message.value = UiMessage("專注結算：$cashText，$ticketText")
        withContext(Dispatchers.Main) { onDone() }
    }

    fun draw() {
        if (_drawing.value) return
        run {
            _drawing.value = true
            try {
                val won = engine.drawSlot()
                _lastDraw.value = won
                delay(6500)
            } finally {
                _drawing.value = false
            }
        }
    }

    fun cycleEntertainmentGame() {
        val next = _entertainmentGame.value.next()
        _entertainmentGame.value = next
        persistEntertainmentState()
    }

    fun bjEnsureRound() {
        // 不重開進行中／已結算的局；只在完全空白時發莊家
        if (_bjState.value.phase == BjPhase.IDLE && _bjState.value.score == 0 &&
            _bjState.value.lastDelta == null
        ) {
            _bjState.value = engine.blackjackNewRound()
            persistEntertainmentState()
        }
    }

    private fun persistEntertainmentState() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                db.metaDao().upsert(AppMetaEntity(MetaKeys.GAME_BJ_JSON, gson.toJson(_bjState.value)))
                db.metaDao().upsert(AppMetaEntity(MetaKeys.GAME_HILO_JSON, gson.toJson(_hiLoState.value)))
                db.metaDao().upsert(AppMetaEntity(MetaKeys.GAME_DRAW_JSON, gson.toJson(_tripleDrawState.value)))
                db.metaDao().upsert(AppMetaEntity(MetaKeys.GAME_SELECTED, _entertainmentGame.value.name))
            }
        }
    }

    private suspend fun loadEntertainmentState() {
        db.metaDao().get(MetaKeys.GAME_SELECTED)?.value?.let { name ->
            runCatching { EntertainmentGame.valueOf(name) }.getOrNull()?.let {
                _entertainmentGame.value = it
            }
        }
        db.metaDao().get(MetaKeys.GAME_BJ_JSON)?.value?.let { raw ->
            runCatching { gson.fromJson(raw, Blackjack21State::class.java) }.getOrNull()?.let {
                _bjState.value = it
            }
        }
        db.metaDao().get(MetaKeys.GAME_HILO_JSON)?.value?.let { raw ->
            runCatching { gson.fromJson(raw, HiLoState::class.java) }.getOrNull()?.let { loaded ->
                _hiLoState.value = if (loaded.phase == HiLoPhase.REVEALING && loaded.reveal != null) {
                    loaded.copy(phase = HiLoPhase.RESULT)
                } else {
                    loaded
                }
            }
        }
        db.metaDao().get(MetaKeys.GAME_DRAW_JSON)?.value?.let { raw ->
            parseTripleDraw(raw)?.let { _tripleDrawState.value = it }
        }
        if (_hiLoState.value.phase == HiLoPhase.IDLE) {
            _hiLoState.value = engine.hiLoNewRound()
        }
        persistEntertainmentState()
    }

    private fun parseTripleDraw(raw: String): TripleDrawState? = runCatching {
        val o = JsonParser.parseString(raw).asJsonObject
        fun cards(name: String): List<PlayingCard> =
            o.getAsJsonArray(name)?.map { el ->
                val c = el.asJsonObject
                PlayingCard(c.get("rank").asInt, c.get("suit").asInt)
            } ?: emptyList()
        TripleDrawState(
            cards = cards("cards"),
            stock = cards("stock"),
            discard = o.getAsJsonArray("discard")?.map { it.asInt }?.toIntArray() ?: intArrayOf(),
            phase = TripleDrawPhase.valueOf(o.get("phase").asString),
            hand = PokerHand.valueOf(o.get("hand").asString),
            payoutCents = o.get("payoutCents")?.asLong ?: 0L,
            drawTimes = o.get("drawTimes")?.asInt ?: 0
        )
    }.getOrNull()

    fun bjAdvance() {
        if (_bjBusy.value) return
        run {
            _bjBusy.value = true
            try {
                var cur = _bjState.value
                if (cur.phase != BjPhase.PLAYING) {
                    cur = engine.blackjackNewRound()
                    _bjState.value = cur
                    persistEntertainmentState()
                }
                val next = engine.blackjackAdvance(cur)
                _bjState.value = next
                persistEntertainmentState()
                val delta = next.lastDelta ?: 1
                val climbMs = (450L + 80L * delta)
                val extra = when (next.result) {
                    BjResult.BUST -> 750L
                    BjResult.WIN_21 -> 600L
                    else -> 0L
                }
                delay(climbMs + extra)
                when (next.result) {
                    BjResult.WIN_21 -> _message.value = UiMessage("21 點！+$${com.gino.mywallet.domain.game.GamePrizes.BJ_JACKPOT_CENTS / 100}")
                    BjResult.BUST -> _message.value = UiMessage("爆牌（${next.score}）· 沒收")
                    else -> { }
                }
            } finally {
                _bjBusy.value = false
            }
        }
    }

    fun bjStand() {
        if (_bjBusy.value) return
        val cur = _bjState.value
        if (cur.phase != BjPhase.PLAYING || cur.score <= 0) return
        run {
            _bjBusy.value = true
            try {
                val next = engine.blackjackStand(cur)
                _bjState.value = next
                persistEntertainmentState()
                delay(320)
                _message.value = UiMessage(
                    when (next.result) {
                        BjResult.WIN_BEAT -> "擊敗莊家！+\$${com.gino.mywallet.domain.game.GamePrizes.BJ_BEAT_CENTS / 100}"
                        BjResult.LOSE -> {
                            val pot = next.potCents / 100
                            if (pot > 0) "沒中 · \$$pot" else "沒中（你 ${next.score} · 莊 ${next.dealer}）"
                        }
                        else -> "結算完成"
                    }
                )
                persistEntertainmentState()
            } finally {
                _bjBusy.value = false
            }
        }
    }

    fun hiLoEnsureRound() {
        val p = _hiLoState.value.phase
        if (p == com.gino.mywallet.domain.game.HiLoPhase.IDLE ||
            p == com.gino.mywallet.domain.game.HiLoPhase.RESULT
        ) {
            // RESULT 時由「再來」手動開；IDLE 自動開
            if (p == com.gino.mywallet.domain.game.HiLoPhase.IDLE) {
                _hiLoState.value = engine.hiLoNewRound()
                persistEntertainmentState()
            }
        }
    }

    fun hiLoNewRound() = run {
        if (_hiLoBusy.value) return@run
        _hiLoState.value = engine.hiLoNewRound()
        persistEntertainmentState()
    }

    fun hiLoGuess(pick: HiLoPick) {
        if (_hiLoBusy.value) return
        val cur = _hiLoState.value
        if (cur.phase != HiLoPhase.READY) return
        run {
            _hiLoBusy.value = true
            try {
                val result = engine.hiLoGuess(cur, pick)
                _hiLoState.value = result.copy(phase = HiLoPhase.REVEALING)
                persistEntertainmentState()
                delay(1_400)
                _hiLoState.value = result
                persistEntertainmentState()
                _message.value = UiMessage(
                    when (result.outcome) {
                        HiLoOutcome.WIN -> "猜對！+\$${result.payoutCents / 100}"
                        HiLoOutcome.TIE -> "相同！+\$${HiLoGame.TIE_PRIZE_YUAN}"
                        HiLoOutcome.LOSE -> "沒中 ${result.base}→${result.reveal}"
                        null -> "結束"
                    }
                )
            } finally {
                _hiLoBusy.value = false
            }
        }
    }

    fun tripleDrawDeal() {
        if (_tripleDrawBusy.value) return
        run {
            _tripleDrawBusy.value = true
            try {
                _tripleDrawState.value = engine.tripleDrawDeal()
                persistEntertainmentState()
            } finally {
                _tripleDrawBusy.value = false
            }
        }
    }

    fun tripleDrawToggle(index: Int) {
        val cur = _tripleDrawState.value
        if (cur.phase != TripleDrawPhase.PLAYING) return
        _tripleDrawState.value = engine.tripleDrawToggle(cur, index)
        persistEntertainmentState()
    }

    fun tripleDrawRedraw() {
        if (_tripleDrawBusy.value) return
        val cur = _tripleDrawState.value
        if (cur.phase != TripleDrawPhase.PLAYING || cur.discard.isEmpty()) return
        run {
            _tripleDrawBusy.value = true
            try {
                _tripleDrawState.value = engine.tripleDrawRedraw(cur)
                persistEntertainmentState()
            } finally {
                _tripleDrawBusy.value = false
            }
        }
    }

    fun tripleDrawSettle() {
        if (_tripleDrawBusy.value) return
        val cur = _tripleDrawState.value
        if (cur.phase != TripleDrawPhase.PLAYING) return
        run {
            _tripleDrawBusy.value = true
            try {
                val next = engine.tripleDrawSettle(cur)
                _tripleDrawState.value = next
                persistEntertainmentState()
                val yuan = next.payoutCents / 100
                _message.value = UiMessage(
                    if (yuan > 0) "${next.hand.label}！+\$$yuan" else "${next.hand.label} · 沒中"
                )
            } finally {
                _tripleDrawBusy.value = false
            }
        }
    }

    fun claimPassTier(tierId: Long) = run {
        val result = engine.claimPassTier(tierId)
        _passClaimReveal.value = result
        delay(1800)
        if (_passClaimReveal.value == result) {
            _passClaimReveal.value = null
        }
    }

    fun dismissPassClaimReveal() {
        _passClaimReveal.value = null
    }

    fun balanceAdjust(walletId: String, targetYuan: String) = run {
        engine.setBalanceAdjust(walletId, parseYuanAllowZero(targetYuan))
        _message.value = UiMessage("平帳完成")
    }

    fun wipeData() = run {
        withContext(Dispatchers.IO) {
            MyWalletApp.instance.wipeAndReseed()
        }
        _charity.value = engine.charityStatus()
        _bjState.value = Blackjack21State()
        _hiLoState.value = engine.hiLoNewRound()
        _tripleDrawState.value = TripleDrawState()
        persistEntertainmentState()
        _message.value = UiMessage("已清空並重建初始資料")
    }

    fun export() = run {
        _exportText.value = engine.exportReport()
        _message.value = UiMessage("報告已產生")
    }

    fun clearExport() { _exportText.value = null }

    fun setCommandInput(text: String) {
        _commandInput.value = text
    }

    fun clearCommandConsole() {
        _commandInput.value = ""
        _commandPreview.value = null
        _commandResultText.value = null
        _pendingReplaceAll.value = null
    }

    fun applyCommands() = run {
        val plan = commandParser.parse(_commandInput.value)
        val hasReplace = plan.commands.any { it is WalletCommand.CourseReplaceAll }
        if (hasReplace) {
            _pendingReplaceAll.value = plan
            _message.value = UiMessage("含 course.replace_all，請確認後執行")
            return@run
        }
        applyPlan(plan)
    }

    fun confirmReplaceAll() = run {
        val plan = _pendingReplaceAll.value ?: error("沒有待確認的指令")
        _pendingReplaceAll.value = null
        applyPlan(plan)
    }

    fun cancelReplaceAll() {
        _pendingReplaceAll.value = null
    }

    private suspend fun applyPlan(plan: CommandPlan) {
        val forced = plan.copy(mode = "apply")
        val result = commandExecutor.run(forced, dryRun = false)
        _commandPreview.value = result
        _commandResultText.value = commandParser.formatResult(result)
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage(
            if (result.ok) "已套用 ${result.applied} 筆指令"
            else "完成：成功 ${result.applied}／失敗 ${result.failed}"
        )
    }

    fun llmSystemPrompt(): String = LlmCommandPrompt.SYSTEM
    fun llmUserTemplate(): String = LlmCommandPrompt.USER_TEMPLATE

    private fun parseYuan(text: String): Long {
        val t = text.trim().replace(",", "").replace(" ", "")
        require(t.isNotEmpty()) { "請輸入金額" }
        require('.' !in t) { "請輸入整數元" }
        val yuan = t.toLong()
        require(yuan > 0) { "金額須為正整數" }
        return yuan * 100
    }

    private fun parseYuanAllowZero(text: String): Long {
        val t = text.trim().replace(",", "").replace(" ", "")
        require(t.isNotEmpty()) { "請輸入金額" }
        require('.' !in t) { "請輸入整數元" }
        val yuan = t.toLong()
        require(yuan >= 0) { "金額不可為負" }
        return yuan * 100
    }
}

fun formatTwd(cents: Long): String {
    val neg = cents < 0
    val v = kotlin.math.abs(cents)
    val s = "%,d".format(v / 100)
    return if (neg) "-NT$$s" else "NT$$s"
}

fun bpToPercent(bp: Int): String = "%.2f%%".format(bp / 100.0)
