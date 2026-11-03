package com.gino.mywallet.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gino.mywallet.MyWalletApp
import com.gino.mywallet.data.bot.BotSyncPrefs
import com.gino.mywallet.data.bot.GoogleDriveAuth
import com.gino.mywallet.data.bot.GoogleDriveClient
import com.gino.mywallet.data.bot.OAuthLoopbackServer
import com.gino.mywallet.data.entity.AllocationRuleEntity
import com.gino.mywallet.data.entity.AppMetaEntity
import com.gino.mywallet.data.entity.CharityStatus
import com.gino.mywallet.data.entity.CalendarEventEntity
import com.gino.mywallet.data.entity.CourseEntity
import com.gino.mywallet.data.entity.ExamEntity
import com.gino.mywallet.data.entity.ExamItemEntity
import com.gino.mywallet.data.entity.ExamItemStatus
import com.gino.mywallet.data.entity.ExamSubjectEntity
import com.gino.mywallet.data.entity.FocusSessionEntity
import com.gino.mywallet.data.entity.LoanEntity
import com.gino.mywallet.data.entity.LotteryStateEntity
import com.gino.mywallet.data.entity.MetaKeys
import com.gino.mywallet.data.entity.PassAdvanceResult
import com.gino.mywallet.data.entity.PendingPhysicalTransferEntity
import com.gino.mywallet.data.entity.PassSeasonEntity
import com.gino.mywallet.data.entity.PassTierEntity
import com.gino.mywallet.data.entity.PlanEntity
import com.gino.mywallet.data.entity.PlanIds
import com.gino.mywallet.data.entity.RepeatRule
import com.gino.mywallet.data.entity.RewardItem
import com.gino.mywallet.data.entity.SlotDrawResult
import com.gino.mywallet.data.entity.TaskEntity
import com.gino.mywallet.data.entity.TaskStatus
import com.gino.mywallet.data.entity.TransactionEntity
import com.gino.mywallet.data.entity.WalletCodes
import com.gino.mywallet.data.entity.WalletEntity
import com.gino.mywallet.data.entity.WalletRateEntity
import com.gino.mywallet.domain.bot.BotFolderSync
import com.gino.mywallet.domain.bot.BotSyncScheduler
import com.gino.mywallet.domain.command.CommandExecutor
import com.gino.mywallet.domain.command.CommandParser
import com.gino.mywallet.domain.command.CommandPlan
import com.gino.mywallet.domain.command.CommandRunResult
import com.gino.mywallet.domain.command.LlmCommandPrompt
import com.gino.mywallet.domain.command.WalletCommand
import com.gino.mywallet.domain.AcademicCalendar
import com.gino.mywallet.domain.EconomyEngine
import com.gino.mywallet.domain.ExamRules
import com.gino.mywallet.domain.SemesterConfig
import com.gino.mywallet.domain.game.BjPhase
import com.gino.mywallet.domain.game.BjResult
import com.gino.mywallet.domain.game.Blackjack21State
import com.gino.mywallet.domain.game.DailyWord
import com.gino.mywallet.domain.game.DailyWordPhase
import com.gino.mywallet.domain.game.DailyWordState
import com.gino.mywallet.domain.game.EntertainmentGame
import com.gino.mywallet.domain.game.HiLoGame
import com.gino.mywallet.domain.game.HiLoOutcome
import com.gino.mywallet.domain.game.HiLoPhase
import com.gino.mywallet.domain.game.HiLoPick
import com.gino.mywallet.domain.game.HiLoState
import com.gino.mywallet.domain.game.LottoGame
import com.gino.mywallet.domain.game.PlayingCard
import com.gino.mywallet.domain.game.PokerHand
import com.gino.mywallet.domain.game.TripleDrawPhase
import com.gino.mywallet.domain.game.TripleDrawState
import com.gino.mywallet.focus.FocusForegroundService
import com.gino.mywallet.focus.FocusSessionController
import com.gino.mywallet.notify.NotificationScheduler
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class UiMessage(val text: String, val id: Long = System.currentTimeMillis())

