package com.gino.mywallet.domain

import com.gino.mywallet.data.dao.AllocationDao
import com.gino.mywallet.data.dao.CalendarEventDao
import com.gino.mywallet.data.dao.CourseDao
import com.gino.mywallet.data.dao.FocusSessionDao
import com.gino.mywallet.data.dao.LoanDao
import com.gino.mywallet.data.dao.LotteryDao
import com.gino.mywallet.data.dao.MetaDao
import com.gino.mywallet.data.dao.PassDao
import com.gino.mywallet.data.dao.PendingPhysicalTransferDao
import com.gino.mywallet.data.dao.PlanDao
import com.gino.mywallet.data.dao.StockDao
import com.gino.mywallet.data.dao.TaskDao
import com.gino.mywallet.data.dao.TransactionDao
import com.gino.mywallet.data.dao.WalletDao
import com.gino.mywallet.data.dao.WalletRateDao
import com.gino.mywallet.data.entity.AllocationRuleEntity
import com.gino.mywallet.data.entity.AppMetaEntity
import com.gino.mywallet.data.entity.CentralDebtStatus
import com.gino.mywallet.data.entity.CharityStatus
import com.gino.mywallet.data.entity.LoanEntity
import com.gino.mywallet.data.entity.LoanMarkers
import com.gino.mywallet.data.entity.LoanStatus
import com.gino.mywallet.data.entity.isCentralOverdraft
import com.gino.mywallet.data.entity.LotteryStateEntity
import com.gino.mywallet.data.entity.MetaKeys
import com.gino.mywallet.data.entity.PassAdvanceResult
import com.gino.mywallet.data.entity.PassSeasonEntity
import com.gino.mywallet.data.entity.FocusSessionEntity
import com.gino.mywallet.data.entity.PassTierEntity
import com.gino.mywallet.data.entity.PendingPhysicalTransferEntity
import com.gino.mywallet.data.entity.PhysicalTransferNotes
import com.gino.mywallet.data.entity.PlanEntity
import com.gino.mywallet.data.entity.RepeatRule
import com.gino.mywallet.data.entity.RewardItem
import com.gino.mywallet.data.entity.SlotDrawResult
import com.gino.mywallet.data.entity.SlotTiers
import com.gino.mywallet.data.entity.TaskEntity
import com.gino.mywallet.data.entity.TaskStatus
import com.gino.mywallet.data.entity.TransactionEntity
import com.gino.mywallet.data.entity.WalletCodes
import com.gino.mywallet.data.entity.WalletRateEntity
import com.gino.mywallet.domain.game.BjPhase
import com.gino.mywallet.domain.game.BjResult
import com.gino.mywallet.domain.game.Blackjack21
import com.gino.mywallet.domain.game.Blackjack21State
import com.gino.mywallet.domain.game.GamePrizes
import com.gino.mywallet.domain.game.HiLoGame
import com.gino.mywallet.domain.game.HiLoOutcome
import com.gino.mywallet.domain.game.HiLoPick
import com.gino.mywallet.domain.game.HiLoState
import com.gino.mywallet.domain.game.LottoGame
import com.gino.mywallet.domain.game.Match3Game
import com.gino.mywallet.domain.game.Match3Resolution
import com.gino.mywallet.domain.game.Match3State
import com.gino.mywallet.domain.game.DailyWord
import com.gino.mywallet.domain.game.DailyWordPhase
import com.gino.mywallet.domain.game.DailyWordState
import com.gino.mywallet.domain.game.RouletteCellKind
import com.gino.mywallet.domain.game.RouletteGame
import com.gino.mywallet.domain.game.RoulettePhase
import com.gino.mywallet.domain.game.RouletteState
import com.gino.mywallet.domain.game.TicketKind
import com.gino.mywallet.domain.game.TripleDrawGame
import com.gino.mywallet.domain.game.TripleDrawPhase
import com.gino.mywallet.domain.game.TripleDrawState
import com.gino.mywallet.domain.PrizeRouter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EconomyEngine(
    private val walletDao: WalletDao,
    private val walletRateDao: WalletRateDao,
    private val allocationDao: AllocationDao,
    private val transactionDao: TransactionDao,
    private val loanDao: LoanDao,
    private val taskDao: TaskDao,
    private val lotteryDao: LotteryDao,
    private val passDao: PassDao,
    private val metaDao: MetaDao,
    private val courseDao: CourseDao,
    private val calendarEventDao: CalendarEventDao,
    private val pendingPhysicalTransferDao: PendingPhysicalTransferDao,
    private val stockDao: StockDao? = null,
    private val planDao: PlanDao? = null,
    private val focusSessionDao: FocusSessionDao? = null,
    private val gson: Gson = Gson()
) {
    private val rewardListType = object : TypeToken<List<RewardItem>>() {}.type
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val passLock = Mutex()
    private val pendingAutoExtendCount = AtomicInteger(0)

    suspend fun currentRevision(): Long =
        metaDao.get(MetaKeys.BOT_REVISION)?.value?.toLongOrNull() ?: 0L

    /** 摘要所代表的資料有寫入時加 1；允許一次操作連呼多次。 */
    suspend fun bumpRevision(): Long {
        val next = currentRevision() + 1L
        metaDao.upsert(AppMetaEntity(MetaKeys.BOT_REVISION, next.toString()))
        return next
    }

    companion object {
        /** @deprecated 已改為每日 07:00 手動領拉霸券 */
        const val COOLDOWN_MS = 22L * 60 * 60 * 1000
        /** 每月通行證格數 */
        const val PASS_LENGTH = PassTrack.LENGTH
        /** 進度達此格起顯示終局衝刺 */
        const val PASS_SPRINT_FROM = PassTrack.SPRINT_FROM
        /** 獎池定義版本；改 buildPassTiers 內容時再加一，啟動才會覆寫本月格 */
        const val PASS_POOL_VERSION = "len80-tickets-v2"
        /** 本月還最低利率 1.00%/月（與半價月利率取較高） */
        const val SAME_MONTH_MIN_RATE_BP = 100
        /**
         * 本月應捐低於此金額（含 0、中央透支無法捐）則免做公益，避免點了卻無法完成。
         */
        const val MIN_CHARITY_DONATION_CENTS = 1_00L

        /** 應捐額是否低於最低、本月可免做 */
        fun shouldSkipMonthlyCharity(amountCents: Long): Boolean =
            amountCents < MIN_CHARITY_DONATION_CENTS

        private val SLOT_WEIGHTS = listOf(
            "$" to 26,
            "7" to 44,
            "💎" to 8,
            "⭐" to 22
        )
        private val SLOT_CASH = mapOf(
            "7" to 25_00L,
            "⭐" to 80_00L,
            "$" to 8_00L
        )
        /** 前兩格同符機率 */
        private const val SLOT_SAME_FIRST_TWO = 0.80
        /** 前兩格同符後，第三格成三連機率 */
        private const val SLOT_TRIPLE_GIVEN_PAIR = 0.22
        private const val SLOT_PAIR_CASH_CENTS = 4_00L
        private const val SLOT_SIDE_BIG = 0.30
        private const val SLOT_SIDE_SMALL = 0.12
        private const val SLOT_SIDE_MISS = 0.05
        private const val SLOT_GEM_FALLBACK_CENTS = 10_00L
    }

    // ---------- 中央欠債（透支＝負餘額 + 借據） ----------

    suspend fun migrateMetaCentralDebtToLoan() {
        val principal = metaDao.get(MetaKeys.CENTRAL_DEBT_PRINCIPAL)?.value?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
        if (principal <= 0) return
        val sinceMs = metaDao.get(MetaKeys.CENTRAL_DEBT_SINCE_MS)?.value?.toLongOrNull() ?: System.currentTimeMillis()
        val days = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(sinceMs).atZone(zone).toLocalDate(),
            LocalDate.now(zone)
        ).coerceAtLeast(0)
        val rateBp = centralOverdraftRateBp()
        val interest = principal * rateBp * days / (10_000L * 30L)
        walletDao.adjustBalance(WalletCodes.CENTRAL, -principal)
        upsertCentralOverdraftLoan(principal, extraRemaining = interest)
        metaDao.upsert(AppMetaEntity(MetaKeys.CENTRAL_DEBT_PRINCIPAL, "0"))
        metaDao.upsert(AppMetaEntity(MetaKeys.CENTRAL_DEBT_SINCE_MS, "0"))
        transactionDao.insert(
            TransactionEntity(
                type = "CENTRAL_DEBT",
                amountCents = principal,
                fromWalletId = WalletCodes.CENTRAL,
                note = "舊欠債改為透支借據"
            )
        )
    }

    suspend fun centralDebtStatus(nowMs: Long = System.currentTimeMillis()): CentralDebtStatus {
        val loan = getCentralOverdraftLoan()
        val central = walletDao.getById(WalletCodes.CENTRAL)?.balanceCents ?: 0L
        val hole = (-central).coerceAtLeast(0)
        val remaining = loan?.remainingCents ?: hole
        return CentralDebtStatus(
            principalCents = hole,
            interestCents = (remaining - hole).coerceAtLeast(0),
            totalOwedCents = remaining,
            sinceMs = loan?.startAt ?: 0L
        )
    }

    private suspend fun centralOverdraftRateBp(): Int =
        walletRateDao.get(WalletCodes.CENTRAL)?.monthlyRateBp ?: 200

    private suspend fun getCentralOverdraftLoan(): LoanEntity? =
        loanDao.getLoans().firstOrNull {
            it.isCentralOverdraft() &&
                (it.status == LoanStatus.ACTIVE || it.status == LoanStatus.DEFAULTED)
        }

    private suspend fun upsertCentralOverdraftLoan(addPrincipalCents: Long, extraRemaining: Long = 0L) {
        if (addPrincipalCents <= 0 && extraRemaining <= 0) return
        val addRemaining = addPrincipalCents + extraRemaining
        val existing = getCentralOverdraftLoan()
        val rateBp = centralOverdraftRateBp()
        val now = System.currentTimeMillis()
        val due = monthEndMillis(YearMonth.now(zone))
        if (existing == null) {
            loanDao.insertLoan(
                LoanEntity(
                    lenderWalletId = WalletCodes.CENTRAL,
                    borrowerWalletId = WalletCodes.CENTRAL,
                    principalCents = addPrincipalCents,
                    remainingCents = addRemaining,
                    monthlyRateBp = rateBp,
                    startAt = now,
                    dueAt = due,
                    interestCoveredUntil = due,
                    offerId = LoanMarkers.CENTRAL_OVERDRAFT_OFFER_ID
                )
            )
        } else {
            loanDao.updateLoan(
                existing.copy(
                    principalCents = existing.principalCents + addPrincipalCents,
                    remainingCents = existing.remainingCents + addRemaining
                )
            )
        }
    }

    /**
     * 從中央發放：餘額可為負；新增的透支會寫入借貸市場借據。
     * 若目標為現金，僅排入待轉移，確認後才入帳。
     */
    private suspend fun payFromCentral(
        toWalletId: String,
        amountCents: Long,
        note: String,
        txType: String
    ): Long {
        if (amountCents <= 0) return 0L
        if (toWalletId == WalletCodes.LIVING) {
            recordPhysicalTransfer(WalletCodes.CENTRAL, WalletCodes.LIVING, amountCents, note)
            return amountCents
        }
        return payFromCentralImmediate(toWalletId, amountCents, note, txType)
    }

    private suspend fun payFromCentralImmediate(
        toWalletId: String,
        amountCents: Long,
        note: String,
        txType: String
    ): Long {
        if (amountCents <= 0) return 0L
        val central = walletDao.getById(WalletCodes.CENTRAL) ?: return 0L
        val oldBal = central.balanceCents
        walletDao.adjustBalance(WalletCodes.CENTRAL, -amountCents)
        val newHole = (-(oldBal - amountCents)).coerceAtLeast(0)
        val oldHole = (-oldBal).coerceAtLeast(0)
        val addedHole = newHole - oldHole
        if (addedHole > 0) {
            upsertCentralOverdraftLoan(addedHole)
        }
        walletDao.adjustBalance(toWalletId, amountCents)
        transactionDao.insert(
            TransactionEntity(
                type = txType,
                amountCents = amountCents,
                fromWalletId = WalletCodes.CENTRAL,
                toWalletId = toWalletId,
                note = if (addedHole > 0) "$note（透支 ${fmtYuan(addedHole)}）" else note
            )
        )
        return amountCents
    }

    /** 中央剛入帳的金額用來填補透支／還借據；利息從正餘額扣掉。 */
    private suspend fun applyCentralIncomeToOverdraft(creditedCents: Long) {
        if (creditedCents <= 0) return
        val loan = getCentralOverdraftLoan() ?: return
        val central = walletDao.getById(WalletCodes.CENTRAL) ?: return
        val oldBal = central.balanceCents - creditedCents
        val oldHole = (-oldBal).coerceAtLeast(0)
        val filled = minOf(creditedCents, oldHole)
        var remaining = loan.remainingCents - filled
        val balanceAfter = oldBal + creditedCents
        if (remaining > 0 && balanceAfter > 0) {
            val burn = minOf(balanceAfter, remaining)
            if (burn > 0) {
                walletDao.adjustBalance(WalletCodes.CENTRAL, -burn)
                remaining -= burn
                transactionDao.insert(
                    TransactionEntity(
                        type = "CENTRAL_DEBT_INTEREST",
                        amountCents = burn,
                        fromWalletId = WalletCodes.CENTRAL,
                        note = "中央透支利息"
                    )
                )
            }
        }
        val repaid = loan.remainingCents - remaining.coerceAtLeast(0)
        if (repaid > 0) {
            transactionDao.insert(
                TransactionEntity(
                    type = "CENTRAL_DEBT_REPAY",
                    amountCents = repaid,
                    toWalletId = WalletCodes.CENTRAL,
                    note = "收入分配償還中央透支"
                )
            )
        }
        if (remaining <= 0) {
            loanDao.updateLoan(
                loan.copy(remainingCents = 0, status = LoanStatus.REPAID, penaltyRateBp = 0)
            )
        } else {
            loanDao.updateLoan(loan.copy(remainingCents = remaining))
        }
    }

    suspend fun recordIncomeStandard(
        amountCents: Long,
        note: String = "",
        deferCentral: Boolean = false
    ): Map<String, Long> {
        require(amountCents > 0)
        migrateMetaCentralDebtToLoan()
        val allocatable = amountCents
        val rules = allocationDao.getAll().associateBy { it.walletId }.toMutableMap()
        val wallets = walletDao.getAll().associateBy { it.id }
        val splits = linkedMapOf<String, Long>()
        var centralExtra = 0L
        var allocated = 0L
        val ordered = listOf(WalletCodes.CENTRAL, WalletCodes.LIVING, WalletCodes.BCI)
            .filter { rules.containsKey(it) }
            .ifEmpty { rules.keys.toList() }
        ordered.forEachIndexed { index, walletId ->
            val bp = rules[walletId]?.percentBp ?: 0
            val share = if (index == ordered.lastIndex) allocatable - allocated else allocatable * bp / 10_000
            allocated += if (index != ordered.lastIndex) share else 0
            val canReceive = wallets[walletId]?.canReceiveIncome != false || walletId == WalletCodes.CENTRAL
            if (canReceive) splits[walletId] = (splits[walletId] ?: 0) + share
            else centralExtra += share
        }
        if (centralExtra > 0) {
            splits[WalletCodes.CENTRAL] = (splits[WalletCodes.CENTRAL] ?: 0) + centralExtra
        }
        val posted = routeCentralShare(splits, centralShareDestination(deferCentral))
        val baseNote = note.ifBlank { "標準收入分配" }
        posted.forEach { (id, cents) ->
            if (cents != 0L) {
                walletDao.adjustBalance(id, cents)
                transactionDao.insert(
                    TransactionEntity(
                        type = "INCOME_A",
                        amountCents = cents,
                        toWalletId = id,
                        note = if (id == WalletCodes.CENTRAL_FUTURE) "$baseNote（延後入中央）" else baseNote
                    )
                )
            }
        }
        applyCentralIncomeToOverdraft(posted[WalletCodes.CENTRAL] ?: 0L)
        bumpRevision()
        return posted
    }

    /** @return true 表示已排入待轉移，確認後才入帳 */
    suspend fun transfer(fromId: String, toId: String, amountCents: Long, note: String = ""): Boolean {
        require(amountCents > 0 && fromId != toId)
        if (fromId == WalletCodes.CENTRAL_FUTURE) {
            require(toId == WalletCodes.CENTRAL) { "未來中央只能轉入中央帳戶" }
            releaseFutureCentral(amountCents, note.ifBlank { "未來中央入帳" })
            bumpRevision()
            return false
        }
        require(toId != WalletCodes.CENTRAL_FUTURE) {
            "未來中央帳戶僅供延後入帳"
        }
        val from = walletDao.getById(fromId) ?: error("找不到來源錢包")
        if (from.isCentral) error("中央帳戶不可提款／一般轉出")
        if (from.balanceCents < amountCents) error("餘額不足")
        if (shouldDeferPhysicalTransfer(fromId, toId)) {
            val deferred = recordPhysicalTransfer(fromId, toId, amountCents, note.ifBlank { "轉帳" })
            bumpRevision()
            return deferred
        }
        walletDao.adjustBalance(fromId, -amountCents)
        walletDao.adjustBalance(toId, amountCents)
        transactionDao.insert(
            TransactionEntity(type = "TRANSFER", amountCents = amountCents, fromWalletId = fromId, toWalletId = toId, note = note)
        )
        bumpRevision()
        return false
    }

    // ---------- 公益 ----------

    suspend fun charityReliefPercent(): Int =
        metaDao.get(MetaKeys.CHARITY_RELIEF_PERCENT)?.value?.toIntOrNull() ?: 0

    suspend fun charityRatePercent(today: LocalDate = LocalDate.now(zone)): Int {
        val raw = 10 + (today.dayOfMonth - 1)
        val relief = charityReliefPercent()
        return (raw - relief).coerceAtLeast(5)
    }

    suspend fun charityStatus(today: LocalDate = LocalDate.now(zone)): CharityStatus {
        val month = YearMonth.from(today).toString()
        val stored = metaDao.get(MetaKeys.CHARITY_MONTH)?.value
        val done = stored == month && metaDao.get(MetaKeys.CHARITY_DONE)?.value == "1"
        val rate = if (done) 0 else charityRatePercent(today)
        val central = (walletDao.getById(WalletCodes.CENTRAL)?.balanceCents ?: 0L).coerceAtLeast(0)
        val amount = if (done) 0L else central * rate / 100
        return CharityStatus(month = month, done = done, ratePercent = rate, amountCents = amount)
    }

    suspend fun ensureCharitySettlement(today: LocalDate = LocalDate.now(zone)) {
        migrateMetaCentralDebtToLoan()
        backfillFocusPhysicalTransfers()
        val currentMonth = YearMonth.from(today).toString()
        val storedMonth = metaDao.get(MetaKeys.CHARITY_MONTH)?.value
        val done = metaDao.get(MetaKeys.CHARITY_DONE)?.value == "1"
        if (storedMonth != null && storedMonth < currentMonth && !done) {
            levyCharityPenaltyTax(forMonth = storedMonth)
        }
        if (storedMonth != currentMonth) {
            metaDao.upsert(AppMetaEntity(MetaKeys.CHARITY_MONTH, currentMonth))
            metaDao.upsert(AppMetaEntity(MetaKeys.CHARITY_DONE, "0"))
            metaDao.upsert(AppMetaEntity(MetaKeys.CHARITY_RELIEF_PERCENT, "0"))
            resetDefaultAllocation(currentMonth)
        } else if (metaDao.get(MetaKeys.CHARITY_DONE) == null) {
            metaDao.upsert(AppMetaEntity(MetaKeys.CHARITY_DONE, "0"))
        }
        ensureAllocationMonth(currentMonth)
        // 清除先前以任務形式建立的公益項
        taskDao.deleteByTitleLike("【公益】%")
        autoSkipCharityIfBelowMinimum(today)
        collapseZeroNetPending()
    }

    /** 中央可捐額（正餘額×%）低於最低時，本月直接視為完成，不徵稅、不卡畫面。 */
    private suspend fun autoSkipCharityIfBelowMinimum(today: LocalDate) {
        val status = charityStatus(today)
        if (status.done) return
        if (!shouldSkipMonthlyCharity(status.amountCents)) return
        metaDao.upsert(AppMetaEntity(MetaKeys.CHARITY_DONE, "1"))
        releaseFutureCentral()
        bumpRevision()
    }

    /**
     * 更新後第一次：把舊版已直接入帳的專注結算淨額改列待轉移，
     * 並從伙雜費撤銷，避免確認時重複入帳。
     */
    private suspend fun backfillFocusPhysicalTransfers() {
        if (metaDao.get(MetaKeys.FOCUS_SETTLE_PHYSICAL_BACKFILL)?.value == "1") return
        val sessions = focusSessionDao?.getAll().orEmpty()
        val sessionNet = sessions.sumOf { it.cashYuan.toLong() * 100L }
        val settleNet = transactionDao.getByType("FOCUS_SETTLE")
            .sumOf { signedLivingCents(it.fromWalletId, it.toWalletId, it.amountCents) }
        val accumulated = if (sessions.isNotEmpty()) sessionNet else settleNet
        val alreadyDeferred = pendingPhysicalTransferDao.getPending()
            .filter { it.taskTitle.startsWith("專注結算") }
            .sumOf { signedLivingCents(it.fromWalletId, it.toWalletId, it.amountCents) } +
            transactionDao.getByType("PHYSICAL_TRANSFER")
                .filter { it.note.startsWith("專注結算") }
                .sumOf { signedLivingCents(it.fromWalletId, it.toWalletId, it.amountCents) }
        val net = accumulated - alreadyDeferred
        if (net != 0L) {
            walletDao.adjustBalance(WalletCodes.LIVING, -net)
            transactionDao.insert(
                TransactionEntity(
                    type = "FOCUS_SETTLE_DEFER",
                    amountCents = kotlin.math.abs(net),
                    fromWalletId = if (net > 0) WalletCodes.LIVING else null,
                    toWalletId = if (net < 0) WalletCodes.LIVING else null,
                    note = "專注結算改列待轉移"
                )
            )
            if (net > 0L) {
                recordPhysicalTransfer(
                    WalletCodes.CENTRAL,
                    WalletCodes.LIVING,
                    net,
                    "專注結算（歷史補記）"
                )
            } else {
                recordPhysicalTransfer(
                    WalletCodes.LIVING,
                    WalletCodes.CENTRAL,
                    -net,
                    "專注結算（歷史補記）"
                )
            }
        }
        metaDao.upsert(AppMetaEntity(MetaKeys.FOCUS_SETTLE_PHYSICAL_BACKFILL, "1"))
    }

    private fun signedLivingCents(fromWalletId: String?, toWalletId: String?, amountCents: Long): Long =
        when {
            toWalletId == WalletCodes.LIVING -> amountCents
            fromWalletId == WalletCodes.LIVING -> -amountCents
            else -> 0L
        }

    private suspend fun ensureAllocationMonth(currentMonth: String) {
        val stored = metaDao.get(MetaKeys.ALLOCATION_MONTH)?.value
        if (stored != currentMonth) {
            resetDefaultAllocation(currentMonth)
        }
    }

    /** 每月重置為 中央 40%／現金 50%／bci 10% */
    suspend fun resetDefaultAllocation(month: String = YearMonth.now(zone).toString()) {
        allocationDao.upsertAll(
            listOf(
                AllocationRuleEntity(WalletCodes.CENTRAL, 4000, isTaxShare = true),
                AllocationRuleEntity(WalletCodes.LIVING, 5000),
                AllocationRuleEntity(WalletCodes.BCI, 1000)
            )
        )
        metaDao.upsert(AppMetaEntity(MetaKeys.ALLOCATION_MONTH, month))
        metaDao.upsert(AppMetaEntity(MetaKeys.TAX_BP, "4000"))
    }

    /** @return 完成後從未來中央轉入中央的金額 */
    suspend fun completeMonthlyCharity(today: LocalDate = LocalDate.now(zone)): Long {
        ensureCharitySettlement(today)
        val status = charityStatus(today)
        // ensure 可能已因低於最低金額自動免做；視為成功不再拋錯
        if (status.done) return 0L
        val rate = status.ratePercent
        val central = walletDao.getById(WalletCodes.CENTRAL) ?: error("無中央帳戶")
        val amount = central.balanceCents.coerceAtLeast(0) * rate / 100
        if (!shouldSkipMonthlyCharity(amount)) {
            walletDao.adjustBalance(WalletCodes.CENTRAL, -amount)
            transactionDao.insert(
                TransactionEntity(
                    type = "DONATION",
                    amountCents = amount,
                    fromWalletId = WalletCodes.CENTRAL,
                    note = "公益捐獻 ${rate}%（${status.month}）"
                )
            )
        }
        metaDao.upsert(AppMetaEntity(MetaKeys.CHARITY_DONE, "1"))
        val released = releaseFutureCentral()
        bumpRevision()
        return released
    }

    /** 把未來中央的金額轉入真正的中央並償還透支。省略金額時轉出全部餘額。 */
    private suspend fun releaseFutureCentral(amountCents: Long? = null, note: String = "未來中央入帳"): Long {
        val future = walletDao.getById(WalletCodes.CENTRAL_FUTURE) ?: return 0L
        val cents = amountCents ?: future.balanceCents
        if (cents <= 0) return 0L
        if (future.balanceCents < cents) error("餘額不足")
        walletDao.adjustBalance(WalletCodes.CENTRAL_FUTURE, -cents)
        walletDao.adjustBalance(WalletCodes.CENTRAL, cents)
        transactionDao.insert(
            TransactionEntity(
                type = "CENTRAL_RELEASE",
                amountCents = cents,
                fromWalletId = WalletCodes.CENTRAL_FUTURE,
                toWalletId = WalletCodes.CENTRAL,
                note = note.ifBlank { "未來中央入帳" }
            )
        )
        applyCentralIncomeToOverdraft(cents)
        return cents
    }

    private suspend fun levyCharityPenaltyTax(forMonth: String) {
        var toCentral = 0L
        walletDao.getAll().filter {
            !it.isCentral && it.id != WalletCodes.CENTRAL_FUTURE && it.balanceCents > 0
        }.forEach { w ->
            val tax = w.balanceCents / 10
            if (tax <= 0) return@forEach
            walletDao.adjustBalance(w.id, -tax)
            walletDao.adjustBalance(WalletCodes.CENTRAL, tax)
            toCentral += tax
            transactionDao.insert(
                TransactionEntity(
                    type = "CHARITY_TAX",
                    amountCents = tax,
                    fromWalletId = w.id,
                    toWalletId = WalletCodes.CENTRAL,
                    note = "公益未完成徵稅 10%（$forMonth）"
                )
            )
        }
        applyCentralIncomeToOverdraft(toCentral)
    }

    // ---------- 借貸／利率 ----------

    /** 本月還：max(月利率/2, 1%)；下月還：完整月利率 */
    fun effectiveRateBp(rateBp: Int, dueThisMonth: Boolean): Int {
        if (!dueThisMonth) return rateBp
        return maxOf(rateBp / 2, SAME_MONTH_MIN_RATE_BP)
    }

    fun previewRepayCents(principalCents: Long, rateBp: Int, dueThisMonth: Boolean): Long {
        val eff = effectiveRateBp(rateBp, dueThisMonth)
        return principalCents + principalCents * eff / 10_000
    }

    suspend fun updateWalletRate(walletId: String, rateBp: Int) {
        require(walletId != WalletCodes.CENTRAL) { "中央帳戶利率僅能由後端變更" }
        require(rateBp >= 0)
        walletRateDao.upsert(WalletRateEntity(walletId, rateBp))
        bumpRevision()
    }

    /** 僅供種子／遷移等後端流程設定中央利率 */
    suspend fun setCentralRateBp(rateBp: Int) {
        require(rateBp >= 0)
        walletRateDao.upsert(WalletRateEntity(WalletCodes.CENTRAL, rateBp))
    }

    /** @return true 表示本金已排入待轉移，確認後才入帳 */
    suspend fun borrowFromWallet(
        lenderId: String,
        borrowerId: String,
        amountCents: Long,
        dueThisMonth: Boolean
    ): Boolean {
        require(amountCents > 0)
        require(lenderId != borrowerId)
        require(lenderId != WalletCodes.CENTRAL_FUTURE && borrowerId != WalletCodes.CENTRAL_FUTURE) {
            "未來中央帳戶不可借貸"
        }
        require(borrowerId != WalletCodes.CENTRAL) { "中央透支請由系統自動產生借據" }
        val lender = walletDao.getById(lenderId) ?: error("出借方不存在")
        if (lender.balanceCents < amountCents) error("出借方餘額不足")
        val rateBp = walletRateDao.get(lenderId)?.monthlyRateBp ?: error("尚未設定該戶利率")
        val effRate = effectiveRateBp(rateBp, dueThisMonth)
        val interest = amountCents * effRate / 10_000
        val ym = YearMonth.now(zone)
        val dueYm = if (dueThisMonth) ym else ym.plusMonths(1)
        val due = monthEndMillis(dueYm)
        val now = System.currentTimeMillis()
        // 本月還：借入時即計入最低／半價息；下月還：本金入帳，息於進入到期月時併入
        val remaining = if (dueThisMonth) amountCents + interest else amountCents
        val coveredUntil = if (dueThisMonth) due else monthEndMillis(ym)
        val loanId = loanDao.insertLoan(
            LoanEntity(
                lenderWalletId = lenderId,
                borrowerWalletId = borrowerId,
                principalCents = amountCents,
                remainingCents = remaining,
                monthlyRateBp = rateBp,
                startAt = now,
                dueAt = due,
                interestCoveredUntil = coveredUntil
            )
        )
        if (dueThisMonth && interest > 0) {
            transactionDao.insert(
                TransactionEntity(
                    type = "LOAN_INTEREST",
                    amountCents = interest,
                    fromWalletId = borrowerId,
                    toWalletId = lenderId,
                    note = "本月還最低息 借據#$loanId（${effRate / 100.0}%）"
                )
            )
        }
        if (shouldDeferPhysicalTransfer(lenderId, borrowerId)) {
            val deferred = recordPhysicalTransfer(
                lenderId, borrowerId, amountCents, PhysicalTransferNotes.borrow(loanId)
            )
            bumpRevision()
            return deferred
        }
        disburseLoan(loanId, lenderId, borrowerId, amountCents, dueThisMonth)
        bumpRevision()
        return false
    }

    /** @return true 表示已排入待轉移，確認後才入帳 */
    suspend fun repayLoan(loanId: Long, amountCents: Long): Boolean {
        val loan = loanDao.getLoan(loanId) ?: error("借據不存在")
        require(!loan.isCentralOverdraft()) { "中央透支借據由收入分配自動償還" }
        require(loan.status == LoanStatus.ACTIVE || loan.status == LoanStatus.DEFAULTED)
        require(amountCents > 0)
        val borrower = walletDao.getById(loan.borrowerWalletId) ?: error("借方不存在")
        if (borrower.balanceCents < amountCents) error("借方餘額不足")
        val principalPaid = minOf(amountCents, loan.remainingCents)
        require(principalPaid > 0) { "此借據無需再還" }
        if (shouldDeferPhysicalTransfer(loan.borrowerWalletId, loan.lenderWalletId)) {
            val title = PhysicalTransferNotes.repay(loanId)
            val already = pendingPhysicalTransferDao.getPending().any { it.taskTitle == title }
            if (already) error("此借據已有待確認還款，請先到錢包頁確認轉移")
            val deferred = recordPhysicalTransfer(
                loan.borrowerWalletId, loan.lenderWalletId, principalPaid, title
            )
            bumpRevision()
            return deferred
        }
        applyLoanRepay(loan, principalPaid)
        bumpRevision()
        return false
    }

    suspend fun settleMonthlyLoanInterest(today: LocalDate = LocalDate.now(zone)) {
        val ym = YearMonth.from(today)
        val key = ym.toString()
        val last = metaDao.get(MetaKeys.LOAN_INTEREST_MONTH)?.value
        if (last != key) {
            val previous = last?.let {
                try {
                    YearMonth.parse(it)
                } catch (_: Exception) {
                    null
                }
            }
            if (previous == null) {
                metaDao.upsert(AppMetaEntity(MetaKeys.LOAN_INTEREST_MONTH, key))
                alignLoanDueDates(ym)
            } else if (ym.isAfter(previous)) {
                var cursor = previous.plusMonths(1)
                while (!cursor.isAfter(ym)) {
                    capitalizeOneMonthInterest(cursor)
                    cursor = cursor.plusMonths(1)
                }
                metaDao.upsert(AppMetaEntity(MetaKeys.LOAN_INTEREST_MONTH, key))
                alignLoanDueDates(ym)
            }
        }
        val extended = refreshOpenLoans(today)
        if (extended > 0) pendingAutoExtendCount.addAndGet(extended)
    }

    /** 啟動後若有借據因逾時被自動續借，回傳次數並清掉，供提示一次。 */
    fun consumeAutoExtendCount(): Int = pendingAutoExtendCount.getAndSet(0)

    private suspend fun capitalizeOneMonthInterest(forMonth: YearMonth) {
        val due = monthEndMillis(forMonth)
        loanDao.getLoans()
            .filter {
                it.isCentralOverdraft() &&
                    (it.status == LoanStatus.ACTIVE || it.status == LoanStatus.DEFAULTED)
            }
            .forEach { loan ->
                val interest = LoanExtension.monthInterestCents(
                    loan.remainingCents,
                    loan.monthlyRateBp,
                    loan.penaltyRateBp
                )
                if (interest <= 0) {
                    loanDao.updateLoan(loan.copy(dueAt = due, interestCoveredUntil = due))
                    return@forEach
                }
                loanDao.updateLoan(
                    loan.copy(
                        remainingCents = loan.remainingCents + interest,
                        dueAt = due,
                        interestCoveredUntil = due
                    )
                )
                transactionDao.insert(
                    TransactionEntity(
                        type = "LOAN_INTEREST",
                        amountCents = interest,
                        fromWalletId = loan.borrowerWalletId,
                        toWalletId = loan.lenderWalletId,
                        note = "月息結算 $forMonth 借據#${loan.id}"
                    )
                )
            }
    }

    private suspend fun alignLoanDueDates(ym: YearMonth) {
        val due = monthEndMillis(ym)
        loanDao.getLoans()
            .filter { it.isCentralOverdraft() && (it.status == LoanStatus.ACTIVE || it.status == LoanStatus.DEFAULTED) }
            .forEach { loan ->
                val loanYm = YearMonth.from(Instant.ofEpochMilli(loan.dueAt).atZone(zone).toLocalDate())
                if (!loanYm.isAfter(ym) && loan.dueAt != due) {
                    loanDao.updateLoan(loan.copy(dueAt = due, interestCoveredUntil = due))
                }
            }
    }

    /**
     * 一般借據：到期月開始時補上尚未入帳的本期月息；到期日已過則自動續借，
     * 每過一個月加一個月利息並把還債日往後推一個月。
     * @return 自動續借次數
     */
    private suspend fun refreshOpenLoans(today: LocalDate): Int {
        backfillInterestCoveredUntil()
        var extensions = 0
        loanDao.getLoans()
            .filter {
                !it.isCentralOverdraft() &&
                    (it.status == LoanStatus.ACTIVE || it.status == LoanStatus.DEFAULTED) &&
                    it.remainingCents > 0
            }
            .forEach { start ->
                var loan = start
                val dueYm = YearMonth.from(Instant.ofEpochMilli(loan.dueAt).atZone(zone).toLocalDate())
                if (loan.interestCoveredUntil < loan.dueAt && !YearMonth.from(today).isBefore(dueYm)) {
                    loan = postTermInterest(loan)
                }
                var guard = 0
                while (LoanExtension.isOverdue(loan.dueAt, today, zone) && guard < 36) {
                    val next = postExtension(loan, "逾時自動續借")
                    if (next.dueAt <= loan.dueAt) break
                    loan = next
                    extensions++
                    guard++
                }
            }
        return extensions
    }

    private suspend fun backfillInterestCoveredUntil() {
        loanDao.getLoans().filter { it.interestCoveredUntil == 0L }.forEach { loan ->
            val covered = if (loan.isCentralOverdraft()) {
                loan.dueAt
            } else {
                LoanExtension.effectiveCoveredUntil(loan, zone)
            }
            if (covered != 0L) {
                loanDao.updateLoan(loan.copy(interestCoveredUntil = covered))
            }
        }
    }

    /** 延長一個月，並立刻再計入一個月利息。本期月息若尚未入帳會一併補上。 */
    suspend fun extendLoan(loanId: Long): LoanEntity {
        backfillInterestCoveredUntil()
        var loan = loanDao.getLoan(loanId) ?: error("借據不存在")
        require(!loan.isCentralOverdraft()) { "中央透支借據不可續借" }
        require(loan.status == LoanStatus.ACTIVE || loan.status == LoanStatus.DEFAULTED) {
            "此借據不可續借"
        }
        require(loan.remainingCents > 0) { "此借據無需續借" }
        if (loan.interestCoveredUntil < loan.dueAt) {
            loan = postTermInterest(loan)
        }
        val updated = postExtension(loan, "續借一個月")
        bumpRevision()
        return updated
    }

    private suspend fun postTermInterest(loan: LoanEntity): LoanEntity {
        val interest = LoanExtension.monthInterestCents(
            loan.remainingCents,
            loan.monthlyRateBp,
            loan.penaltyRateBp
        )
        val updated = loan.copy(
            remainingCents = loan.remainingCents + interest,
            interestCoveredUntil = loan.dueAt
        )
        loanDao.updateLoan(updated)
        if (interest > 0) {
            val ym = YearMonth.from(Instant.ofEpochMilli(loan.dueAt).atZone(zone).toLocalDate())
            transactionDao.insert(
                TransactionEntity(
                    type = "LOAN_INTEREST",
                    amountCents = interest,
                    fromWalletId = loan.borrowerWalletId,
                    toWalletId = loan.lenderWalletId,
                    note = "月息結算 $ym 借據#${loan.id}"
                )
            )
        }
        return updated
    }

    private suspend fun postExtension(loan: LoanEntity, notePrefix: String): LoanEntity {
        val interest = LoanExtension.monthInterestCents(
            loan.remainingCents,
            loan.monthlyRateBp,
            loan.penaltyRateBp
        )
        val newDue = LoanExtension.plusMonths(loan.dueAt, 1, zone)
        val updated = loan.copy(
            remainingCents = loan.remainingCents + interest,
            dueAt = newDue,
            interestCoveredUntil = newDue
        )
        loanDao.updateLoan(updated)
        if (interest > 0) {
            transactionDao.insert(
                TransactionEntity(
                    type = "LOAN_INTEREST",
                    amountCents = interest,
                    fromWalletId = loan.borrowerWalletId,
                    toWalletId = loan.lenderWalletId,
                    note = "$notePrefix 借據#${loan.id}"
                )
            )
        }
        return updated
    }

    private fun monthEndMillis(ym: YearMonth): Long =
        ym.atEndOfMonth().atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

    suspend fun markDefault(loanId: Long) {
        val loan = loanDao.getLoan(loanId) ?: error("借據不存在")
        require(!loan.isCentralOverdraft()) { "中央透支借據不可宣告違約" }
        require(loan.status == LoanStatus.ACTIVE)
        loanDao.updateLoan(loan.copy(status = LoanStatus.DEFAULTED, penaltyRateBp = loan.penaltyRateBp + 1000))
        walletDao.setCanReceiveIncome(loan.borrowerWalletId, false)
        val rules = allocationDao.getAll().associateBy { it.walletId }.toMutableMap()
        fun bp(id: String) = rules[id]?.percentBp ?: 0
        fun setBp(id: String, value: Int, tax: Boolean = rules[id]?.isTaxShare == true) {
            rules[id] = AllocationRuleEntity(id, value.coerceAtLeast(0), tax)
        }
        setBp(WalletCodes.CENTRAL, bp(WalletCodes.CENTRAL) + 200, tax = true)
        setBp(WalletCodes.LIVING, bp(WalletCodes.LIVING) - 100)
        setBp(WalletCodes.BCI, bp(WalletCodes.BCI) - 100)
        if (loan.borrowerWalletId == WalletCodes.LIVING || loan.borrowerWalletId == WalletCodes.BCI) {
            val seized = bp(loan.borrowerWalletId)
            setBp(loan.borrowerWalletId, 0)
            setBp(WalletCodes.CENTRAL, bp(WalletCodes.CENTRAL) + seized, tax = true)
        }
        normalizeRules(rules)
        allocationDao.upsertAll(rules.values.toList())
        transactionDao.insert(
            TransactionEntity(
                type = "LOAN_DEFAULT",
                amountCents = 0,
                fromWalletId = loan.borrowerWalletId,
                toWalletId = loan.lenderWalletId,
                note = "違約 #$loanId"
            )
        )
    }

    private fun normalizeRules(rules: MutableMap<String, AllocationRuleEntity>) {
        val sum = rules.values.sumOf { it.percentBp }
        if (sum == 10_000 || sum == 0) return
        val central = rules[WalletCodes.CENTRAL] ?: return
        rules[WalletCodes.CENTRAL] = central.copy(percentBp = central.percentBp + (10_000 - sum))
    }

    suspend fun applyAllocationDeltas(deltas: Map<String, Int>) {
        val rules = allocationDao.getAll().associateBy { it.walletId }.toMutableMap()
        deltas.forEach { (id, delta) ->
            val cur = rules[id] ?: AllocationRuleEntity(id, 0, id == WalletCodes.CENTRAL)
            rules[id] = cur.copy(percentBp = (cur.percentBp + delta).coerceAtLeast(0))
        }
        normalizeRules(rules)
        allocationDao.upsertAll(rules.values.toList())
        bumpRevision()
    }

    // ---------- 任務 ----------

    suspend fun completeTask(taskId: Long): PassAdvanceResult? {
        val task = taskDao.getById(taskId) ?: error("任務不存在")
        require(task.status == TaskStatus.OPEN)
        require(!task.isRepeatOnCooldown()) { "下次可執行前不可再領取" }
        val rewards = parseRewards(task.rewardJson)
        if (rewards.none { it.kind.startsWith("TICKET") || it.kind == "LOTTERY_TICKET" }) {
            addTickets(TicketKind.SLOT, 1) // 舊任務未寫入券獎勵時預設 +1 🎰
        }
        val passResult = advancePassOneTier()
        applyRewards(rewards, allowedCentralOut = true)
        val now = System.currentTimeMillis()
        if (task.repeatable) {
            applyRepeatCooldown(task, now)
        } else {
            taskDao.update(task.copy(status = TaskStatus.DONE, resolvedAt = now))
        }
        bumpRevision()
        return passResult
    }

    /**
     * 外部受信任 App（死記硬背）入帳：通行證 +1、TICKET_LEVEL 券包，不加現金。
     * @return Pair(是否新入帳, 通行證結果)；已處理過相同 nonce 則 first=false
     */
    suspend fun grantExternalTicketLevel(
        source: String,
        date: String,
        nonce: String,
        level: Int,
        note: String
    ): Pair<Boolean, PassAdvanceResult?> {
        require(source == "rote") { "不支援的來源" }
        require(nonce.isNotBlank()) { "缺少 nonce" }
        require(date.isNotBlank()) { "缺少 date" }
        val lv = level.coerceIn(1, GamePrizes.MAX_TICKET_LEVEL)
        val key = MetaKeys.EXT_GRANT_PREFIX + source + ":" + date + ":" + nonce
        if (metaDao.get(key) != null) {
            return false to null
        }
        val passResult = advancePassOneTier()
        applyRewards(
            listOf(RewardItem(kind = "TICKET_LEVEL", level = lv)),
            allowedCentralOut = true
        )
        metaDao.upsert(AppMetaEntity(key, note.ifBlank { "ok" }))
        bumpRevision()
        return true to passResult
    }

    /**
     * Bot／指令立即發獎：現金從中央到 [toWalletId]，以及／或加券。
     * 不推進通行證。重複 nonce 則略過。
     * @return Pair(是否新入帳, 說明)
     */
    suspend fun grantBotReward(
        nonce: String,
        yuan: Double? = null,
        toWalletId: String? = null,
        ticketKind: String? = null,
        ticketN: Int = 0,
        note: String = ""
    ): Pair<Boolean, String> {
        require(nonce.isNotBlank()) { "缺少 nonce" }
        val key = MetaKeys.BOT_REWARD_PREFIX + nonce
        if (metaDao.get(key) != null) {
            return false to "此筆獎勵已入帳過"
        }
        val cashYuan = yuan ?: 0.0
        val hasCash = cashYuan > 0
        val hasTickets = !ticketKind.isNullOrBlank() && ticketN > 0
        require(hasCash || hasTickets) { "須提供現金或券" }
        if (hasCash) {
            val to = toWalletId?.trim().orEmpty()
            require(to.isNotBlank()) { "現金獎須指定 to" }
            require(to != WalletCodes.CENTRAL && to != WalletCodes.CENTRAL_FUTURE) {
                "to 不可為中央"
            }
            walletDao.getById(to) ?: error("找不到目標錢包：$to")
            val cents = (cashYuan * 100.0).roundToLong()
            require(cents > 0) { "現金須為正數" }
            payFromCentral(to, cents, note.ifBlank { "bot 發獎" }, "REWARD_TRANSFER")
        }
        if (hasTickets) {
            val kind = TicketKind.entries.find { it.name.equals(ticketKind, ignoreCase = true) }
                ?: error("未知券種：$ticketKind")
            addTickets(kind, ticketN)
        }
        metaDao.upsert(AppMetaEntity(key, note.ifBlank { "ok" }))
        bumpRevision()
        return true to "已發獎"
    }

    suspend fun failTask(taskId: Long) {
        val task = taskDao.getById(taskId) ?: error("任務不存在")
        require(task.status == TaskStatus.OPEN)
        require(!task.isRepeatOnCooldown()) { "下次可執行前不可再領取" }
        applyRewards(parseRewards(task.penaltyJson), allowedCentralOut = false)
        val now = System.currentTimeMillis()
        if (task.repeatable) {
            applyRepeatCooldown(task, now)
        } else {
            taskDao.update(task.copy(status = TaskStatus.FAILED, resolvedAt = now))
        }
        bumpRevision()
    }

    private fun shouldDeferPhysicalTransfer(from: String, to: String): Boolean =
        (WalletCodes.isPiggyWallet(from) && to == WalletCodes.LIVING) ||
            (from == WalletCodes.LIVING && WalletCodes.isPiggyWallet(to))

    /** @return true 表示仍在待轉移；淨額被抵成 0 時會立即結清並回傳 false */
    private suspend fun recordPhysicalTransfer(
        fromWalletId: String,
        toWalletId: String,
        amountCents: Long,
        title: String
    ): Boolean {
        if (amountCents <= 0) return false
        val id = pendingPhysicalTransferDao.insert(
            PendingPhysicalTransferEntity(
                amountCents = amountCents,
                fromWalletId = fromWalletId,
                toWalletId = toWalletId,
                taskTitle = title.ifBlank { "待轉移" }
            )
        )
        collapseZeroNetPending()
        return pendingPhysicalTransferDao.getPending().any { it.id == id }
    }

    /**
     * 確認待轉移。
     * @param amountCents 這次實際轉移金額（絕對值，分）。
     *   - 淨額 ≠ 0：須 0 < amount ≤ |淨額|，依淨額方向 FIFO 部分結算
     *   - 淨額 = 0：忽略金額，結清全部待轉移（收支相抵）
     */
    suspend fun confirmPhysicalTransfers(amountCents: Long = Long.MAX_VALUE) {
        val pending = pendingPhysicalTransferDao.getPending()
        if (pending.isEmpty()) return
        val net = piggyNetCents(pending)
        if (net == 0L) {
            settleZeroNet(pending)
            bumpRevision()
            return
        }
        val max = kotlin.math.abs(net)
        val settle = if (amountCents == Long.MAX_VALUE) max else amountCents
        require(settle > 0) { "請輸入要轉移的金額" }
        require(settle <= max) { "不可超過上限 ${max / 100} 元" }
        val wantPositive = net > 0
        var left = settle
        for (item in pending) {
            if (left <= 0) break
            val contrib = piggyContributionCents(item)
            if (contrib == 0L) continue
            if (wantPositive && contrib <= 0) continue
            if (!wantPositive && contrib >= 0) continue
            val take = minOf(kotlin.math.abs(contrib), left)
            settlePhysicalTransferItem(item, take)
            left -= take
        }
        require(left == 0L) { "可結算金額不足" }
        collapseZeroNetPending()
        bumpRevision()
    }

    /** 淨額已相抵時結清全部待轉移，讓清單與錢包卡片消失。餘額不足則留下，稍後再試。 */
    private suspend fun collapseZeroNetPending() {
        val pending = pendingPhysicalTransferDao.getPending()
        if (pending.isEmpty() || piggyNetCents(pending) != 0L) return
        try {
            settleZeroNet(pending)
        } catch (_: Exception) {
            // 來源餘額不夠時保留待轉移，錢包頁仍可手動確認
        }
    }

    /** 先入現金再扣回，避免收支相抵時因順序而餘額不足 */
    private suspend fun settleZeroNet(pending: List<PendingPhysicalTransferEntity>) {
        val ordered = pending.sortedWith(
            compareBy<PendingPhysicalTransferEntity>(
                {
                    val contrib = piggyContributionCents(it)
                    when {
                        contrib > 0L -> 0
                        contrib < 0L -> 1
                        else -> 2
                    }
                },
                { it.createdAt },
                { it.id }
            )
        )
        ordered.forEach { settlePhysicalTransferItem(it, it.amountCents) }
    }

    /** 正值＝進入現金；負值＝離開現金（撲滿或中央） */
    fun piggyNetCents(items: List<PendingPhysicalTransferEntity>): Long =
        items.sumOf { piggyContributionCents(it) }

    private fun piggyContributionCents(item: PendingPhysicalTransferEntity): Long =
        item.livingNetCents()

    /** 結算一筆待轉移的部分或全額；全額則標記已確認，部分則改金額 */
    private suspend fun settlePhysicalTransferItem(item: PendingPhysicalTransferEntity, settleCents: Long) {
        require(settleCents > 0 && settleCents <= item.amountCents)
        val repayLoanId = item.repayLoanId()
        val borrowLoanId = item.borrowLoanId()
        when {
            repayLoanId != null -> {
                val loan = loanDao.getLoan(repayLoanId) ?: error("借據不存在")
                applyLoanRepay(loan, settleCents)
            }
            borrowLoanId != null -> {
                val loan = loanDao.getLoan(borrowLoanId) ?: error("借據不存在")
                disburseLoan(
                    loanId = borrowLoanId,
                    lenderId = item.fromWalletId,
                    borrowerId = item.toWalletId,
                    amountCents = settleCents,
                    dueThisMonth = isLoanDueThisMonth(loan)
                )
            }
            item.fromWalletId == WalletCodes.CENTRAL -> {
                payFromCentralImmediate(
                    item.toWalletId,
                    settleCents,
                    item.taskTitle.ifBlank { "待轉移" },
                    "PHYSICAL_TRANSFER"
                )
            }
            else -> settleGenericPhysicalTransfer(item.copy(amountCents = settleCents))
        }
        if (settleCents >= item.amountCents) {
            pendingPhysicalTransferDao.confirmById(item.id)
        } else {
            pendingPhysicalTransferDao.update(item.copy(amountCents = item.amountCents - settleCents))
        }
    }

    private suspend fun settleGenericPhysicalTransfer(item: PendingPhysicalTransferEntity) {
        val src = walletDao.getById(item.fromWalletId) ?: error("找不到來源戶頭")
        if (src.balanceCents < item.amountCents) {
            error("${src.name} 餘額不足")
        }
        walletDao.adjustBalance(item.fromWalletId, -item.amountCents)
        walletDao.adjustBalance(item.toWalletId, item.amountCents)
        transactionDao.insert(
            TransactionEntity(
                type = "PHYSICAL_TRANSFER",
                amountCents = item.amountCents,
                fromWalletId = item.fromWalletId,
                toWalletId = item.toWalletId,
                note = item.taskTitle.ifBlank { "待轉移" }
            )
        )
    }

    private suspend fun disburseLoan(
        loanId: Long,
        lenderId: String,
        borrowerId: String,
        amountCents: Long,
        dueThisMonth: Boolean
    ) {
        val src = walletDao.getById(lenderId) ?: error("出借方不存在")
        if (src.balanceCents < amountCents) error("${src.name} 餘額不足")
        walletDao.adjustBalance(lenderId, -amountCents)
        walletDao.adjustBalance(borrowerId, amountCents)
        transactionDao.insert(
            TransactionEntity(
                type = "LOAN_DISBURSE",
                amountCents = amountCents,
                fromWalletId = lenderId,
                toWalletId = borrowerId,
                note = "借款 #$loanId（還債=${if (dueThisMonth) "本月" else "下月"}）"
            )
        )
    }

    private suspend fun applyLoanRepay(loan: LoanEntity, principalPaid: Long) {
        val borrower = walletDao.getById(loan.borrowerWalletId) ?: error("借方不存在")
        if (borrower.balanceCents < principalPaid) error("借方餘額不足")
        walletDao.adjustBalance(loan.borrowerWalletId, -principalPaid)
        walletDao.adjustBalance(loan.lenderWalletId, principalPaid)
        val newRemaining = loan.remainingCents - principalPaid
        val newStatus = if (newRemaining <= 0) LoanStatus.REPAID else loan.status
        loanDao.updateLoan(
            loan.copy(
                remainingCents = newRemaining.coerceAtLeast(0),
                status = newStatus,
                penaltyRateBp = if (newStatus == LoanStatus.REPAID) 0 else loan.penaltyRateBp
            )
        )
        if (newStatus == LoanStatus.REPAID) {
            walletDao.setCanReceiveIncome(loan.borrowerWalletId, true)
        }
        transactionDao.insert(
            TransactionEntity(
                type = "LOAN_REPAY",
                amountCents = principalPaid,
                fromWalletId = loan.borrowerWalletId,
                toWalletId = loan.lenderWalletId,
                note = "還款 #${loan.id}"
            )
        )
    }

    private fun isLoanDueThisMonth(loan: LoanEntity): Boolean {
        val dueYm = YearMonth.from(Instant.ofEpochMilli(loan.dueAt).atZone(zone).toLocalDate())
        return !dueYm.isAfter(YearMonth.now(zone))
    }

    private suspend fun applyRepeatCooldown(task: TaskEntity, now: Long) {
        val rule = parseRepeatRule(task.repeatRuleJson)
        val nextAt = nextRepeatAt(rule, now)
        taskDao.update(
            task.copy(
                status = TaskStatus.OPEN,
                resolvedAt = now,
                repeatRuleJson = gson.toJson(rule.copy(nextAt = nextAt)),
                repeatable = true
            )
        )
    }

    fun nextRepeatAt(rule: RepeatRule, fromMs: Long = System.currentTimeMillis()): Long {
        val base = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDateTime()
        val next = when (rule.freq) {
            "weekly" -> base.plusWeeks(1)
            "every_n" -> base.plusDays((rule.n ?: 1).toLong().coerceAtLeast(1))
            else -> base.plusDays(1)
        }
        return next.atZone(zone).toInstant().toEpochMilli()
    }

    suspend fun loadSemester(): SemesterConfig {
        val rawDate = metaDao.get(MetaKeys.SEMESTER_WEEK1_MONDAY)?.value
            ?: AcademicCalendar.DEFAULT_WEEK1_MONDAY
        val monday = runCatching { LocalDate.parse(rawDate) }.getOrElse {
            LocalDate.parse(AcademicCalendar.DEFAULT_WEEK1_MONDAY)
        }.let { AcademicCalendar.normalizeWeek1Monday(it) }
        val weeks = metaDao.get(MetaKeys.SEMESTER_WEEKS)?.value?.toIntOrNull()
            ?.let { AcademicCalendar.coerceWeeks(it) }
            ?: AcademicCalendar.DEFAULT_WEEKS
        return SemesterConfig(week1Monday = monday, weeks = weeks)
    }

    suspend fun saveSemester(week1Monday: LocalDate, weeks: Int): SemesterConfig {
        val config = SemesterConfig(
            week1Monday = AcademicCalendar.normalizeWeek1Monday(week1Monday),
            weeks = AcademicCalendar.coerceWeeks(weeks)
        )
        metaDao.upsert(AppMetaEntity(MetaKeys.SEMESTER_WEEK1_MONDAY, config.week1Monday.toString()))
        metaDao.upsert(AppMetaEntity(MetaKeys.SEMESTER_WEEKS, config.weeks.toString()))
        bumpRevision()
        return config
    }

    suspend fun upsertPlan(id: String, title: String, note: String = ""): PlanEntity {
        val dao = planDao ?: error("計畫資料庫未就緒")
        val plan = PlanEntity(
            id = com.gino.mywallet.data.entity.PlanIds.requireValid(id),
            title = title.trim().ifBlank { id.trim() },
            note = note.trim()
        )
        dao.upsert(plan)
        bumpRevision()
        return plan
    }

    fun parseRepeatRule(json: String): RepeatRule =
        try {
            if (json.isBlank()) RepeatRule()
            else gson.fromJson(json, RepeatRule::class.java) ?: RepeatRule()
        } catch (_: Exception) {
            RepeatRule()
        }

    fun repeatRuleToJson(rule: RepeatRule): String = gson.toJson(rule)

    // ---------- 通行證 ----------

    private suspend fun advancePassOneTier(): PassAdvanceResult? = passLock.withLock {
        ensureMonthlyPassSeasonLocked()
        val season = passDao.getActiveSeason() ?: return@withLock null
        if (season.progress >= PASS_LENGTH) return@withLock null
        val nextIndex = season.progress + 1
        val tiers = passDao.getTiers(season.id)
        val tier = tiers.find { it.tierIndex == nextIndex }
        passDao.updateSeason(season.copy(progress = nextIndex.coerceAtMost(PASS_LENGTH)))
        if (tier == null) {
            return@withLock PassAdvanceResult(nextIndex, "—", claimedReward = false)
        }
        val rewards = parseRewards(tier.rewardJson)
        if (rewards.isEmpty() && !tier.claimed) {
            passDao.updateTier(tier.copy(claimed = true))
        }
        PassAdvanceResult(
            tierIndex = nextIndex,
            label = tier.label,
            claimedReward = rewards.isNotEmpty()
        )
    }

    suspend fun claimPassTier(tierId: Long): PassAdvanceResult = passLock.withLock {
        val season = passDao.getActiveSeason() ?: error("尚無進行中賽季")
        val tier = passDao.getTiers(season.id).find { it.id == tierId } ?: error("通行證格不存在")
        require(tier.tierIndex <= season.progress) { "尚未解鎖此格" }
        require(!tier.claimed) { "已領取" }
        val rewards = parseRewards(tier.rewardJson)
        require(rewards.isNotEmpty()) { "休息格無需領取" }
        val marked = passDao.markClaimedIfUnclaimed(tier.id)
        require(marked == 1) { "已領取" }
        val prizes = try {
            applyRewards(rewards, allowedCentralOut = true)
        } catch (e: Exception) {
            passDao.updateTier(tier.copy(claimed = false))
            throw e
        }
        val hint = prizes.joinToString("") { it.arrowLabel() }
        bumpRevision()
        PassAdvanceResult(tier.tierIndex, tier.label, claimedReward = true, prizeHint = hint)
    }

    suspend fun ensureMonthlyPassSeason(today: LocalDate = LocalDate.now(zone)) {
        passLock.withLock { ensureMonthlyPassSeasonLocked(today) }
    }

    private suspend fun ensureMonthlyPassSeasonLocked(today: LocalDate = LocalDate.now(zone)) {
        settleMonthlyLoanInterest(today)
        val ym = YearMonth.from(today)
        val title = "通行證 ${ym}"
        val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = monthEndMillis(ym)
        val active = passDao.getActiveSeason()
        if (active != null && active.title == title && active.endsAt >= System.currentTimeMillis()) {
            return
        }
        passDao.deactivateAllSeasons()
        val seasonId = passDao.insertSeason(
            PassSeasonEntity(title = title, startsAt = start, endsAt = end, progress = 0, active = true)
        )
        passDao.insertTiers(buildPassTiers(seasonId))
        metaDao.upsert(AppMetaEntity(MetaKeys.PASS_POOL_VERSION, PASS_POOL_VERSION))
    }

    /**
     * 獎池程式碼有改（PASS_POOL_VERSION 變了）才覆寫本月格，保留進度與已領狀態。
     * 啟動時不要每次重建，否則已領旗標會被併發刪表清掉，獎勵就能重複領。
     */
    suspend fun refreshCurrentPassRewards(today: LocalDate = LocalDate.now(zone)) {
        passLock.withLock {
            ensureMonthlyPassSeasonLocked(today)
            val stored = metaDao.get(MetaKeys.PASS_POOL_VERSION)?.value
            if (stored == PASS_POOL_VERSION) return@withLock
            if (stored == null) {
                // 舊資料沒有版本標記：不要刪表，避免把已領狀態清掉
                metaDao.upsert(AppMetaEntity(MetaKeys.PASS_POOL_VERSION, PASS_POOL_VERSION))
                return@withLock
            }
            val season = passDao.getActiveSeason() ?: return@withLock
            val oldClaimed = passDao.getTiers(season.id).associate { it.tierIndex to it.claimed }
            val fresh = buildPassTiers(season.id).map { t ->
                t.copy(claimed = oldClaimed[t.tierIndex] == true)
            }
            passDao.replaceTiers(season.id, fresh)
            if (season.progress > PASS_LENGTH) {
                passDao.updateSeason(season.copy(progress = PASS_LENGTH))
            }
            metaDao.upsert(AppMetaEntity(MetaKeys.PASS_POOL_VERSION, PASS_POOL_VERSION))
        }
    }

    private fun buildPassTiers(seasonId: Long): List<PassTierEntity> =
        PassTrack.specs().map { spec ->
            PassTierEntity(
                seasonId = seasonId,
                tierIndex = spec.index,
                label = spec.label,
                rewardJson = gson.toJson(spec.rewards)
            )
        }

    // ---------- 抽獎／打卡券 ----------

    /** 07:00 起可手動領 1 張拉霸券（每日一次）；取代舊 22h 冷卻。 */
    suspend fun canClaimMorningSlot(nowMs: Long = System.currentTimeMillis()): Boolean {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        if (now.toLocalTime().hour < 7) return false
        val today = now.toLocalDate().toString()
        return metaDao.get(MetaKeys.CHECKIN_SLOT_CLAIM_DAY)?.value != today
    }

    suspend fun claimMorningSlotTicket(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!canClaimMorningSlot(nowMs)) return false
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()
        addTickets(TicketKind.SLOT, 1)
        metaDao.upsert(AppMetaEntity(MetaKeys.CHECKIN_SLOT_CLAIM_DAY, today))
        return true
    }

    /** @deprecated 改用 [claimMorningSlotTicket] */
    suspend fun claimCooldownTicketIfNeeded(): TicketKind? {
        if (!claimMorningSlotTicket()) return null
        return TicketKind.SLOT
    }

    /** @deprecated 改用 claimMorningSlotTicket */
    suspend fun claimDailyFreeTicketIfNeeded(): Boolean = claimMorningSlotTicket()

    suspend fun addTickets(kind: TicketKind, n: Int) {
        if (n <= 0) return
        val state = lotteryDao.getState() ?: LotteryStateEntity()
        lotteryDao.upsertState(
            when (kind) {
                TicketKind.SLOT -> state.copy(slotTickets = state.slotTickets + n)
                TicketKind.BJ -> state.copy(bjTickets = state.bjTickets + n)
                TicketKind.SCRATCH -> state.copy(scratchTickets = state.scratchTickets + n)
                TicketKind.DRAW -> state.copy(drawTickets = state.drawTickets + n)
                TicketKind.LOTTO -> state.copy(lottoTickets = state.lottoTickets + n)
                TicketKind.MATCH -> state.copy(matchTickets = state.matchTickets + n)
                TicketKind.MATH -> state.copy(mathTickets = state.mathTickets + n)
            }
        )
        bumpRevision()
    }

    suspend fun grantTicketMap(map: Map<TicketKind, Int>): String {
        if (map.isEmpty()) return ""
        map.forEach { (k, n) -> addTickets(k, n) }
        return GamePrizes.formatTicketMap(map)
    }

    /** 舊 API：加拉霸券 */
    suspend fun addLotteryTickets(n: Int) = addTickets(TicketKind.SLOT, n)

    suspend fun consumeTickets(kind: TicketKind, n: Int) {
        require(n > 0) { "消耗張數須 > 0" }
        val state = lotteryDao.getState() ?: error("無抽獎狀態")
        when (kind) {
            TicketKind.SLOT -> {
                if (state.slotTickets < n) error("沒有${kind.symbol}次數")
                lotteryDao.upsertState(state.copy(slotTickets = state.slotTickets - n))
            }
            TicketKind.BJ -> {
                if (state.bjTickets < n) error("沒有${kind.symbol}次數")
                lotteryDao.upsertState(state.copy(bjTickets = state.bjTickets - n))
            }
            TicketKind.SCRATCH -> {
                if (state.scratchTickets < n) error("沒有${kind.symbol}次數")
                lotteryDao.upsertState(state.copy(scratchTickets = state.scratchTickets - n))
            }
            TicketKind.DRAW -> {
                if (state.drawTickets < n) error("沒有${kind.symbol}次數")
                lotteryDao.upsertState(state.copy(drawTickets = state.drawTickets - n))
            }
            TicketKind.LOTTO -> {
                if (state.lottoTickets < n) error("沒有${kind.symbol}次數")
                lotteryDao.upsertState(state.copy(lottoTickets = state.lottoTickets - n))
            }
            TicketKind.MATCH -> {
                if (state.matchTickets < n) error("沒有${kind.symbol}次數")
                lotteryDao.upsertState(state.copy(matchTickets = state.matchTickets - n))
            }
            TicketKind.MATH -> {
                if (state.mathTickets < n) error("沒有${kind.symbol}次數")
                lotteryDao.upsertState(state.copy(mathTickets = state.mathTickets - n))
            }
        }
    }

    suspend fun consumeLotteryTickets(n: Int) = consumeTickets(TicketKind.SLOT, n)

    /** 消耗 1 張樂透券，寫入 3 碼進入下一期 */
    suspend fun buyLottoTicket(pickRaw: String): LotteryStateEntity {
        val pick = LottoGame.normalizePick(pickRaw) ?: error("請輸入 3 位數字")
        consumeTickets(TicketKind.LOTTO, 1)
        val state = lotteryDao.getState() ?: LotteryStateEntity()
        val now = System.currentTimeMillis()
        val picks = parseLottoPicks(state.lottoPicksJson).toMutableList()
        picks.add(pick)
        val drawAt = if (picks.size > 1 && state.lottoDrawAt > now) {
            state.lottoDrawAt
        } else {
            LottoGame.nextDrawAt(now, zone)
        }
        val next = state.copy(
            lottoPending = picks.size,
            lottoPicksJson = gson.toJson(picks),
            lottoDrawAt = drawAt
        )
        lotteryDao.upsertState(next)
        return next
    }

    suspend fun hasDueLottoDraw(nowMs: Long = System.currentTimeMillis()): Boolean {
        val state = lotteryDao.getState() ?: return false
        return LottoGame.dueDrawAt(state.lottoDrawAt, nowMs) != null &&
            parseLottoPicks(state.lottoPicksJson).isNotEmpty()
    }

    /**
     * 手動結算已到期樂透。發券（expandEvBudget），0 碼無獎。
     * 通知只提醒，不自動呼叫此函式。
     */
    suspend fun settleDueLottoDraws(nowMs: Long = System.currentTimeMillis()): String? {
        var state = lotteryDao.getState() ?: return null
        var summary: String? = null
        while (true) {
            val due = LottoGame.dueDrawAt(state.lottoDrawAt, nowMs) ?: break
            val picks = parseLottoPicks(state.lottoPicksJson)
            if (picks.isEmpty()) {
                state = state.copy(lottoPending = 0, lottoPicksJson = "[]", lottoDrawAt = 0L)
                lotteryDao.upsertState(state)
                break
            }
            val win = LottoGame.drawWinning()
            var totalEv = 0
            val parts = mutableListOf<String>()
            picks.forEach { pick ->
                val result = LottoGame.resolvePick(pick, win)
                totalEv += result.ticketEvYuan
                val granted = if (result.ticketEvYuan > 0) {
                    grantTicketMap(GamePrizes.expandEvBudget(result.ticketEvYuan))
                } else {
                    "無"
                }
                parts.add("${pick}→${result.matches}碼 $granted")
                transactionDao.insert(
                    TransactionEntity(
                        type = "LOTTO",
                        amountCents = 0,
                        note = "${TicketKind.LOTTO.symbol} $pick 對 $win（${result.matches}碼）$granted"
                    )
                )
            }
            summary = "開獎 $win · ${picks.size}注 · 券EV≈$totalEv · ${parts.joinToString(" ")}"
            state = state.copy(
                lottoPending = 0,
                lottoPicksJson = "[]",
                lottoDrawAt = 0L,
                lottoLastResult = summary
            )
            lotteryDao.upsertState(state)
        }
        return summary
    }

    fun parseLottoPicks(json: String): List<String> =
        try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(json, type)?.filter { it.length == 3 } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    fun nextLottoDrawAt(nowMs: Long = System.currentTimeMillis()): Long =
        LottoGame.nextDrawAt(nowMs, zone)

    // ---------- 輪盤／消消樂 ----------

    fun rouletteIdle(): RouletteState = RouletteGame.idle()

    /**
     * 扣 1 🧮、注入底金、進入旋轉。
     * READY：洗新盤後再開；LIVE：同盤繼續。
     */
    suspend fun rouletteSpin(
        current: RouletteState,
        random: kotlin.random.Random = kotlin.random.Random.Default
    ): RouletteState {
        require(current.phase != RoulettePhase.SPINNING) { "旋轉中" }
        require(current.phase != RoulettePhase.SETTLE) { "請先結算本輪" }
        require(!current.needsForcedCashOut) { "請先結算本輪" }
        consumeTickets(TicketKind.MATH, 1)
        val fresh = current.phase == RoulettePhase.READY
        var state = if (fresh) RouletteGame.newRoundBoard(random) else current
        state = state.copy(
            potCents = state.potCents + RouletteGame.BASE_INJECT_CENTS,
            ticketsThisRound = if (fresh) 1 else state.ticketsThisRound + 1,
            lastDeltaCents = RouletteGame.BASE_INJECT_CENTS,
            lastKind = null
        )
        return RouletteGame.beginSpin(state, random)
    }

    /**
     * UI 動畫結束後套用停格效果。
     * 撞「結算」格：立刻入帳並洗新盤（回傳盤面 lastKind=SETTLE、lastDeltaCents=獎金便於 UI 提示）。
     */
    suspend fun rouletteResolve(current: RouletteState): RouletteState {
        val next = RouletteGame.resolveSpin(current)
        if (next.phase != RoulettePhase.SETTLE) return next
        val pot = next.potCents
        val tickets = next.ticketsThisRound
        if (pot > 0) {
            payPrizeFromCentral(
                pot,
                "ROULETTE",
                "${TicketKind.MATH.symbol} 輪盤 · 結算格 · 本輪 $tickets 券"
            )
        } else {
            transactionDao.insert(
                TransactionEntity(
                    type = "ROULETTE",
                    amountCents = 0,
                    note = "${TicketKind.MATH.symbol} 輪盤 · 結算格 \$0 · 本輪 $tickets 券"
                )
            )
        }
        return RouletteGame.cashOutBoard().copy(
            lastKind = RouletteCellKind.SETTLE,
            lastDeltaCents = pot,
            lastLandedIndex = next.lastLandedIndex,
            ticketsThisRound = 0
        )
    }

    suspend fun rouletteCashOut(current: RouletteState): Priced<RouletteState> {
        require(
            current.phase == RoulettePhase.LIVE ||
                (current.phase == RoulettePhase.READY && current.potCents > 0)
        ) { "無可結算金額" }
        require(current.potCents > 0) { "金額為 0" }
        val pot = current.potCents
        val prize = payPrizeFromCentral(
            pot,
            "ROULETTE",
            "${TicketKind.MATH.symbol} 輪盤 · 結算 · 本輪 ${current.ticketsThisRound} 券"
        )
        return Priced(RouletteGame.cashOutBoard(), prize)
    }

    fun match3Idle(): Match3State = Match3Game.idle()

    fun match3Deal(): Match3State = Match3Game.newBoard()

    /**
     * 有效交換：扣 1 🍬、入帳，並回傳逐步動畫。
     * @return null = 無效交換
     */
    suspend fun match3Swap(
        current: Match3State,
        from: Int,
        to: Int
    ): Pair<Priced<Match3State>, Match3Resolution>? {
        val resolution = Match3Game.trySwap(current, from, to) ?: return null
        consumeTickets(TicketKind.MATCH, 1)
        val next = Match3Game.applyResolution(current, resolution).copy(animating = true)
        val prize = if (resolution.payoutCents > 0) {
            payPrizeFromCentral(
                resolution.payoutCents,
                "MATCH3",
                "${TicketKind.MATCH.symbol} 消${resolution.cleared} 連鎖${resolution.cascades}"
            )
        } else null
        return Priced(next, prize) to resolution
    }

    fun blackjackNewRound(): Blackjack21State = Blackjack21.newRound()

    suspend fun blackjackAdvance(current: Blackjack21State): Priced<Blackjack21State> {
        require(current.phase == BjPhase.PLAYING) { "非進行中" }
        consumeTickets(TicketKind.BJ, 1)
        val next = Blackjack21.advance(current, Blackjack21.rollStep())
        val prize = if (next.result == BjResult.WIN_21) {
            payPrizeFromCentral(GamePrizes.BJ_JACKPOT_CENTS, "BJ21", "${TicketKind.BJ.symbol} 二十一點・21點")
        } else {
            null
        }
        return Priced(next, prize)
    }

    suspend fun blackjackStand(current: Blackjack21State): Priced<Blackjack21State> {
        val next = Blackjack21.stand(current)
        val prize = when (next.result) {
            BjResult.WIN_BEAT ->
                payPrizeFromCentral(GamePrizes.BJ_BEAT_CENTS, "BJ21", "${TicketKind.BJ.symbol} 擊敗莊家")
            BjResult.LOSE -> {
                if (next.potCents > 0) {
                    payPrizeFromCentral(next.potCents, "BJ21", "${TicketKind.BJ.symbol} 停牌")
                } else {
                    null
                }
            }
            else -> null
        }
        return Priced(next, prize)
    }

    fun tripleDrawIdle(): TripleDrawState = TripleDrawState()

    suspend fun tripleDrawDeal(): TripleDrawState {
        consumeTickets(TicketKind.DRAW, 1)
        return TripleDrawGame.deal()
    }

    fun tripleDrawToggle(current: TripleDrawState, index: Int): TripleDrawState =
        TripleDrawGame.toggleDiscard(current, index)

    suspend fun tripleDrawRedraw(current: TripleDrawState): TripleDrawState {
        require(current.phase == TripleDrawPhase.PLAYING)
        consumeTickets(TicketKind.DRAW, 1)
        return TripleDrawGame.redraw(current)
    }

    suspend fun tripleDrawSettle(current: TripleDrawState): Priced<TripleDrawState> {
        val next = TripleDrawGame.settle(current)
        val prize = if (next.payoutCents > 0) {
            payPrizeFromCentral(
                next.payoutCents,
                "TRIPLE_DRAW",
                "${TicketKind.DRAW.symbol} ${next.hand.label}"
            )
        } else {
            transactionDao.insert(
                TransactionEntity(
                    type = "TRIPLE_DRAW",
                    amountCents = 0,
                    note = "${TicketKind.DRAW.symbol} ${next.hand.label} · 沒中"
                )
            )
            null
        }
        return Priced(next, prize)
    }

    fun hiLoNewRound(): HiLoState = HiLoGame.newBase()

    suspend fun hiLoGuess(current: HiLoState, pick: HiLoPick): Priced<HiLoState> {
        consumeTickets(TicketKind.SCRATCH, 1)
        val result = HiLoGame.resolve(current, pick)
        val prize = when (result.outcome) {
            HiLoOutcome.WIN, HiLoOutcome.TIE -> {
                payPrizeFromCentral(
                    result.payoutCents,
                    "HILO",
                    "${TicketKind.SCRATCH.symbol} ${pickLabel(pick)} ${result.base}→${result.reveal}"
                )
            }
            HiLoOutcome.LOSE -> {
                transactionDao.insert(
                    TransactionEntity(
                        type = "HILO",
                        amountCents = 0,
                        note = "${TicketKind.SCRATCH.symbol} ${pickLabel(pick)} ${result.base}→${result.reveal} · 沒中"
                    )
                )
                null
            }
            null -> null
        }
        return Priced(result, prize)
    }

    private fun pickLabel(pick: HiLoPick): String = when (pick) {
        HiLoPick.HIGH -> "更高"
        HiLoPick.LOW -> "更低"
    }

    /** 載入或開啟當期每日五字（中午 12:00 重置） */
    suspend fun ensureDailyWord(nowMs: Long = System.currentTimeMillis()): DailyWordState {
        val period = DailyWord.periodId(nowMs, zone)
        val raw = metaDao.get(MetaKeys.GAME_WORDLE_JSON)?.value
        if (raw != null) {
            runCatching { gson.fromJson(raw, DailyWordState::class.java) }.getOrNull()?.let { saved ->
                if (saved.periodId == period) return enrichDailyWord(saved)
            }
        }
        val fresh = DailyWord.newRound(period)
        persistDailyWord(fresh)
        return fresh
    }

    /** 舊存檔補上翻譯／獎金欄位 */
    private fun enrichDailyWord(state: DailyWordState): DailyWordState {
        if (state.answerZh.isNotBlank() && state.prizeYuan > 0 && state.tier.isNotBlank()) return state
        val fromTable = DailyWord.lookupAnswer(state.answer) ?: return state
        return state.copy(
            answerZh = state.answerZh.ifBlank { fromTable.zh },
            tier = state.tier.ifBlank { fromTable.tier.name },
            prizeYuan = if (state.prizeYuan > 0) state.prizeYuan else fromTable.tier.prizeYuan()
        )
    }

    suspend fun submitDailyWordGuess(raw: String, nowMs: Long = System.currentTimeMillis()): Priced<DailyWordState> {
        var state = ensureDailyWord(nowMs)
        require(state.phase == DailyWordPhase.PLAYING) { "本局已結束，等中午 12:00 重置" }
        state = DailyWord.submitGuess(state, raw)
        var prize: RoutedPrize? = null
        var ticketGrant = ""
        if (state.phase == DailyWordPhase.WON && !state.rewarded) {
            val yuan = state.prizeYuan.coerceAtLeast(1)
            val zh = state.answerZh.ifBlank { state.answer }
            ticketGrant = grantTicketMap(GamePrizes.expandEvBudget(yuan))
            transactionDao.insert(
                TransactionEntity(
                    type = "WORDLE",
                    amountCents = 0,
                    note = "🔠 每日五字猜中 · ${state.answer}（$zh）$ticketGrant"
                )
            )
            state = state.copy(rewarded = true)
        } else if (state.phase == DailyWordPhase.LOST) {
            val zh = state.answerZh.ifBlank { state.answer }
            transactionDao.insert(
                TransactionEntity(
                    type = "WORDLE",
                    amountCents = 0,
                    note = "🔠 每日五字 · 答案 ${state.answer}（$zh）"
                )
            )
        }
        persistDailyWord(state)
        return Priced(state, prize, ticketGrant)
    }

    private suspend fun persistDailyWord(state: DailyWordState) {
        metaDao.upsert(AppMetaEntity(MetaKeys.GAME_WORDLE_JSON, gson.toJson(state)))
    }

    /** 中央 → 動態抽選戶頭（現金仍待轉移）；回傳入帳目標供通知 */
    private suspend fun payPrizeFromCentral(amountCents: Long, txType: String, note: String): RoutedPrize? {
        if (amountCents <= 0) return null
        val target = PrizeRouter.pickTarget(walletDao.getAll())
        val tagged = "$note → ${target.name}"
        payFromCentral(target.id, amountCents, tagged, txType)
        val deferred = target.id == WalletCodes.LIVING &&
            pendingPhysicalTransferDao.getPending().any {
                it.fromWalletId == WalletCodes.CENTRAL &&
                    it.toWalletId == WalletCodes.LIVING &&
                    it.taskTitle == tagged &&
                    it.amountCents == amountCents
            }
        return RoutedPrize(
            walletId = target.id,
            walletName = target.name,
            amountCents = amountCents,
            deferred = deferred
        )
    }

    suspend fun drawSlot(): SlotDrawResult {
        val state = lotteryDao.getState() ?: error("無抽獎狀態")
        if (state.slotTickets <= 0) error("沒有${TicketKind.SLOT.symbol}次數")
        val newCount = state.drawCount + 1
        val boosted = newCount % 10 == 0
        lotteryDao.upsertState(state.copy(slotTickets = state.slotTickets - 1, drawCount = newCount))

        val target = PrizeRouter.pickTarget(walletDao.getAll())
        val symbol = weightedSymbol()
        val sameFirstTwo = Random.nextDouble() < SLOT_SAME_FIRST_TWO
        val reel0 = symbol
        val reel1 = if (sameFirstTwo) symbol else weightedSymbol(exclude = symbol)
        val reel2 = when {
            sameFirstTwo && Random.nextDouble() < SLOT_TRIPLE_GIVEN_PAIR -> symbol
            !sameFirstTwo -> {
                when (Random.nextInt(3)) {
                    0 -> reel0
                    1 -> reel1
                    else -> weightedSymbol(exclude = reel0, exclude2 = reel1)
                }
            }
            else -> weightedSymbol(exclude = symbol)
        }

        val result = resolveSlot(target.id, target.name, reel0, reel1, reel2, boosted)
        applySlotPrize(result)
        // 拉霸附贈其他遊戲次數
        grantSlotSideTickets(result)
        transactionDao.insert(
            TransactionEntity(
                type = "LOTTERY",
                amountCents = result.cashCents,
                toWalletId = result.walletId,
                note = "${TicketKind.SLOT.symbol} 抽中：${result.label} → ${result.walletName}"
            )
        )
        return result
    }

    /** 中小獎／大獎時有機會附贈 🃏／🎫 */
    private suspend fun grantSlotSideTickets(result: SlotDrawResult) {
        when (result.tier) {
            SlotTiers.BIG -> {
                if (Random.nextDouble() < SLOT_SIDE_BIG) addTickets(TicketKind.SCRATCH, 1)
                if (Random.nextDouble() < SLOT_SIDE_BIG * 0.7) addTickets(TicketKind.BJ, 1)
            }
            SlotTiers.SMALL -> {
                if (Random.nextDouble() < SLOT_SIDE_SMALL) {
                    addTickets(if (Random.nextBoolean()) TicketKind.BJ else TicketKind.SCRATCH, 1)
                }
            }
            else -> {
                if (Random.nextDouble() < SLOT_SIDE_MISS) addTickets(TicketKind.BJ, 1)
            }
        }
    }

    private fun weightedSymbol(exclude: String? = null, exclude2: String? = null): String {
        val pool = SLOT_WEIGHTS.filter { it.first != exclude && it.first != exclude2 }
        val list = pool.ifEmpty { SLOT_WEIGHTS }
        val total = list.sumOf { it.second }
        var r = Random.nextInt(total)
        return list.first {
            r -= it.second
            r < 0
        }.first
    }

    private fun resolveSlot(
        walletId: String,
        walletName: String,
        a: String,
        b: String,
        c: String,
        boosted: Boolean
    ): SlotDrawResult {
        val allSame = a == b && b == c
        val twoSame = !allSame && (a == b || a == c || b == c)
        val nearMiss = a == b && a != c
        val mult = if (boosted) 3.0 else 1.0
        return when {
            allSame -> {
                when (a) {
                    "7", "⭐", "$" -> {
                        val cash = ((SLOT_CASH[a] ?: 0L) * mult).toLong()
                        val tier = if (a == "⭐" || cash >= 25_00L || boosted) SlotTiers.BIG else SlotTiers.SMALL
                        SlotDrawResult(walletId, walletName, a, b, c, "${a} → ${fmtYuan(cash)}${if (boosted) "（×3）" else ""}", boosted, cash, tier, false)
                    }
                    "💎" -> SlotDrawResult(
                        walletId, walletName, a, b, c,
                        "降息／補償",
                        boosted, 0, SlotTiers.BIG, false
                    )
                    else -> SlotDrawResult(walletId, walletName, a, b, c, "銘謝惠顧", boosted, 0, SlotTiers.MISS, false)
                }
            }
            twoSame -> {
                val cash = (SLOT_PAIR_CASH_CENTS * mult).toLong()
                SlotDrawResult(
                    walletId, walletName, a, b, c,
                    "兩符 → ${fmtYuan(cash)}${if (boosted) "（×3）" else ""}",
                    boosted, cash, SlotTiers.SMALL, nearMiss
                )
            }
            else -> SlotDrawResult(walletId, walletName, a, b, c, "沒中", boosted, 0, SlotTiers.MISS, false)
        }
    }

    private suspend fun applySlotPrize(result: SlotDrawResult) {
        when {
            result.reel0 == result.reel1 && result.reel1 == result.reel2 && result.reel0 == "💎" -> {
                val cut = tryCutLoanRate(result.walletId)
                if (!cut) {
                    payFromCentral(
                        result.walletId,
                        if (result.boosted) SLOT_GEM_FALLBACK_CENTS * 3 else SLOT_GEM_FALLBACK_CENTS,
                        "拉霸 💎 補償",
                        "REWARD_TRANSFER"
                    )
                }
            }
            result.cashCents > 0 -> payFromCentral(
                result.walletId,
                result.cashCents,
                "拉霸獎勵 → ${result.walletName}",
                "REWARD_TRANSFER"
            )
        }
    }

    private suspend fun tryCutLoanRate(walletId: String): Boolean {
        val loan = loanDao.getLoans().firstOrNull {
            !it.isCentralOverdraft() &&
                (it.status == LoanStatus.ACTIVE || it.status == LoanStatus.DEFAULTED) &&
                (it.borrowerWalletId == walletId || it.lenderWalletId == WalletCodes.CENTRAL && it.borrowerWalletId == walletId) &&
                it.monthlyRateBp > 0
        } ?: return false
        loanDao.updateLoan(loan.copy(monthlyRateBp = (loan.monthlyRateBp - 50).coerceAtLeast(0)))
        transactionDao.insert(
            TransactionEntity(
                type = "LOAN_RATE_CUT",
                amountCents = 0,
                fromWalletId = loan.lenderWalletId,
                toWalletId = loan.borrowerWalletId,
                note = "💎 借據#${loan.id} 月息 -0.5%"
            )
        )
        return true
    }

    private fun fmtYuan(cents: Long): String = "$${cents / 100}"

    // ---------- 獎懲 ----------

    suspend fun applyRewards(items: List<RewardItem>, allowedCentralOut: Boolean = true): List<RoutedPrize> {
        val routed = mutableListOf<RoutedPrize>()
        items.forEach { item ->
            when (item.kind) {
                "TRANSFER" -> {
                    val amount = item.amountCents ?: return@forEach
                    val from = item.fromWalletId ?: WalletCodes.CENTRAL
                    val to = item.toWalletId ?: return@forEach
                    if (from == WalletCodes.CENTRAL && !allowedCentralOut) error("此獎懲不可從中央轉出")
                    if (from == WalletCodes.CENTRAL) {
                        payFromCentral(to, amount, "獎懲轉帳", "REWARD_TRANSFER")
                    } else if (shouldDeferPhysicalTransfer(from, to)) {
                        recordPhysicalTransfer(from, to, amount, "獎懲轉帳")
                    } else {
                        val src = walletDao.getById(from) ?: return@forEach
                        if (src.balanceCents < amount) return@forEach
                        walletDao.adjustBalance(from, -amount)
                        walletDao.adjustBalance(to, amount)
                        transactionDao.insert(
                            TransactionEntity(
                                type = "REWARD_TRANSFER",
                                amountCents = amount,
                                fromWalletId = from,
                                toWalletId = to,
                                note = "獎懲轉帳"
                            )
                        )
                    }
                }
                "PRIZE" -> {
                    val amount = item.amountCents ?: return@forEach
                    if (!allowedCentralOut) error("此獎懲不可從中央轉出")
                    payPrizeFromCentral(amount, "REWARD_TRANSFER", "動態獎金")?.let { routed += it }
                }
                "ALLOCATION_DELTA" -> {
                    val id = item.walletId ?: return@forEach
                    val delta = item.deltaBp ?: return@forEach
                    applyAllocationDeltas(mapOf(id to delta))
                }
                "LOTTERY_TICKET" -> addTickets(TicketKind.SLOT, item.tickets ?: 1)
                "TICKET_SLOT" -> addTickets(TicketKind.SLOT, item.tickets ?: 1)
                "TICKET_BJ" -> addTickets(TicketKind.BJ, item.tickets ?: 1)
                "TICKET_SCRATCH" -> addTickets(TicketKind.SCRATCH, item.tickets ?: 1)
                "TICKET_DRAW" -> addTickets(TicketKind.DRAW, item.tickets ?: 1)
                "TICKET_LOTTO" -> addTickets(TicketKind.LOTTO, item.tickets ?: 1)
                "TICKET_MATCH" -> addTickets(TicketKind.MATCH, item.tickets ?: 1)
                "TICKET_MATH" -> addTickets(TicketKind.MATH, item.tickets ?: 1)
                "TICKET_BUDGET" -> {
                    val yuan = ((item.amountCents ?: 0L) / 100L).toInt().coerceAtLeast(0)
                    grantTicketMap(GamePrizes.expandEvBudget(yuan))
                }
                "TICKET_LEVEL" -> {
                    val pack = GamePrizes.expandTicketLevel(item.level ?: 1)
                    pack.forEach { (kind, n) -> addTickets(kind, n) }
                }
                "TAX_RELIEF" -> {
                    val p = item.reliefPercent ?: return@forEach
                    val cur = charityReliefPercent()
                    metaDao.upsert(AppMetaEntity(MetaKeys.CHARITY_RELIEF_PERCENT, (cur + p).toString()))
                }
            }
        }
        return routed
    }

    fun parseRewards(json: String): List<RewardItem> =
        try {
            gson.fromJson(json, rewardListType) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    fun rewardsToJson(items: List<RewardItem>): String = gson.toJson(items)

    // ---------- 累積里程碑（打卡） ----------

    suspend fun milestoneSnapshot(): MilestoneSnapshot {
        val done = taskDao.countDoneAll()
        val focusMs = focusSessionDao?.sumEffectiveMsAll() ?: 0L
        val redeemedTasks = metaDao.get(MetaKeys.MILESTONE_TASKS_REDEEMED)?.value?.toIntOrNull() ?: 0
        val redeemedFocus = metaDao.get(MetaKeys.MILESTONE_FOCUS_REDEEMED_MS)?.value?.toLongOrNull() ?: 0L
        return MilestoneSnapshot(
            doneTasks = done,
            redeemedTasks = redeemedTasks,
            claimableTasks = MilestoneTrack.claimableTaskCount(done, redeemedTasks),
            taskProgress = MilestoneTrack.taskProgressInStep(done, redeemedTasks),
            focusMs = focusMs,
            redeemedFocusMs = redeemedFocus,
            claimableFocus = MilestoneTrack.claimableFocusCount(focusMs, redeemedFocus),
            focusProgressMs = MilestoneTrack.focusProgressInStepMs(focusMs, redeemedFocus)
        )
    }

    /** 一次領完所有可領的任務里程碑；回傳發券摘要 */
    suspend fun claimTaskMilestones(maxClaims: Int = Int.MAX_VALUE): String {
        val snap = milestoneSnapshot()
        val n = snap.claimableTasks.coerceAtMost(maxClaims).coerceAtLeast(0)
        if (n <= 0) return ""
        val parts = mutableListOf<String>()
        repeat(n) {
            parts += grantTicketMap(GamePrizes.expandEvBudget(MilestoneTrack.REWARD_EV_YUAN))
        }
        val next = MilestoneTrack.afterClaimTasks(snap.redeemedTasks, n)
        metaDao.upsert(AppMetaEntity(MetaKeys.MILESTONE_TASKS_REDEEMED, next.toString()))
        return parts.filter { it.isNotBlank() }.joinToString(" · ")
    }

    suspend fun claimFocusMilestones(maxClaims: Int = Int.MAX_VALUE): String {
        val snap = milestoneSnapshot()
        val n = snap.claimableFocus.coerceAtMost(maxClaims).coerceAtLeast(0)
        if (n <= 0) return ""
        val parts = mutableListOf<String>()
        repeat(n) {
            parts += grantTicketMap(GamePrizes.expandEvBudget(MilestoneTrack.REWARD_EV_YUAN))
        }
        val next = MilestoneTrack.afterClaimFocusMs(snap.redeemedFocusMs, n)
        metaDao.upsert(AppMetaEntity(MetaKeys.MILESTONE_FOCUS_REDEEMED_MS, next.toString()))
        return parts.filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** 下一場 07:00 拉霸券提醒時刻 */
    fun nextMorningSlotAt(nowMs: Long = System.currentTimeMillis()): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        var day = now.toLocalDate()
        var candidate = day.atTime(7, 0).atZone(zone)
        if (!candidate.toInstant().isAfter(now.toInstant())) {
            day = day.plusDays(1)
            candidate = day.atTime(7, 0).atZone(zone)
        }
        return candidate.toInstant().toEpochMilli()
    }

    // ---------- 專注結算 ----------

    /**
     * @param cashYuan 可為負（扣伙雜費）
     * @param effectiveMs 專注有效毫秒
     * @param grantTickets 短期專注不發券
     * @return Pair(券包, 現金是否已排入待轉移)
     */
    suspend fun settleFocus(
        cashYuan: Int,
        effectiveMs: Long,
        grantTickets: Boolean = true
    ): Pair<Map<TicketKind, Int>, Boolean> {
        val cents = cashYuan.toLong() * 100L
        val deferred = when {
            cents > 0L -> recordPhysicalTransfer(
                WalletCodes.CENTRAL,
                WalletCodes.LIVING,
                cents,
                "專注結算 +${cashYuan} 元"
            )
            cents < 0L -> recordPhysicalTransfer(
                WalletCodes.LIVING,
                WalletCodes.CENTRAL,
                -cents,
                "專注結算 ${cashYuan} 元"
            )
            else -> false
        }
        if (!grantTickets) return emptyMap<TicketKind, Int>() to deferred
        val units = FocusRules.ticketUnits(effectiveMs)
        val mix = GamePrizes.expandEvBudget(units * GamePrizes.EV_PER_LEVEL_YUAN)
        val extra = FocusRules.guaranteedTickets(effectiveMs)
        val pack = GamePrizes.mergeTicketMaps(mix, extra)
        pack.forEach { (kind, n) -> addTickets(kind, n) }
        bumpRevision()
        return pack to deferred
    }

    /** 專注結算並寫入 focus_sessions（指令控制台／PC 暫代匯回用） */
    suspend fun settleFocusAndRecord(
        cashYuan: Int,
        effectiveMs: Long,
        grantTickets: Boolean = true,
        intent: String = "專注",
        category: String = "其他",
        endedAt: Long = System.currentTimeMillis()
    ): Pair<Map<TicketKind, Int>, Boolean> {
        val result = settleFocus(cashYuan, effectiveMs, grantTickets)
        focusSessionDao?.insert(
            FocusSessionEntity(
                intent = intent.trim().ifBlank { "專注" },
                category = category.trim().ifBlank { "其他" },
                linkedTaskIdsJson = "[]",
                startedAt = endedAt - effectiveMs.coerceAtLeast(0L),
                endedAt = endedAt,
                effectiveMs = effectiveMs.coerceAtLeast(0L),
                cashYuan = cashYuan
            )
        )
        return result
    }

    // ---------- 股票買賣（只動投資現金；市值不寫入錢包） ----------

    /** 買入：從投資現金扣款並記 STOCK_BUY；持股表由呼叫端更新 */
    suspend fun stockBuy(totalCents: Long, symbol: String) {
        require(totalCents > 0) { "金額需大於 0" }
        val w = walletDao.getById(WalletCodes.INVEST) ?: error("投資錢包不存在")
        require(w.balanceCents >= totalCents) {
            "投資現金不足（現金 ${fmtYuan(w.balanceCents)}）"
        }
        walletDao.adjustBalance(WalletCodes.INVEST, -totalCents)
        transactionDao.insert(
            TransactionEntity(
                type = "STOCK_BUY",
                amountCents = totalCents,
                fromWalletId = WalletCodes.INVEST,
                note = "買入 $symbol"
            )
        )
        bumpRevision()
    }

    /** 賣出：加回投資現金並記 STOCK_SELL */
    suspend fun stockSell(totalCents: Long, symbol: String) {
        require(totalCents > 0) { "金額需大於 0" }
        walletDao.adjustBalance(WalletCodes.INVEST, totalCents)
        transactionDao.insert(
            TransactionEntity(
                type = "STOCK_SELL",
                amountCents = totalCents,
                toWalletId = WalletCodes.INVEST,
                note = "賣出 $symbol"
            )
        )
        bumpRevision()
    }

    // ---------- 平帳 ----------

    suspend fun setBalanceAdjust(walletId: String, targetCents: Long) {
        require(
            walletId == WalletCodes.LIVING ||
                walletId == WalletCodes.CARD ||
                walletId == WalletCodes.INVEST
        ) {
            "平帳僅限現金、提款卡與投資"
        }
        require(targetCents >= 0)
        val w = walletDao.getById(walletId) ?: error("錢包不存在")
        val delta = targetCents - w.balanceCents
        if (delta == 0L) return
        walletDao.setBalance(walletId, targetCents)
        transactionDao.insert(
            TransactionEntity(
                type = "BALANCE_ADJUST",
                amountCents = kotlin.math.abs(delta),
                fromWalletId = if (delta < 0) walletId else null,
                toWalletId = if (delta > 0) walletId else null,
                note = "平帳調整至 ${fmtYuan(targetCents)}"
            )
        )
        bumpRevision()
    }

    /** Bot 用結構化摘要 JSON（mywallet-snapshot/v1） */
    suspend fun exportSnapshotJson(): String {
        val exportedAt = System.currentTimeMillis()
        val revision = currentRevision()
        val wallets = walletDao.getAll()
        val rules = allocationDao.getAll()
        val loans = loanDao.getLoans()
        val charity = charityStatus()
        val rates = walletRateDao.getAll()
        val tasks = taskDao.getAll()
        val courses = courseDao.getAll()
        val events = calendarEventDao.getAll()
        val pending = pendingPhysicalTransferDao.getPending()
        val semester = loadSemester()
        val today = LocalDate.now(zone)
        val ym = YearMonth.from(today)
        val monthStart = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthEnd = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthTx = transactionDao.getBetween(monthStart, monthEnd)
        val closing = wallets.associate { it.id to it.balanceCents }
        val digest = digestMonthCashflow(ym.toString(), closing, monthTx)
        val horizonEnd = today.plusDays(SnapshotSchema.EVENT_HORIZON_DAYS.toLong()).toString()
        val todayYmd = today.toString()

        val root = linkedMapOf<String, Any?>()
        root["schema"] = SnapshotSchema.ID
        root["exportedAt"] = exportedAt
        root["revision"] = revision
        root["wallets"] = wallets.map {
            linkedMapOf(
                "id" to it.id,
                "name" to it.name,
                "balanceCents" to it.balanceCents,
                "canReceiveIncome" to it.canReceiveIncome,
                "isCentral" to it.isCentral
            )
        }
        val holdings = stockDao?.getHoldings().orEmpty()
        if (holdings.isNotEmpty()) {
            val stockValue = holdings.sumOf { it.marketValueCents() }
            val investCash = wallets.find { it.id == WalletCodes.INVEST }?.balanceCents ?: 0L
            root["holdings"] = holdings.map { h ->
                linkedMapOf(
                    "symbol" to h.symbol,
                    "name" to h.name,
                    "sharesMilli" to h.sharesMilli,
                    "lastPriceCents" to h.lastPriceCents,
                    "marketValueCents" to h.marketValueCents(),
                    "costCents" to h.costCents
                )
            }
            root["holdingsMarketValueCents"] = stockValue
            root["investTotalCents"] = investCash + stockValue
        }
        root["rates"] = rates.map {
            linkedMapOf("walletId" to it.walletId, "monthlyRateBp" to it.monthlyRateBp)
        }
        root["allocation"] = rules.map {
            linkedMapOf(
                "walletId" to it.walletId,
                "percentBp" to it.percentBp,
                "isTaxShare" to it.isTaxShare
            )
        }
        root["charity"] = linkedMapOf(
            "month" to charity.month,
            "done" to charity.done,
            "ratePercent" to charity.ratePercent,
            "amountCents" to charity.amountCents
        )
        val debt = centralDebtStatus()
        root["centralDebt"] = linkedMapOf(
            "principalCents" to debt.principalCents,
            "interestCents" to debt.interestCents,
            "totalOwedCents" to debt.totalOwedCents
        )
        root["loans"] = loans.map {
            linkedMapOf(
                "id" to it.id,
                "lender" to it.lenderWalletId,
                "borrower" to it.borrowerWalletId,
                "remainingCents" to it.remainingCents,
                "principalCents" to it.principalCents,
                "monthlyRateBp" to it.monthlyRateBp,
                "status" to it.status,
                "dueAt" to it.dueAt
            )
        }
        val st = lotteryDao.getState()
        root["tickets"] = linkedMapOf(
            "SLOT" to (st?.slotTickets ?: 0),
            "BJ" to (st?.bjTickets ?: 0),
            "SCRATCH" to (st?.scratchTickets ?: 0),
            "DRAW" to (st?.drawTickets ?: 0),
            "LOTTO" to (st?.lottoTickets ?: 0),
            "MATCH" to (st?.matchTickets ?: 0),
            "MATH" to (st?.mathTickets ?: 0),
            "lottoPending" to (st?.lottoPending ?: 0),
            "drawCount" to (st?.drawCount ?: 0)
        )
        val season = passDao.getActiveSeason()
        root["pass"] = if (season != null) {
            val tiers = passDao.getTiers(season.id)
            linkedMapOf(
                "title" to season.title,
                "progress" to season.progress,
                "length" to PASS_LENGTH,
                "poolVersion" to PASS_POOL_VERSION,
                "tiers" to tiers.map { tier ->
                    linkedMapOf(
                        "index" to tier.tierIndex,
                        "label" to tier.label,
                        "claimed" to tier.claimed,
                        "unlocked" to (tier.tierIndex <= season.progress)
                    )
                }
            )
        } else null
        root["semester"] = linkedMapOf(
            "week1Monday" to semester.week1Monday.toString(),
            "weeks" to semester.weeks,
            "currentWeek" to AcademicCalendar.currentWeek(semester, today)
        )
        root["plans"] = planDao?.getAll().orEmpty().map {
            linkedMapOf("id" to it.id, "title" to it.title, "note" to it.note)
        }
        fun taskMap(t: TaskEntity) = linkedMapOf(
            "id" to t.id,
            "title" to t.title,
            "category" to t.category,
            "note" to t.note,
            "ticketLevel" to t.ticketLevel(),
            "repeatable" to t.repeatable,
            "rewardJson" to t.rewardJson,
            "penaltyJson" to t.penaltyJson,
            "plannedAt" to t.plannedAt,
            "planId" to t.planId,
            "status" to t.status
        )
        root["openTasks"] = tasks.filter { it.status == TaskStatus.OPEN }.map { taskMap(it) }
        root["taskHistory"] = taskDao.getRecentResolved(100).map { t ->
            linkedMapOf(
                "id" to t.id,
                "title" to t.title,
                "category" to t.category,
                "status" to t.status,
                "resolvedAt" to t.resolvedAt,
                "ticketLevel" to t.ticketLevel(),
                "planId" to t.planId
            )
        }
        root["courses"] = courses.map { c ->
            linkedMapOf(
                "id" to c.id,
                "title" to c.title,
                "weekday" to c.weekday,
                "periodStart" to c.periodStart,
                "periodEnd" to c.periodEnd,
                "location" to c.location,
                "weeks" to c.weekNumbers()
            )
        }
        root["events"] = events.filter { it.dateYmd >= todayYmd && it.dateYmd <= horizonEnd }.map { e ->
            linkedMapOf(
                "id" to e.id,
                "title" to e.title,
                "date" to e.dateYmd,
                "timeMinutes" to e.timeMinutes,
                "location" to e.location,
                "note" to e.note
            )
        }
        root["pendingTransfers"] = pending.map { p ->
            linkedMapOf(
                "id" to p.id,
                "amountCents" to p.amountCents,
                "from" to p.fromWalletId,
                "to" to p.toWalletId,
                "title" to p.taskTitle,
                "createdAt" to p.createdAt
            )
        }
        root["cashflow"] = linkedMapOf(
            "month" to digest.month,
            "wallets" to digest.wallets.map {
                linkedMapOf(
                    "walletId" to it.walletId,
                    "openingCents" to it.openingCents,
                    "closingCents" to it.closingCents,
                    "netCents" to it.netCents
                )
            },
            "buckets" to digest.buckets.map {
                linkedMapOf("key" to it.key, "label" to it.label, "cents" to it.cents)
            },
            "nonBalanceNotes" to digest.nonBalanceNotes.map {
                linkedMapOf("key" to it.key, "label" to it.label, "cents" to it.cents)
            },
            "reconcilingGapCents" to digest.reconcilingGapCents
        )
        return gson.toJson(root)
    }

    suspend fun exportReport(): String {
        val wallets = walletDao.getAll()
        val rules = allocationDao.getAll()
        val loans = loanDao.getLoans()
        val charity = charityStatus()
        val rates = walletRateDao.getAll()
        val tasks = taskDao.getAll()
        val courses = courseDao.getAll()
        val events = calendarEventDao.getAll()
        val sb = StringBuilder()
        sb.appendLine("# 我的錢包狀態報告 v2")
        sb.appendLine("exportedAt=${System.currentTimeMillis()}")
        sb.appendLine()
        sb.appendLine("## 狀態（唯讀）")
        sb.appendLine()
        sb.appendLine("### 錢包")
        wallets.forEach {
            sb.appendLine("- ${it.name}(${it.id}): ${fmt(it.balanceCents)} 可收收入=${it.canReceiveIncome}")
        }
        val holdings = stockDao?.getHoldings().orEmpty()
        if (holdings.isNotEmpty()) {
            val stockValue = holdings.sumOf { it.marketValueCents() }
            val investCash = wallets.find { it.id == WalletCodes.INVEST }?.balanceCents ?: 0L
            sb.appendLine()
            sb.appendLine("### 持股（投資現金另計於錢包）")
            holdings.forEach { h ->
                sb.appendLine(
                    "- ${h.symbol} ${h.name}: ${h.sharesMilli / 1000.0}股 " +
                        "現價${fmt(h.lastPriceCents)} 市值${fmt(h.marketValueCents())} 成本${fmt(h.costCents)}"
                )
            }
            sb.appendLine("- 持股市值合計=${fmt(stockValue)} 投資總額(現金+市值)=${fmt(investCash + stockValue)}")
        }
        sb.appendLine()
        sb.appendLine("### 戶頭月利率")
        rates.forEach { sb.appendLine("- ${it.walletId}: ${it.monthlyRateBp}bp") }
        sb.appendLine()
        sb.appendLine("### 分配％")
        rules.forEach { sb.appendLine("- ${it.walletId}: ${it.percentBp}") }
        sb.appendLine()
        sb.appendLine("### 公益")
        sb.appendLine("- month=${charity.month} done=${charity.done} rate%=${charity.ratePercent} amount=${charity.amountCents}")
        val debt = centralDebtStatus()
        if (debt.principalCents > 0 || debt.interestCents > 0) {
            sb.appendLine()
            sb.appendLine("### 中央欠債")
            sb.appendLine("- principal=${debt.principalCents} interest=${debt.interestCents} total=${debt.totalOwedCents}")
        }
        sb.appendLine()
        sb.appendLine("### 借據")
        if (loans.isEmpty()) sb.appendLine("- （無）")
        loans.forEach {
            sb.appendLine("- #${it.id} ${it.borrowerWalletId}<-${it.lenderWalletId} 餘${it.remainingCents} bp=${it.monthlyRateBp} ${it.status}")
        }
        sb.appendLine()
        val st = lotteryDao.getState()
        sb.appendLine(
            "### 抽獎 🎰=${st?.slotTickets} 🃏=${st?.bjTickets} 🎫=${st?.scratchTickets} 🎴=${st?.drawTickets} 🎟=${st?.lottoTickets} 🍬=${st?.matchTickets} 🧮=${st?.mathTickets} pending=${st?.lottoPending} drawCount=${st?.drawCount}"
        )
        val season = passDao.getActiveSeason()
        if (season != null) sb.appendLine("### 通行證 ${season.title} ${season.progress}/$PASS_LENGTH")
        sb.appendLine()
        sb.appendLine("## 生活資料（可指令修改）")
        sb.appendLine()
        val semester = loadSemester()
        val today = LocalDate.now(zone)
        val thisWeek = AcademicCalendar.currentWeek(semester, today)
        sb.appendLine("### 學期")
        sb.appendLine(
            "- 第1週週一=${semester.week1Monday} 週數=${semester.weeks} " +
                "迄=${semester.lastSunday} 本週=${thisWeek ?: "學期外"}"
        )
        sb.appendLine()
        sb.appendLine("### 計畫")
        val plans = planDao?.getAll().orEmpty()
        if (plans.isEmpty()) sb.appendLine("- （無）")
        plans.forEach { p ->
            val note = if (p.note.isNotBlank()) " ${p.note}" else ""
            sb.appendLine("- ${p.id} ${p.title}$note")
        }
        sb.appendLine()
        sb.appendLine("### 進行中任務")
        val visibleTasks = tasks.filter { it.status == TaskStatus.OPEN }
        if (visibleTasks.isEmpty()) sb.appendLine("- （無）")
        visibleTasks.forEach { t ->
            sb.appendLine("- ${formatTaskReportLine(t, semester)}")
        }
        sb.appendLine()
        sb.appendLine("### 課表")
        if (courses.isEmpty()) sb.appendLine("- （無）")
        courses.forEach { c ->
            val time = PeriodTimes.formatPeriodRange(c.periodStart, c.periodEnd)
            val loc = if (c.location.isNotBlank()) " @ ${c.location}" else ""
            val weeks = " ${c.weeksLabel(semester.weeks)}"
            sb.appendLine("- 週${PeriodTimes.weekdayLabel(c.weekday)} ${c.periodStart}-${c.periodEnd}節($time) ${c.title}$loc$weeks")
        }
        sb.appendLine()
        sb.appendLine("### 行程")
        if (events.isEmpty()) sb.appendLine("- （無）")
        events.forEach { e ->
            val t = e.timeLabel()?.let { " $it" }.orEmpty()
            val loc = if (e.location.isNotBlank()) " @ ${e.location}" else ""
            sb.appendLine("- ${e.dateYmd}$t ${e.title}$loc")
        }
        sb.appendLine()
        sb.appendLine("## 指令提示")
        sb.appendLine("- 將規劃結果以 ```mywallet-cmd 區塊貼回 App 設定→指令控制台")
        sb.appendLine("- schema 必須為 mywallet-cmd/v1；支援 op：task.* / wallet.transfer / focus.settle / ticket.add / note / exam.* / loan.* / course.* / event.create / semester.set / plan.*")
        return sb.toString()
    }

    private fun formatTaskReportLine(t: TaskEntity, semester: SemesterConfig): String {
        val lv = parseRewards(t.rewardJson).firstOrNull { it.kind == "TICKET_LEVEL" }?.level
        val lvText = lv?.let { " Lv.$it" }.orEmpty()
        val rep = if (t.repeatable) {
            val next = t.repeatNextAt()
            if (t.isRepeatOnCooldown()) " 可重複 冷卻至=${fmtInstant(next)}" else " 可重複"
        } else ""
        val planned = t.plannedAt?.let { " planned=${fmtInstant(it)}${weekTag(it, semester)}" }.orEmpty()
        val plan = t.planId?.let { " plan=$it" }.orEmpty()
        return "[OPEN] #${t.id} ${t.title} | ${t.category}$lvText$rep$planned$plan"
    }

    private fun weekTag(ms: Long, semester: SemesterConfig): String {
        val date = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val w = AcademicCalendar.weekOf(date, semester) ?: return ""
        return " 第${w}週"
    }

    private fun fmtInstant(ms: Long): String {
        val ldt = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()
        return "%04d-%02d-%02d %02d:%02d".format(
            ldt.year, ldt.monthValue, ldt.dayOfMonth, ldt.hour, ldt.minute
        )
    }

    private fun fmt(cents: Long): String {
        val neg = cents < 0
        val v = kotlin.math.abs(cents)
        val s = "%,d".format(v / 100)
        return if (neg) "-$$s" else "$$s"
    }
}