/** 成就頁統計（本週或本月） */
data class FocusStatsUi(
    val weekly: Boolean,
    val totalMs: Long,
    val sessionCount: Int,
    val doneTasks: Int,
    /** 分類 → 專注毫秒，依時數排序 */
    val byCategory: List<Pair<String, Long>>
)

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

    val plans: StateFlow<List<PlanEntity>> =
        db.planDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeExam: StateFlow<ExamEntity?> =
        db.examDao().observeActiveExam()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val examItems: StateFlow<List<ExamItemEntity>> =
        activeExam.flatMapLatest { exam ->
            if (exam == null) flowOf(emptyList()) else db.examDao().observeItems(exam.id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val examSubjects: StateFlow<List<ExamSubjectEntity>> =
        activeExam.flatMapLatest { exam ->
            if (exam == null) flowOf(emptyList()) else db.examDao().observeSubjects(exam.id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingPhysicalTransfers: StateFlow<List<PendingPhysicalTransferEntity>> =
        db.pendingPhysicalTransferDao().observePending()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 持股市值合計（cents）；首頁投資顯示 = 投資現金 + 這個值 */
    val stockMarketValueCents: StateFlow<Long> =
        db.stockDao().observeHoldings()
            .map { holdings -> holdings.sumOf { it.marketValueCents() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val recentFocusSessions: StateFlow<List<FocusSessionEntity>> =
        db.focusSessionDao().observeRecent(20)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _focusStats = MutableStateFlow<FocusStatsUi?>(null)
    val focusStats: StateFlow<FocusStatsUi?> = _focusStats.asStateFlow()

    private val _notificationsEnabled = MutableStateFlow(true)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    /** "calendar" | "exam"；預設行事曆 */
    private val _middleTab = MutableStateFlow("calendar")
    val middleTab: StateFlow<String> = _middleTab.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message

    private val _lastDraw = MutableStateFlow<SlotDrawResult?>(null)
    val lastDraw: StateFlow<SlotDrawResult?> = _lastDraw

    private val _passClaimReveal = MutableStateFlow<PassAdvanceResult?>(null)
    val passClaimReveal: StateFlow<PassAdvanceResult?> = _passClaimReveal.asStateFlow()

    private val _drawing = MutableStateFlow(false)
    val drawing: StateFlow<Boolean> = _drawing

    private val _entertainmentGame = MutableStateFlow(EntertainmentGame.BLACKJACK21)
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

    private val _wordleState = MutableStateFlow(DailyWordState())
    val wordleState: StateFlow<DailyWordState> = _wordleState.asStateFlow()

    private val _wordleBusy = MutableStateFlow(false)
    val wordleBusy: StateFlow<Boolean> = _wordleBusy.asStateFlow()

    private val _match3State = MutableStateFlow(com.gino.mywallet.domain.game.Match3State())
    val match3State: StateFlow<com.gino.mywallet.domain.game.Match3State> = _match3State.asStateFlow()

    private val _match3Busy = MutableStateFlow(false)
    val match3Busy: StateFlow<Boolean> = _match3Busy.asStateFlow()

    private val _match3AnimSteps =
        MutableStateFlow<List<com.gino.mywallet.domain.game.Match3AnimStep>>(emptyList())
    val match3AnimSteps:
        StateFlow<List<com.gino.mywallet.domain.game.Match3AnimStep>> = _match3AnimSteps.asStateFlow()

    private val _rouletteState = MutableStateFlow(com.gino.mywallet.domain.game.RouletteGame.idle())
    val rouletteState: StateFlow<com.gino.mywallet.domain.game.RouletteState> = _rouletteState.asStateFlow()

    private val _rouletteBusy = MutableStateFlow(false)
    val rouletteBusy: StateFlow<Boolean> = _rouletteBusy.asStateFlow()

    private val _milestone = MutableStateFlow<com.gino.mywallet.domain.MilestoneSnapshot?>(null)
    val milestone: StateFlow<com.gino.mywallet.domain.MilestoneSnapshot?> = _milestone.asStateFlow()

    private val _canMorningSlot = MutableStateFlow(false)
    val canMorningSlot: StateFlow<Boolean> = _canMorningSlot.asStateFlow()

    private val _hasDueLotto = MutableStateFlow(false)
    val hasDueLotto: StateFlow<Boolean> = _hasDueLotto.asStateFlow()

    private val _exportText = MutableStateFlow<String?>(null)
    val exportText: StateFlow<String?> = _exportText

    private val _snapshotText = MutableStateFlow<String?>(null)
    val snapshotText: StateFlow<String?> = _snapshotText

    private val botPrefs = BotSyncPrefs(getApplication())
    private val driveAuthFinishing = AtomicBoolean(false)
    private val _botDriveLabel = MutableStateFlow(driveLabelFromPrefs())
    val botDriveLabel: StateFlow<String> = _botDriveLabel.asStateFlow()
    private val _botToken = MutableStateFlow(botPrefs.token)
    val botToken: StateFlow<String> = _botToken.asStateFlow()
    private val _botRevision = MutableStateFlow(0L)
    val botRevision: StateFlow<Long> = _botRevision.asStateFlow()
    private val _botLastSync = MutableStateFlow(botPrefs.lastSyncMessage)
    val botLastSync: StateFlow<String> = _botLastSync.asStateFlow()
    private val _botSyncBusy = MutableStateFlow(false)
    val botSyncBusy: StateFlow<Boolean> = _botSyncBusy.asStateFlow()

    private fun driveLabelFromPrefs(): String = botPrefs.driveStatusLine()

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
            calendarEventDao = db.calendarEventDao(),
            planDao = db.planDao(),
            examDao = db.examDao()
        )
    }

    private val _semester = MutableStateFlow(AcademicCalendar.DEFAULT)
    val semester: StateFlow<SemesterConfig> = _semester.asStateFlow()

    private val _charity = MutableStateFlow(CharityStatus(month = "", done = false, ratePercent = 10))
    val charity: StateFlow<CharityStatus> = _charity

    private val gson = Gson()

    init {
        refreshBootstrap()
        viewModelScope.launch {
            _notificationsEnabled.value =
                db.metaDao().get(MetaKeys.NOTIFICATIONS_ENABLED)?.value != "0"
            val mid = db.metaDao().get(MetaKeys.MIDDLE_TAB)?.value
            _middleTab.value = if (mid == "exam") "exam" else "calendar"
            loadEntertainmentState()
            _botRevision.value = engine.currentRevision()
        }
    }

    fun refreshBootstrap() = run {
        engine.ensureCharitySettlement()
        engine.refreshCurrentPassRewards()
        _charity.value = engine.charityStatus()
        _semester.value = engine.loadSemester()
        _botRevision.value = engine.currentRevision()
        _milestone.value = engine.milestoneSnapshot()
        _canMorningSlot.value = engine.canClaimMorningSlot()
        _hasDueLotto.value = engine.hasDueLottoDraw()
        NotificationScheduler.rescheduleAll(getApplication())
        if (engine.consumeAutoExtendCount() > 0) {
            _message.value = UiMessage("逾時未還，已自動續借並計入利息")
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) = run {
        db.metaDao().upsert(AppMetaEntity(MetaKeys.NOTIFICATIONS_ENABLED, if (enabled) "1" else "0"))
        _notificationsEnabled.value = enabled
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage(if (enabled) "已開啟通知" else "已關閉通知")
    }

    fun setMiddleTab(mode: String) = run {
        val normalized = if (mode == "exam") "exam" else "calendar"
        db.metaDao().upsert(AppMetaEntity(MetaKeys.MIDDLE_TAB, normalized))
        _middleTab.value = normalized
        _message.value = UiMessage(
            if (normalized == "exam") "中間頁改為考試" else "中間頁改為行事曆"
        )
    }

    fun claimMorningSlot() = run {
        val ok = engine.claimMorningSlotTicket()
        _canMorningSlot.value = engine.canClaimMorningSlot()
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage(if (ok) "已領取晨間 🎰" else "尚未到 07:00 或今日已領")
    }

    /** @deprecated 改用 [claimMorningSlot] */
    fun claimCooldown() = claimMorningSlot()

    fun settleLottoManual() = run {
        val summary = engine.settleDueLottoDraws()
        _hasDueLotto.value = engine.hasDueLottoDraw()
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage(summary ?: "尚無可開獎注單")
    }

    fun refreshMilestones() = run {
        _milestone.value = engine.milestoneSnapshot()
        _canMorningSlot.value = engine.canClaimMorningSlot()
        _hasDueLotto.value = engine.hasDueLottoDraw()
    }

    fun claimTaskMilestones() = run {
        val grant = engine.claimTaskMilestones()
        _milestone.value = engine.milestoneSnapshot()
        _message.value = UiMessage(if (grant.isNotBlank()) "任務累積領獎：$grant" else "尚無可領")
    }

    fun claimFocusMilestones() = run {
        val grant = engine.claimFocusMilestones()
        _milestone.value = engine.milestoneSnapshot()
        _message.value = UiMessage(if (grant.isNotBlank()) "專注累積領獎：$grant" else "尚無可領")
    }

    fun clearMessage() { _message.value = null }

    /** 供其他 ViewModel（如投資頁）借用全域 Snackbar */
    fun notify(text: String) {
        _message.value = UiMessage(text)
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _message.value = UiMessage(e.message ?: e.toString())
            }
        }
    }

    fun incomeStandard(
        amountYuan: String,
        note: String,
        deferCentral: Boolean = false,
        onDone: (() -> Unit)? = null
    ) = run {
        val cents = parseYuan(amountYuan)
        val splits = engine.recordIncomeStandard(cents, note, deferCentral)
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
        val deferred = engine.transfer(from, to, parseYuan(amountYuan), note)
        _message.value = UiMessage(if (deferred) "已加入待轉移，確認後才入帳" else "轉帳完成")
        onDone?.let { withContext(Dispatchers.Main) { it() } }
    }

    fun completeCharity() = run {
        val before = engine.charityStatus()
        if (before.done) {
            _message.value = UiMessage("本月公益已完成")
            return@run
        }
        val skipped = EconomyEngine.shouldSkipMonthlyCharity(before.amountCents)
        engine.completeMonthlyCharity().also { released ->
            _charity.value = engine.charityStatus()
            NotificationScheduler.rescheduleAll(getApplication())
            _message.value = when {
                released > 0 ->
                    UiMessage("本月公益已完成，未來中央 ${formatTwd(released)} 已入帳")
                skipped ->
                    UiMessage("中央可捐額低於最低 ${formatTwd(EconomyEngine.MIN_CHARITY_DONATION_CENTS)}，本月免做")
                else ->
                    UiMessage("本月公益已完成")
            }
        }
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
        val deferred = engine.borrowFromWallet(lender, borrower, parseYuan(amountYuan), dueThisMonth)
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage(
            if (deferred) "借款已成立，待轉移確認後才入帳" else "借款成立"
        )
    }

    fun updateRate(walletId: String, ratePercent: String, notify: Boolean = true) = run {
        engine.updateWalletRate(walletId, (ratePercent.toDouble() * 100).toInt())
        if (notify) _message.value = UiMessage("利率已更新")
    }

    fun repay(loanId: Long, amountYuan: String) = run {
        val deferred = engine.repayLoan(loanId, parseYuan(amountYuan))
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage(if (deferred) "已加入待轉移，確認後才入帳" else "還款完成")
    }

    fun repayFull(loanId: Long) = run {
        val loan = db.loanDao().getLoan(loanId) ?: error("借據不存在")
        val deferred = engine.repayLoan(loanId, loan.remainingCents)
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage(if (deferred) "已加入待轉移，確認後才入帳" else "已還清")
    }

    fun extendLoan(loanId: Long) = run {
        val loan = engine.extendLoan(loanId)
        NotificationScheduler.rescheduleAll(getApplication())
        val due = Instant.ofEpochMilli(loan.dueAt).atZone(ZoneId.systemDefault()).toLocalDate()
        _message.value = UiMessage("已續借至 ${due.format(DateTimeFormatter.ofPattern("M/d"))}")
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
        plannedAt: Long? = null,
        planId: String? = null,
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
            engine.repeatRuleToJson(
                RepeatRule(
                    freq = repeatFreq,
                    n = repeatN.toIntOrNull(),
                    nextAt = repeatFirstAt ?: 0L
                )
            )
        } else ""
        val resolvedPlanId = planId?.trim()?.ifBlank { null }?.let { PlanIds.requireValid(it) }
        db.taskDao().insert(
            TaskEntity(
                title = title,
                note = note,
                category = category,
                repeatable = repeatable,
                repeatRuleJson = ruleJson,
                rewardJson = engine.rewardsToJson(rewards),
                plannedAt = plannedAt,
                planId = resolvedPlanId
            )
        )
        engine.bumpRevision()
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage("任務已建立（券包 Lv.$level）")
    }

    fun saveCalendarEvent(
        id: Long = 0,
        title: String,
        dateYmd: String,
        timeMinutes: Int,
        location: String,
        note: String,
        createdAt: Long = System.currentTimeMillis()
    ) = run {
        require(title.isNotBlank()) { "請輸入標題" }
        require(dateYmd.isNotBlank()) { "請選擇日期" }
        db.calendarEventDao().upsert(
            CalendarEventEntity(
                id = id,
                title = title.trim(),
                dateYmd = dateYmd.trim(),
                timeMinutes = timeMinutes,
                location = location.trim(),
                note = note.trim(),
                createdAt = createdAt
            )
        )
        engine.bumpRevision()
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage(if (id == 0L) "行程已新增" else "行程已更新")
    }

    fun deleteCalendarEvent(id: Long) = run {
        db.calendarEventDao().deleteById(id)
        engine.bumpRevision()
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage("行程已刪除")
    }

    fun deleteAllCalendarEvents() = run {
        db.calendarEventDao().deleteAll()
        engine.bumpRevision()
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage("已清空全部行程")
    }

    fun saveCourse(
        id: Long = 0,
        title: String,
        weekday: Int,
        periodStart: Int,
        periodEnd: Int,
        location: String,
        weeksJson: String = ""
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
                location = location.trim(),
                weeksJson = weeksJson
            )
        )
        engine.bumpRevision()
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage(if (id == 0L) "課程已新增" else "課程已更新")
    }

    fun deleteCourse(id: Long) = run {
        db.courseDao().deleteById(id)
        engine.bumpRevision()
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage("課程已刪除")
    }

    fun deleteTasks(ids: Collection<Long>) = run {
        val list = ids.filter { it > 0 }.distinct()
        require(list.isNotEmpty()) { "請選擇要刪除的任務" }
        db.taskDao().deleteByIds(list)
        engine.bumpRevision()
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

    fun loadFocusStats(weekly: Boolean) = run {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startDay = if (weekly) today.with(DayOfWeek.MONDAY) else today.withDayOfMonth(1)
        val from = startDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val sessions = db.focusSessionDao().getBetween(from, now)
        val done = db.taskDao().countDoneBetween(from, now)
        _focusStats.value = FocusStatsUi(
            weekly = weekly,
            totalMs = sessions.sumOf { it.effectiveMs },
            sessionCount = sessions.size,
            doneTasks = done,
            byCategory = sessions.groupBy { it.category }
                .mapValues { (_, list) -> list.sumOf { it.effectiveMs } }
                .toList()
                .sortedByDescending { it.second }
        )
    }

    fun endFocusSession(onDone: (topic: String, effectiveMs: Long) -> Unit = { _, _ -> }) = run {
        val appCtx = getApplication<Application>()
        val meta = FocusSessionController.sessionMeta
        val snap = FocusSessionController.snapshot()
        val topic = snap.topic.ifBlank { meta?.intent.orEmpty() }.trim()
        val grantTickets = snap.mode == com.gino.mywallet.domain.FocusMode.Long
        val (cash, _) = when (snap.phase) {
            com.gino.mywallet.focus.FocusPhase.ShortComplete ->
                FocusSessionController.claimShortReward()
            else -> FocusSessionController.endSession()
        }
        val ms = FocusSessionController.snapshot().displayMs
        val (pack, deferred) = engine.settleFocus(cash, ms, grantTickets = grantTickets)
        if (meta != null) {
            db.focusSessionDao().insert(
                FocusSessionEntity(
                    intent = topic.ifBlank { meta.intent },
                    category = meta.category,
                    linkedTaskIdsJson = "[]",
                    startedAt = meta.wallStartAt,
                    endedAt = System.currentTimeMillis(),
                    effectiveMs = ms,
                    cashYuan = cash
                )
            )
        }
        FocusForegroundService.stop(appCtx)
        FocusSessionController.resetIdle()
        NotificationScheduler.rescheduleAll(appCtx)
        val cashText = if (cash >= 0) "+$cash 元" else "$cash 元"
        val ticketText = com.gino.mywallet.domain.game.GamePrizes.formatTicketMap(pack)
            .ifBlank { "無券" }
        _message.value = UiMessage(
            if (deferred) "專注結算：$cashText 已加入待轉移，$ticketText"
            else "專注結算：$cashText，$ticketText"
        )
        withContext(Dispatchers.Main) { onDone(topic, ms) }
    }

    /** 專注主題標記完成後寫入行事曆當日紀錄 */
    fun recordFocusTopicOnCalendar(topic: String, effectiveMs: Long) = run {
        val title = topic.trim().ifBlank { "專注" }
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        val mins = effectiveMs / 60_000L
        val dur = when {
            mins >= 60 -> "${mins / 60} 小時 ${mins % 60} 分"
            else -> "$mins 分"
        }
        db.calendarEventDao().upsert(
            CalendarEventEntity(
                title = title,
                dateYmd = now.toLocalDate().toString(),
                timeMinutes = now.hour * 60 + now.minute,
                location = "",
                note = "專注完成 · $dur"
            )
        )
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage("已記入日曆：$title")
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

    fun buyLotto(pick: String) = run {
        engine.buyLottoTicket(pick)
        NotificationScheduler.rescheduleAll(getApplication())
        val st = db.lotteryDao().getState()
        val at = st?.lottoDrawAt ?: 0L
        val label = if (at > 0) LottoGame.formatDrawLabel(at) else "下一期"
        val picks = engine.parseLottoPicks(st?.lottoPicksJson ?: "[]")
        _message.value = UiMessage("已買 $pick，開獎 $label（待開 ${picks.size}）")
    }

    fun ensureWordle() = run {
        _wordleState.value = engine.ensureDailyWord()
    }

    fun submitWordle(guess: String) {
        if (_wordleBusy.value) return
        run {
            _wordleBusy.value = true
            try {
                val next = engine.submitDailyWordGuess(guess)
                _wordleState.value = next.state
                _message.value = UiMessage(
                    when (next.state.phase) {
                        DailyWordPhase.WON -> {
                            val zh = next.state.answerZh.ifBlank { next.state.answer }
                            val tickets = next.ticketGrant.ifBlank { "券≈$${next.state.prizeYuan}" }
                            "猜中！${next.state.answer}（$zh） $tickets"
                        }
                        DailyWordPhase.LOST -> {
                            val zh = next.state.answerZh.ifBlank { next.state.answer }
                            "沒中 · 答案 ${next.state.answer}（$zh）"
                        }
                        DailyWordPhase.PLAYING -> "繼續猜（${next.state.guesses.size}/${DailyWord.MAX_GUESSES}）"
                    }
                )
            } finally {
                _wordleBusy.value = false
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
                db.metaDao().upsert(AppMetaEntity(MetaKeys.GAME_MATCH3_JSON, gson.toJson(_match3State.value)))
                db.metaDao().upsert(AppMetaEntity(MetaKeys.GAME_MATH_JSON, gson.toJson(_rouletteState.value)))
                db.metaDao().upsert(AppMetaEntity(MetaKeys.GAME_SELECTED, _entertainmentGame.value.name))
            }
        }
    }

    private suspend fun loadEntertainmentState() {
        db.metaDao().get(MetaKeys.GAME_SELECTED)?.value?.let { name ->
            val mapped = when (name) {
                "LOTTO", "WORDLE", "SLOT" -> EntertainmentGame.BLACKJACK21
                "SPEED_MATH" -> EntertainmentGame.ROULETTE
                else -> runCatching { EntertainmentGame.valueOf(name) }.getOrNull()
            }
            mapped?.let { _entertainmentGame.value = it }
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
        db.metaDao().get(MetaKeys.GAME_MATCH3_JSON)?.value?.let { raw ->
            runCatching {
                gson.fromJson(raw, com.gino.mywallet.domain.game.Match3State::class.java)
            }.getOrNull()?.let { _match3State.value = it }
        }
        db.metaDao().get(MetaKeys.GAME_MATH_JSON)?.value?.let { raw ->
            runCatching {
                gson.fromJson(raw, com.gino.mywallet.domain.game.RouletteState::class.java)
            }.getOrNull()?.takeIf { it.cells.size == com.gino.mywallet.domain.game.RouletteGame.CELL_COUNT }
                ?.let { loaded ->
                    _rouletteState.value =
                        if (loaded.phase == com.gino.mywallet.domain.game.RoulettePhase.SPINNING) {
                            runCatching { engine.rouletteResolve(loaded) }.getOrElse { loaded }
                        } else {
                            loaded
                        }
                }
        }
        _wordleState.value = engine.ensureDailyWord()
        _milestone.value = engine.milestoneSnapshot()
        _canMorningSlot.value = engine.canClaimMorningSlot()
        _hasDueLotto.value = engine.hasDueLottoDraw()
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
                val priced = engine.blackjackAdvance(cur)
                val next = priced.state
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
                    BjResult.WIN_21 -> {
                        val yuan = com.gino.mywallet.domain.game.GamePrizes.BJ_JACKPOT_CENTS / 100
                        _message.value = UiMessage("21 點！+\$$yuan${priced.prize?.arrowLabel().orEmpty()}")
                    }
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
                val priced = engine.blackjackStand(cur)
                val next = priced.state
                _bjState.value = next
                persistEntertainmentState()
                delay(320)
                _message.value = UiMessage(
                    when (next.result) {
                        BjResult.WIN_BEAT -> {
                            val yuan = com.gino.mywallet.domain.game.GamePrizes.BJ_BEAT_CENTS / 100
                            "擊敗莊家！+\$$yuan${priced.prize?.arrowLabel().orEmpty()}"
                        }
                        BjResult.LOSE -> {
                            val pot = next.potCents / 100
                            if (pot > 0) {
                                "沒中 · \$$pot${priced.prize?.arrowLabel().orEmpty()}"
                            } else {
                                "沒中（你 ${next.score} · 莊 ${next.dealer}）"
                            }
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
                val priced = engine.hiLoGuess(cur, pick)
                val result = priced.state
                _hiLoState.value = result.copy(phase = HiLoPhase.REVEALING)
                persistEntertainmentState()
                delay(1_400)
                _hiLoState.value = result
                persistEntertainmentState()
                _message.value = UiMessage(
                    when (result.outcome) {
                        HiLoOutcome.WIN -> "猜對！+\$${result.payoutCents / 100}${priced.prize?.arrowLabel().orEmpty()}"
                        HiLoOutcome.TIE -> "相同！+\$${HiLoGame.TIE_PRIZE_YUAN}${priced.prize?.arrowLabel().orEmpty()}"
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
                val priced = engine.tripleDrawSettle(cur)
                val next = priced.state
                _tripleDrawState.value = next
                persistEntertainmentState()
                val yuan = next.payoutCents / 100
                _message.value = UiMessage(
                    if (yuan > 0) {
                        "${next.hand.label}！+\$$yuan${priced.prize?.arrowLabel().orEmpty()}"
                    } else {
                        "${next.hand.label} · 沒中"
                    }
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

    fun match3Deal() {
        if (_match3Busy.value) return
        run {
            _match3Busy.value = true
            try {
                _match3State.value = engine.match3Deal()
                persistEntertainmentState()
            } finally {
                _match3Busy.value = false
            }
        }
    }

    fun match3Swap(from: Int, to: Int) {
        if (_match3Busy.value || _match3State.value.animating) return
        run {
            _match3Busy.value = true
            try {
                val outcome = engine.match3Swap(_match3State.value, from, to)
                if (outcome == null) {
                    _message.value = UiMessage("無效交換")
                } else {
                    val (priced, resolution) = outcome
                    _match3AnimSteps.value = resolution.steps
                    _match3State.value = priced.state
                    persistEntertainmentState()
                    _message.value = UiMessage(
                        "消${priced.state.lastCleared} +${"%.2f".format(priced.state.lastPayoutCents / 100.0)}${priced.prize?.arrowLabel().orEmpty()}"
                    )
                }
            } finally {
                _match3Busy.value = false
            }
        }
    }

    fun match3AnimFinished() {
        _match3AnimSteps.value = emptyList()
        _match3State.value = _match3State.value.copy(animating = false)
        persistEntertainmentState()
    }

    fun rouletteSpin() {
        if (_rouletteBusy.value) return
        val cur = _rouletteState.value
        if (cur.phase == com.gino.mywallet.domain.game.RoulettePhase.SPINNING) return
        if (cur.needsForcedCashOut) {
            _message.value = UiMessage("請先結算本輪")
            return
        }
        run {
            _rouletteBusy.value = true
            try {
                _rouletteState.value = engine.rouletteSpin(cur)
                persistEntertainmentState()
            } finally {
                _rouletteBusy.value = false
            }
        }
    }

    fun rouletteAnimFinished() {
        val cur = _rouletteState.value
        if (cur.phase != com.gino.mywallet.domain.game.RoulettePhase.SPINNING) return
        run {
            _rouletteBusy.value = true
            try {
                val next = engine.rouletteResolve(cur)
                _rouletteState.value = next
                persistEntertainmentState()
                when {
                    next.lastKind == com.gino.mywallet.domain.game.RouletteCellKind.SETTLE &&
                        next.phase == com.gino.mywallet.domain.game.RoulettePhase.READY -> {
                        _message.value = UiMessage(
                            "結算格 +${"%.2f".format(next.lastDeltaCents / 100.0)}"
                        )
                    }
                    next.needsForcedCashOut -> {
                        _message.value = UiMessage("格子用完，請結算")
                    }
                    next.lastKind != null -> {
                        val label = next.lastKind!!.label()
                        _message.value = UiMessage(
                            "$label · ${"%.2f".format(next.potCents / 100.0)}"
                        )
                    }
                }
            } finally {
                _rouletteBusy.value = false
            }
        }
    }

    fun rouletteCashOut() {
        if (_rouletteBusy.value) return
        val cur = _rouletteState.value
        if (cur.phase != com.gino.mywallet.domain.game.RoulettePhase.LIVE || cur.potCents <= 0) return
        run {
            _rouletteBusy.value = true
            try {
                val priced = engine.rouletteCashOut(cur)
                _rouletteState.value = priced.state
                persistEntertainmentState()
                _message.value = UiMessage(
                    "輪盤結算 +${"%.2f".format(cur.potCents / 100.0)}${priced.prize?.arrowLabel().orEmpty()}"
                )
            } finally {
                _rouletteBusy.value = false
            }
        }
    }

    fun dismissPassClaimReveal() {
        _passClaimReveal.value = null
    }

    fun balanceAdjust(walletId: String, targetYuan: String) = run {
        engine.setBalanceAdjust(walletId, parseYuanAllowZero(targetYuan))
        _message.value = UiMessage("平帳完成")
    }

    fun confirmPhysicalTransfers(amountYuan: String) = run {
        val pending = pendingPhysicalTransfers.value
        val net = engine.piggyNetCents(pending)
        if (net == 0L) {
            engine.confirmPhysicalTransfers(0)
            _message.value = UiMessage("已結算（收支相抵）")
            return@run
        }
        val cents = parseYuanAllowZero(amountYuan)
        require(cents > 0) { "請輸入要轉移的金額" }
        engine.confirmPhysicalTransfers(cents)
        val max = kotlin.math.abs(net)
        _message.value = UiMessage(
            if (cents >= max) "已轉移並結算"
            else "已轉移 ${formatYuanInput(cents)} 元，剩餘待確認"
        )
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
        _semester.value = engine.loadSemester()
    }

    fun setSemester(week1Monday: LocalDate, weeks: Int) = run {
        _semester.value = engine.saveSemester(week1Monday, weeks)
        NotificationScheduler.rescheduleAll(getApplication())
        val w = AcademicCalendar.currentWeek(_semester.value)
        _message.value = UiMessage(
            "學期已設定：第1週 ${_semester.value.week1Monday}（${_semester.value.weeks} 週）" +
                (w?.let { " · 本週第${it}週" } ?: " · 今天在學期外")
        )
    }

    fun upsertPlan(id: String, title: String, note: String = "") = run {
        engine.upsertPlan(id, title, note)
        _message.value = UiMessage("計畫已儲存")
    }

    fun deletePlan(id: String) = run {
        val removed = db.taskDao().deleteOpenByPlanId(id)
        db.planDao().deleteById(id)
        engine.bumpRevision()
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage("已刪除計畫與 $removed 筆未完成任務")
    }

    /** 無進行中考試時自動建立隱藏容器（UI 不暴露考試層級） */
    fun ensureActiveExam() = run {
        ensureActiveExamInternal()
    }

    private suspend fun ensureActiveExamInternal(): ExamEntity {
        val existing = db.examDao().getActiveExam()
        if (existing != null) return existing
        val id = db.examDao().upsertExam(
            ExamEntity(title = "考試", archived = false)
        )
        return db.examDao().getExamById(id) ?: error("無法建立考試")
    }

    /** 儲存課程備註，並可同時新增多個單元（單元可為空，只更新備註） */
    fun saveExamCourse(subject: String, note: String, units: List<String>) = run {
        val subj = subject.trim()
        require(subj.isNotBlank()) { "請輸入課程名稱" }
        val exam = ensureActiveExamInternal()
        db.examDao().upsertSubjectNote(exam.id, subj, note.trim())
        val cleaned = units.map { it.trim() }.filter { it.isNotBlank() }
        cleaned.forEach { unit ->
            db.examDao().upsertItem(
                ExamItemEntity(
                    examId = exam.id,
                    subject = subj,
                    title = unit,
                    status = ExamItemStatus.OPEN
                )
            )
        }
        engine.bumpRevision()
        _message.value = UiMessage(
            if (cleaned.isEmpty()) "已儲存" else "已儲存（+${cleaned.size} 單元）"
        )
    }

    fun deleteExamItem(id: Long) = run {
        val item = db.examDao().getItemById(id) ?: error("範圍不存在")
        db.examDao().deleteItem(id)
        renormalizeExamQueue(item.examId)
        engine.bumpRevision()
        _message.value = UiMessage("已刪除範圍")
    }

    fun completeExamItem(id: Long) = run {
        refreshExamReviewsInternal()
        val item = db.examDao().getItemById(id) ?: error("範圍不存在")
        val now = System.currentTimeMillis()
        val updated = ExamRules.complete(item, now).getOrElse { throw it }
        db.examDao().updateItem(updated)
        renormalizeExamQueue(item.examId)

        // 與一般任務相同：建立 Lv4 學業任務並走 completeTask（行事曆以 DONE+resolvedAt 顯示）
        val label = ExamRules.displayLabel(item)
        val taskTitle = when (updated.status) {
            ExamItemStatus.DONE -> "複習 · $label"
            else -> label
        }
        val taskId = db.taskDao().insert(
            TaskEntity(
                title = taskTitle,
                note = "考試",
                category = "學業",
                rewardJson = engine.rewardsToJson(
                    listOf(RewardItem(kind = "TICKET_LEVEL", level = 4))
                ),
                status = TaskStatus.OPEN
            )
        )
        val pass = engine.completeTask(taskId)
        NotificationScheduler.rescheduleAll(getApplication())
        val phase = when (updated.status) {
            ExamItemStatus.COOLDOWN -> "初次完成"
            ExamItemStatus.DONE -> "複習完成"
            else -> "已完成"
        }
        val passText = pass?.let { p ->
            when {
                p.claimedReward -> "通行證 +1 · 有獎勵可領"
                p.label.contains("休息") -> "通行證 +1 · 休息"
                else -> "通行證 +1"
            }
        }
        _message.value = UiMessage(
            listOfNotNull(phase, "Lv.4 券包", passText).joinToString(" · ")
        )
    }

    fun enqueueExamItem(id: Long) = run {
        refreshExamReviewsInternal()
        val item = db.examDao().getItemById(id) ?: error("範圍不存在")
        if (!ExamRules.canEnqueue(item)) {
            error("倒數中或已複習的範圍不能加入串列")
        }
        if (item.queueOrder != null) {
            _message.value = UiMessage("已在串列中")
            return@run
        }
        val queued = ExamRules.queueSorted(db.examDao().getItems(item.examId))
        db.examDao().updateItem(item.copy(queueOrder = queued.size))
        _message.value = UiMessage("已加入串列")
    }

    fun dequeueExamItem(id: Long) = run {
        val item = db.examDao().getItemById(id) ?: error("範圍不存在")
        if (item.queueOrder == null) return@run
        db.examDao().updateItem(item.copy(queueOrder = null))
        renormalizeExamQueue(item.examId)
        _message.value = UiMessage("已移出串列")
    }

    fun moveExamQueue(id: Long, delta: Int) = run {
        val item = db.examDao().getItemById(id) ?: error("範圍不存在")
        if (item.queueOrder == null) return@run
        val queued = ExamRules.queueSorted(db.examDao().getItems(item.examId)).toMutableList()
        val index = queued.indexOfFirst { it.id == id }
        if (index < 0) return@run
        val target = (index + delta).coerceIn(0, queued.lastIndex)
        if (target == index) return@run
        queued.removeAt(index)
        queued.add(target, item)
        ExamRules.normalizeQueueOrders(queued).forEach { db.examDao().updateItem(it) }
    }

    fun refreshExamReviews() = run {
        refreshExamReviewsInternal()
    }

    private suspend fun refreshExamReviewsInternal() {
        val exam = db.examDao().getActiveExam() ?: return
        val now = System.currentTimeMillis()
        val due = ExamRules.itemsDueForReview(db.examDao().getItems(exam.id), now)
        due.forEach { item ->
            db.examDao().updateItem(item.copy(status = ExamItemStatus.REVIEWABLE))
        }
    }

    private suspend fun renormalizeExamQueue(examId: Long) {
        val queued = ExamRules.queueSorted(db.examDao().getItems(examId))
        ExamRules.normalizeQueueOrders(queued).forEach { db.examDao().updateItem(it) }
    }

    fun export() = run {
        _exportText.value = engine.exportReport()
        _message.value = UiMessage("報告已產生")
    }

    fun clearExport() { _exportText.value = null }

    fun exportSnapshot() = run {
        _snapshotText.value = engine.exportSnapshotJson()
        _message.value = UiMessage("摘要已產生")
    }

    fun clearSnapshot() { _snapshotText.value = null }

    fun setBotToken(token: String) {
        _botToken.value = token
        botPrefs.token = token
        if (token.isNotBlank() && botPrefs.isDriveAuthorized) {
            BotSyncScheduler.schedule(getApplication())
        } else if (token.isBlank()) {
            BotSyncScheduler.cancel(getApplication())
        }
    }

    fun refreshDriveLabel() {
        if (botPrefs.invalidateStaleDriveScope()) {
            _message.value = UiMessage(botPrefs.lastAuthError)
        }
        _botDriveLabel.value = driveLabelFromPrefs()
    }

    /**
     * Google OAuth：在本機 127.0.0.1 收授權碼（桌面應用程式用戶端）。
     * 授權碼先持久化；換 token 改在前景執行（華為常擋背景 HTTPS）。
     */
    fun startDriveLogin() = run {
        if (!GoogleDriveAuth.clientIdConfigured()) {
            botPrefs.lastAuthError = "尚未設定 drive.oauth.clientId"
            refreshDriveLabel()
            _message.value = UiMessage(botPrefs.lastAuthError)
            return@run
        }
        botPrefs.clearPendingAuth()
        botPrefs.lastAuthError = ""
        _botDriveLabel.value = "等待瀏覽器授權…"
        _message.value = UiMessage("請在瀏覽器完成 Google 登入…")
        val pkce = GoogleDriveAuth.newPkce()
        botPrefs.pendingCodeVerifier = pkce.verifier
        val loopback = OAuthLoopbackServer()
        val redirectUri = try {
            loopback.start()
        } catch (e: Exception) {
            botPrefs.lastAuthError = "無法啟動本機登入接收：${e.message}"
            refreshDriveLabel()
            throw IllegalStateException(botPrefs.lastAuthError)
        }
        botPrefs.pendingRedirectUri = redirectUri
        val authUri = GoogleDriveAuth.buildAuthorizeUri(pkce, redirectUri)
        withContext(Dispatchers.Main) {
            val intent = Intent(Intent.ACTION_VIEW, authUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        }
        try {
            val outcome = withContext(Dispatchers.IO) { loopback.await() }
            when (outcome) {
                is OAuthLoopbackServer.Result.Error -> {
                    botPrefs.clearPendingAuth()
                    botPrefs.lastAuthError = outcome.error
                    refreshDriveLabel()
                    throw IllegalStateException(outcome.error)
                }
                is OAuthLoopbackServer.Result.Code -> {
                    // 立刻落盤，避免背景被殺或網路被擋時弄丟授權碼
                    botPrefs.savePendingAuth(outcome.code, pkce.verifier, redirectUri)
                    refreshDriveLabel()
                    _message.value = UiMessage("授權碼已收到，正在完成連結…")
                    finishPendingDriveAuthInternal(quiet = false)
                }
            }
        } catch (e: Exception) {
            if (botPrefs.lastAuthError.isBlank()) {
                botPrefs.lastAuthError = e.message ?: e.toString()
            }
            refreshDriveLabel()
            // 若授權碼仍在，回到 App 還能再試；不要把 pending 清掉
            if (!botPrefs.hasPendingAuthCode) throw e
        } finally {
            loopback.stop()
        }
    }

    /** App 回到前景時呼叫：補完瀏覽器授權後被擋掉的換 token。 */
    fun onAppResumeDriveAuth() {
        refreshDriveLabel()
        if (!botPrefs.hasPendingAuthCode) return
        run {
            finishPendingDriveAuthInternal(quiet = false)
        }
    }

    private suspend fun finishPendingDriveAuthInternal(quiet: Boolean) {
        if (!botPrefs.hasPendingAuthCode) return
        if (!driveAuthFinishing.compareAndSet(false, true)) return
        try {
            val code = botPrefs.pendingAuthCode
            val verifier = botPrefs.pendingCodeVerifier
            val redirectUri = botPrefs.pendingRedirectUri
            _botDriveLabel.value = "正在向 Google 換取權杖…"
            val tokens = try {
                withContext(Dispatchers.IO) {
                    GoogleDriveAuth.exchangeCode(code, verifier, redirectUri)
                }
            } catch (e: Exception) {
                val msg = e.message ?: e.toString()
                // invalid_grant 等：碼已失效，清掉避免反覆重試
                val consumed = msg.contains("invalid_grant", ignoreCase = true) ||
                    msg.contains("invalid_request", ignoreCase = true)
                if (consumed) botPrefs.clearPendingAuth()
                botPrefs.lastAuthError = msg
                refreshDriveLabel()
                if (!quiet) throw e
                return
            }
            botPrefs.accessToken = tokens.accessToken
            botPrefs.accessExpiresAt =
                System.currentTimeMillis() + tokens.expiresInSec * 1000L
            if (!tokens.refreshToken.isNullOrBlank()) {
                botPrefs.refreshToken = tokens.refreshToken
            }
            if (botPrefs.refreshToken.isBlank()) {
                botPrefs.clearPendingAuth()
                botPrefs.lastAuthError =
                    "未取得 refresh_token。請到 Google 帳號 → 安全性 → 刪除應用程式存取權後重試"
                refreshDriveLabel()
                if (!quiet) throw IllegalStateException(botPrefs.lastAuthError)
                return
            }
            botPrefs.clearPendingAuth()
            botPrefs.lastAuthError = ""
            botPrefs.markScopeGranted()
            _botDriveLabel.value = "正在建立 Drive 資料夾…"
            val drive = GoogleDriveClient(botPrefs)
            try {
                withContext(Dispatchers.IO) {
                    drive.fetchAccountEmail()
                    drive.ensureBotFolders()
                }
            } catch (e: Exception) {
                botPrefs.lastAuthError = "資料夾建立失敗：${e.message}"
                refreshDriveLabel()
                if (!quiet) throw e
                return
            }
            refreshDriveLabel()
            if (botPrefs.token.isNotBlank()) {
                BotSyncScheduler.schedule(getApplication())
            }
            _message.value = UiMessage(
                "已連結 Drive（${botPrefs.accountEmail.ifBlank { BotSyncPrefs.FOLDER_NAME }}）"
            )
        } finally {
            driveAuthFinishing.set(false)
        }
    }

    fun clearDriveLink() {
        botPrefs.clearDrive()
        BotSyncScheduler.cancel(getApplication())
        _botDriveLabel.value = ""
        _message.value = UiMessage("已解除 Drive 連結")
    }

    fun syncBotFolder(fromResume: Boolean = false) = run {
        if (botPrefs.hasPendingAuthCode) {
            finishPendingDriveAuthInternal(quiet = fromResume)
        }
        if (_botSyncBusy.value) return@run
        refreshDriveLabel()
        if (!botPrefs.isDriveAuthorized) {
            if (!fromResume) {
                _message.value = UiMessage(
                    "尚未完成 Drive 授權。請按「登入 Drive」，瀏覽器出現「登入完成」後再同步"
                )
            }
            return@run
        }
        if (botPrefs.token.isBlank()) {
            if (!fromResume) _message.value = UiMessage("請先設定權杖（與 Grok Bot 共用的那串）")
            return@run
        }
        _botSyncBusy.value = true
        try {
            // viewModelScope 預設 Main；Drive HTTP 必須在 IO
            withContext(Dispatchers.IO) {
                val drive = GoogleDriveClient(botPrefs)
                drive.ensureAccessToken()
                drive.ensureBotFolders()
                if (botPrefs.accountEmail.isBlank()) {
                    runCatching { drive.fetchAccountEmail() }
                }
            }
            refreshDriveLabel()

            val syncer = BotFolderSync(
                context = getApplication(),
                engine = engine,
                metaDao = db.metaDao(),
                commandExecutor = commandExecutor,
                prefs = botPrefs
            )
            val report = syncer.sync()
            botPrefs.lastSyncAt = System.currentTimeMillis()
            botPrefs.lastSyncMessage = report.message
            _botLastSync.value = report.message
            _botRevision.value = engine.currentRevision()
            refreshDriveLabel()
            BotSyncScheduler.schedule(getApplication())
            NotificationScheduler.rescheduleAll(getApplication())
            if (!fromResume || report.appliedBatches > 0 || !report.ok) {
                _message.value = UiMessage(report.message)
            }
        } finally {
            _botSyncBusy.value = false
        }
    }

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
        _semester.value = engine.loadSemester()
        NotificationScheduler.rescheduleAll(getApplication())
        _message.value = UiMessage(
            if (result.ok) "已套用 ${result.applied} 筆指令"
            else "完成：成功 ${result.applied}／失敗 ${result.failed}"
        )
    }

    fun llmSystemPrompt(): String = LlmCommandPrompt.SYSTEM
    fun llmUserTemplate(): String = LlmCommandPrompt.USER_TEMPLATE

    private fun parseYuan(text: String): Long {
        val cents = parseYuanToCents(text, allowZero = false)
        require(cents > 0) { "金額須為正數" }
        return cents
    }

    private fun parseYuanAllowZero(text: String): Long =
        parseYuanToCents(text, allowZero = true)
}

/** 支援整數或最多兩位小數的元 → 分 */
fun parseYuanToCents(text: String, allowZero: Boolean = true): Long {
    val t = text.trim().replace(",", "").replace(" ", "")
    require(t.isNotEmpty()) { "請輸入金額" }
    require(t.count { it == '.' } <= 1) { "金額格式不正確" }
    require(t.all { it.isDigit() || it == '.' }) { "金額格式不正確" }
    val parts = t.split('.')
    val whole = parts[0].ifEmpty { "0" }.toLong()
    val frac = when {
        parts.size == 1 -> 0L
        parts[1].isEmpty() -> 0L
        parts[1].length == 1 -> parts[1].toLong() * 10
        parts[1].length == 2 -> parts[1].toLong()
        else -> error("最多兩位小數")
    }
    val cents = whole * 100 + frac
    if (!allowZero) require(cents > 0) { "金額須為正數" }
    else require(cents >= 0) { "金額不可為負" }
    return cents
}

fun formatYuanInput(cents: Long): String {
    val v = kotlin.math.abs(cents)
    val whole = v / 100
    val frac = v % 100
    val body = if (frac == 0L) whole.toString() else "%d.%02d".format(whole, frac)
    return if (cents < 0) "-$body" else body
}

fun formatTwd(cents: Long): String {
    val neg = cents < 0
    val v = kotlin.math.abs(cents)
    val s = "%,d".format(v / 100)
    return if (neg) "-NT$$s" else "NT$$s"
}

/** 還款等需精準到分的金額；有角分才顯示小數 */
fun formatTwdExact(cents: Long): String {
    val neg = cents < 0
    val v = kotlin.math.abs(cents)
    val whole = "%,d".format(v / 100)
    val frac = v % 100
    val body = if (frac == 0L) whole else "$whole.%02d".format(frac)
    return if (neg) "-NT$$body" else "NT$$body"
}

fun bpToPercent(bp: Int): String = "%.2f%%".format(bp / 100.0)

fun formatEpochDate(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    return d.toString()
}
