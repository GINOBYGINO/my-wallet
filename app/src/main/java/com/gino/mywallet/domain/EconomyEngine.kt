package com.gino.mywallet.domain

import com.gino.mywallet.data.dao.AllocationDao
import com.gino.mywallet.data.dao.CalendarEventDao
import com.gino.mywallet.data.dao.CourseDao
import com.gino.mywallet.data.dao.LoanDao
import com.gino.mywallet.data.dao.LotteryDao
import com.gino.mywallet.data.dao.MetaDao
import com.gino.mywallet.data.dao.PassDao
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
import com.gino.mywallet.data.entity.PassTierEntity
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
import com.gino.mywallet.domain.game.TicketKind
import com.gino.mywallet.domain.game.TripleDrawGame
import com.gino.mywallet.domain.game.TripleDrawPhase
import com.gino.mywallet.domain.game.TripleDrawState
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.random.Random

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
    private val gson: Gson = Gson()
) {
    private val rewardListType = object : TypeToken<List<RewardItem>>() {}.type
    private val zone: ZoneId get() = ZoneId.systemDefault()

    companion object {
        const val COOLDOWN_MS = 22L * 60 * 60 * 1000
        /** 本月還最低利率 1.00%/月（與半價月利率取較高） */
        const val SAME_MONTH_MIN_RATE_BP = 100
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
     */
    private suspend fun payFromCentral(
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

    suspend fun recordIncomeStandard(amountCents: Long, note: String = ""): Map<String, Long> {
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
        splits.forEach { (id, cents) ->
            if (cents != 0L) {
                walletDao.adjustBalance(id, cents)
                transactionDao.insert(
                    TransactionEntity(
                        type = "INCOME_A",
                        amountCents = cents,
                        toWalletId = id,
                        note = note.ifBlank { "標準收入分配" }
                    )
                )
            }
        }
        applyCentralIncomeToOverdraft(splits[WalletCodes.CENTRAL] ?: 0L)
        return splits
    }

    suspend fun transfer(fromId: String, toId: String, amountCents: Long, note: String = "") {
        require(amountCents > 0 && fromId != toId)
        val from = walletDao.getById(fromId) ?: error("找不到來源錢包")
        if (from.isCentral) error("中央帳戶不可提款／一般轉出")
        if (from.balanceCents < amountCents) error("餘額不足")
        walletDao.adjustBalance(fromId, -amountCents)
        walletDao.adjustBalance(toId, amountCents)
        transactionDao.insert(
            TransactionEntity(type = "TRANSFER", amountCents = amountCents, fromWalletId = fromId, toWalletId = toId, note = note)
        )
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
    }

    private suspend fun ensureAllocationMonth(currentMonth: String) {
        val stored = metaDao.get(MetaKeys.ALLOCATION_MONTH)?.value
        if (stored != currentMonth) {
            resetDefaultAllocation(currentMonth)
        }
    }

    /** 每月重置為 中央 40%／伙雜費 50%／bci 10% */
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

    suspend fun completeMonthlyCharity(today: LocalDate = LocalDate.now(zone)) {
        ensureCharitySettlement(today)
        val status = charityStatus(today)
        if (status.done) error("本月公益已完成")
        val rate = status.ratePercent
        val central = walletDao.getById(WalletCodes.CENTRAL) ?: error("無中央帳戶")
        val amount = central.balanceCents.coerceAtLeast(0) * rate / 100
        if (amount <= 0) error("中央無可捐金額")
        walletDao.adjustBalance(WalletCodes.CENTRAL, -amount)
        transactionDao.insert(
            TransactionEntity(
                type = "DONATION",
                amountCents = amount,
                fromWalletId = WalletCodes.CENTRAL,
                note = "公益捐獻 ${rate}%（${status.month}）"
            )
        )
        metaDao.upsert(AppMetaEntity(MetaKeys.CHARITY_DONE, "1"))
    }

    private suspend fun levyCharityPenaltyTax(forMonth: String) {
        var toCentral = 0L
        walletDao.getAll().filter { !it.isCentral && it.balanceCents > 0 }.forEach { w ->
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
    }

    /** 僅供種子／遷移等後端流程設定中央利率 */
    suspend fun setCentralRateBp(rateBp: Int) {
        require(rateBp >= 0)
        walletRateDao.upsert(WalletRateEntity(WalletCodes.CENTRAL, rateBp))
    }

    suspend fun borrowFromWallet(
        lenderId: String,
        borrowerId: String,
        amountCents: Long,
        dueThisMonth: Boolean
    ): Long {
        require(amountCents > 0)
        require(lenderId != borrowerId)
        require(borrowerId != WalletCodes.CENTRAL) { "中央透支請由系統自動產生借據" }
        val lender = walletDao.getById(lenderId) ?: error("出借方不存在")
        if (lender.balanceCents < amountCents) error("出借方餘額不足")
        val rateBp = walletRateDao.get(lenderId)?.monthlyRateBp ?: error("尚未設定該戶利率")
        val effRate = effectiveRateBp(rateBp, dueThisMonth)
        val interest = amountCents * effRate / 10_000
        val ym = YearMonth.now(zone)
        val dueYm = if (dueThisMonth) ym else ym.plusMonths(1)
        val due = monthEndMillis(dueYm)
        walletDao.adjustBalance(lenderId, -amountCents)
        walletDao.adjustBalance(borrowerId, amountCents)
        val now = System.currentTimeMillis()
        // 本月還：借入時即計入最低／半價息；下月還：本金入帳，息於月結併入
        val remaining = if (dueThisMonth) amountCents + interest else amountCents
        val loanId = loanDao.insertLoan(
            LoanEntity(
                lenderWalletId = lenderId,
                borrowerWalletId = borrowerId,
                principalCents = amountCents,
                remainingCents = remaining,
                monthlyRateBp = rateBp,
                startAt = now,
                dueAt = due
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
        transactionDao.insert(
            TransactionEntity(
                type = "LOAN_DISBURSE",
                amountCents = amountCents,
                fromWalletId = lenderId,
                toWalletId = borrowerId,
                note = "借款 #$loanId（還債=${if (dueThisMonth) "本月" else "下月"}）"
            )
        )
        return loanId
    }

    suspend fun repayLoan(loanId: Long, amountCents: Long) {
        val loan = loanDao.getLoan(loanId) ?: error("借據不存在")
        require(!loan.isCentralOverdraft()) { "中央透支借據由收入分配自動償還" }
        require(loan.status == LoanStatus.ACTIVE || loan.status == LoanStatus.DEFAULTED)
        require(amountCents > 0)
        val borrower = walletDao.getById(loan.borrowerWalletId) ?: error("借方不存在")
        if (borrower.balanceCents < amountCents) error("借方餘額不足")
        val principalPaid = minOf(amountCents, loan.remainingCents)
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
                note = "還款 #$loanId"
            )
        )
    }

    suspend fun settleMonthlyLoanInterest(today: LocalDate = LocalDate.now(zone)) {
        val ym = YearMonth.from(today)
        val key = ym.toString()
        val last = metaDao.get(MetaKeys.LOAN_INTEREST_MONTH)?.value
        if (last == key) return
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
            return
        }
        if (!ym.isAfter(previous)) return
        var cursor = previous.plusMonths(1)
        while (!cursor.isAfter(ym)) {
            capitalizeOneMonthInterest(cursor)
            cursor = cursor.plusMonths(1)
        }
        metaDao.upsert(AppMetaEntity(MetaKeys.LOAN_INTEREST_MONTH, key))
        alignLoanDueDates(ym)
    }

    private suspend fun capitalizeOneMonthInterest(forMonth: YearMonth) {
        val due = monthEndMillis(forMonth)
        loanDao.getLoans()
            .filter { it.status == LoanStatus.ACTIVE || it.status == LoanStatus.DEFAULTED }
            .forEach { loan ->
                val rate = loan.monthlyRateBp + loan.penaltyRateBp
                val interest = loan.remainingCents * rate / 10_000
                if (interest <= 0) {
                    loanDao.updateLoan(loan.copy(dueAt = due))
                    return@forEach
                }
                loanDao.updateLoan(loan.copy(remainingCents = loan.remainingCents + interest, dueAt = due))
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
            .filter { it.status == LoanStatus.ACTIVE || it.status == LoanStatus.DEFAULTED }
            .forEach { loan ->
                // 若借據到期月仍在未來，不強制改到本月
                val loanYm = YearMonth.from(Instant.ofEpochMilli(loan.dueAt).atZone(zone).toLocalDate())
                if (!loanYm.isAfter(ym) && loan.dueAt != due) {
                    loanDao.updateLoan(loan.copy(dueAt = due))
                }
            }
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
    }

    // ---------- 任務 ----------

    suspend fun completeTask(taskId: Long): PassAdvanceResult? {
        val task = taskDao.getById(taskId) ?: error("任務不存在")
        require(task.status == TaskStatus.OPEN)
        val rewards = parseRewards(task.rewardJson)
        if (rewards.none { it.kind.startsWith("TICKET") || it.kind == "LOTTERY_TICKET" }) {
            addTickets(TicketKind.SLOT, 1) // 舊任務未寫入券獎勵時預設 +1 🎰
        }
        val passResult = advancePassOneTier()
        applyRewards(rewards, allowedCentralOut = true)
        taskDao.update(task.copy(status = TaskStatus.DONE, resolvedAt = System.currentTimeMillis()))
        if (task.repeatable) scheduleNextRepeat(task)
        return passResult
    }

    suspend fun failTask(taskId: Long) {
        val task = taskDao.getById(taskId) ?: error("任務不存在")
        require(task.status == TaskStatus.OPEN)
        applyRewards(parseRewards(task.penaltyJson), allowedCentralOut = false)
        taskDao.update(task.copy(status = TaskStatus.FAILED, resolvedAt = System.currentTimeMillis()))
        if (task.repeatable) scheduleNextRepeat(task)
    }

    private suspend fun scheduleNextRepeat(task: TaskEntity) {
        val rule = parseRepeatRule(task.repeatRuleJson)
        val base = if (rule.nextAt > 0) Instant.ofEpochMilli(rule.nextAt).atZone(zone).toLocalDateTime()
        else LocalDateTime.now(zone)
        val next = when (rule.freq) {
            "weekly" -> base.plusWeeks(1)
            "every_n" -> base.plusDays((rule.n ?: 1).toLong().coerceAtLeast(1))
            else -> base.plusDays(1)
        }
        val nextAt = next.atZone(zone).toInstant().toEpochMilli()
        val newRule = rule.copy(nextAt = nextAt)
        taskDao.insert(
            task.copy(
                id = 0,
                status = TaskStatus.OPEN,
                resolvedAt = null,
                createdAt = System.currentTimeMillis(),
                repeatRuleJson = gson.toJson(newRule),
                repeatable = true,
                dueAt = null
            )
        )
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

    private suspend fun advancePassOneTier(): PassAdvanceResult? {
        ensureMonthlyPassSeason()
        val season = passDao.getActiveSeason() ?: return null
        if (season.progress >= 60) return null
        val nextIndex = season.progress + 1
        val tiers = passDao.getTiers(season.id)
        val tier = tiers.find { it.tierIndex == nextIndex }
        passDao.updateSeason(season.copy(progress = nextIndex.coerceAtMost(60)))
        if (tier == null) {
            return PassAdvanceResult(nextIndex, "—", claimedReward = false)
        }
        val rewards = parseRewards(tier.rewardJson)
        if (rewards.isEmpty() && !tier.claimed) {
            passDao.updateTier(tier.copy(claimed = true))
        }
        return PassAdvanceResult(
            tierIndex = nextIndex,
            label = tier.label,
            claimedReward = rewards.isNotEmpty()
        )
    }

    suspend fun claimPassTier(tierId: Long): PassAdvanceResult {
        val season = passDao.getActiveSeason() ?: error("尚無進行中賽季")
        val tier = passDao.getTiers(season.id).find { it.id == tierId } ?: error("通行證格不存在")
        require(tier.tierIndex <= season.progress) { "尚未解鎖此格" }
        require(!tier.claimed) { "已領取" }
        val rewards = parseRewards(tier.rewardJson)
        require(rewards.isNotEmpty()) { "休息格無需領取" }
        applyRewards(rewards, allowedCentralOut = true)
        passDao.updateTier(tier.copy(claimed = true))
        return PassAdvanceResult(tier.tierIndex, tier.label, claimedReward = true)
    }

    suspend fun ensureMonthlyPassSeason(today: LocalDate = LocalDate.now(zone)) {
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
    }

    /** 以新獎池覆寫本月通行證格（保留進度與已領狀態） */
    suspend fun refreshCurrentPassRewards(today: LocalDate = LocalDate.now(zone)) {
        ensureMonthlyPassSeason(today)
        val season = passDao.getActiveSeason() ?: return
        val oldClaimed = passDao.getTiers(season.id).associate { it.tierIndex to it.claimed }
        passDao.deleteTiers(season.id)
        val fresh = buildPassTiers(season.id).map { t ->
            t.copy(claimed = oldClaimed[t.tierIndex] == true)
        }
        passDao.insertTiers(fresh)
    }

    private fun buildPassTiers(seasonId: Long): List<PassTierEntity> {
        /** 中央 → 伙雜費 */
        fun living(yuan: Long) = listOf(
            RewardItem(
                kind = "TRANSFER",
                amountCents = yuan * 100,
                fromWalletId = WalletCodes.CENTRAL,
                toWalletId = WalletCodes.LIVING
            )
        )
        fun slot(n: Int) = listOf(RewardItem(kind = "TICKET_SLOT", tickets = n))
        fun bj(n: Int) = listOf(RewardItem(kind = "TICKET_BJ", tickets = n))
        fun gold(n: Int) = listOf(RewardItem(kind = "TICKET_SCRATCH", tickets = n))
        fun draw(n: Int) = listOf(RewardItem(kind = "TICKET_DRAW", tickets = n))
        fun relief(p: Int) = listOf(RewardItem(kind = "TAX_RELIEF", reliefPercent = p))
        fun rest() = emptyList<RewardItem>()
        fun reliefLabel(p: Int) = "公益基準−${p}%"
        fun livingLabel(yuan: Long) = "🍔$$yuan"
        fun combo(label: String, items: List<RewardItem>) = label to items

        /**
         * 瘦曲線（月現金≈600、券 EV≈200）：
         * 休息多、純 🎰、小現金、里程碑、終局。
         */
        val special = mapOf(
            1 to combo("休息", rest()),
            3 to combo("休息", rest()),
            5 to combo(livingLabel(15), living(15)),
            6 to combo("休息", rest()),
            8 to combo("休息", rest()),
            9 to combo(livingLabel(20), living(20)),
            10 to combo(reliefLabel(1), relief(1)),
            11 to combo("休息", rest()),
            12 to combo("${TicketKind.BJ.symbol}×1", bj(1)),
            13 to combo("休息", rest()),
            14 to combo(livingLabel(15), living(15)),
            15 to combo("${TicketKind.SCRATCH.symbol}×1", gold(1)),
            16 to combo("休息", rest()),
            17 to combo(livingLabel(20), living(20)),
            19 to combo("休息", rest()),
            20 to combo(reliefLabel(1), relief(1)),
            22 to combo("休息", rest()),
            23 to combo(livingLabel(15), living(15)),
            24 to combo("${TicketKind.BJ.symbol}×2", bj(2)),
            25 to combo("休息", rest()),
            26 to combo(livingLabel(20), living(20)),
            27 to combo("休息", rest()),
            29 to combo("休息", rest()),
            30 to combo("🍔$40＋${TicketKind.SLOT.symbol}×1", living(40) + slot(1)),
            31 to combo("休息", rest()),
            33 to combo("休息", rest()),
            34 to combo(livingLabel(15), living(15)),
            36 to combo("${TicketKind.BJ.symbol}×2", bj(2)),
            37 to combo(livingLabel(20), living(20)),
            38 to combo("休息", rest()),
            40 to combo(reliefLabel(2), relief(2)),
            41 to combo("休息", rest()),
            43 to combo("休息", rest()),
            44 to combo(livingLabel(20), living(20)),
            45 to combo("${TicketKind.SCRATCH.symbol}×1", gold(1)),
            46 to combo("休息", rest()),
            47 to combo(livingLabel(15), living(15)),
            48 to combo("${TicketKind.BJ.symbol}×2＋${TicketKind.DRAW.symbol}×1", bj(2) + draw(1)),
            49 to combo("休息", rest()),
            50 to combo("基準−2%＋${TicketKind.SLOT.symbol}×1", relief(2) + slot(1)),
            51 to combo("休息", rest()),
            53 to combo(livingLabel(25), living(25)),
            54 to combo("休息", rest()),
            55 to combo(livingLabel(50), living(50)),
            57 to combo("休息", rest()),
            58 to combo("休息", rest()),
            59 to combo(livingLabel(25), living(25)),
            60 to combo(
                "通關",
                living(80) + slot(2) + bj(3) + gold(1) + draw(1)
            )
        )
        return (1..60).map { i ->
            when {
                special.containsKey(i) -> {
                    val (label, items) = special[i]!!
                    PassTierEntity(
                        seasonId = seasonId,
                        tierIndex = i,
                        label = label,
                        rewardJson = gson.toJson(items)
                    )
                }
                else -> PassTierEntity(
                    seasonId = seasonId,
                    tierIndex = i,
                    label = "${TicketKind.SLOT.symbol}×1",
                    rewardJson = gson.toJson(slot(1))
                )
            }
        }
    }

    // ---------- 抽獎 ----------

    suspend fun claimCooldownTicketIfNeeded(): TicketKind? {
        val now = System.currentTimeMillis()
        val state = lotteryDao.getState() ?: LotteryStateEntity()
        if (state.nextFreeAt > now) return null
        val cycle = listOf(TicketKind.SLOT, TicketKind.BJ, TicketKind.SCRATCH, TicketKind.DRAW)
        val kind = cycle[state.cooldownIndex.mod(cycle.size)]
        val nextIndex = state.cooldownIndex + 1
        val bumped = when (kind) {
            TicketKind.SLOT -> state.copy(slotTickets = state.slotTickets + 1)
            TicketKind.BJ -> state.copy(bjTickets = state.bjTickets + 1)
            TicketKind.SCRATCH -> state.copy(scratchTickets = state.scratchTickets + 1)
            TicketKind.DRAW -> state.copy(drawTickets = state.drawTickets + 1)
        }
        lotteryDao.upsertState(
            bumped.copy(
                nextFreeAt = now + COOLDOWN_MS,
                lastFreeDrawDay = LocalDate.now(zone).toString(),
                cooldownIndex = nextIndex
            )
        )
        return kind
    }

    /** @deprecated 改用 claimCooldownTicketIfNeeded */
    suspend fun claimDailyFreeTicketIfNeeded(): Boolean = claimCooldownTicketIfNeeded() != null

    suspend fun addTickets(kind: TicketKind, n: Int) {
        if (n <= 0) return
        val state = lotteryDao.getState() ?: LotteryStateEntity()
        lotteryDao.upsertState(
            when (kind) {
                TicketKind.SLOT -> state.copy(slotTickets = state.slotTickets + n)
                TicketKind.BJ -> state.copy(bjTickets = state.bjTickets + n)
                TicketKind.SCRATCH -> state.copy(scratchTickets = state.scratchTickets + n)
                TicketKind.DRAW -> state.copy(drawTickets = state.drawTickets + n)
            }
        )
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
        }
    }

    suspend fun consumeLotteryTickets(n: Int) = consumeTickets(TicketKind.SLOT, n)

    fun blackjackNewRound(): Blackjack21State = Blackjack21.newRound()

    suspend fun blackjackAdvance(current: Blackjack21State): Blackjack21State {
        require(current.phase == BjPhase.PLAYING) { "非進行中" }
        consumeTickets(TicketKind.BJ, 1)
        val next = Blackjack21.advance(current, Blackjack21.rollStep())
        if (next.result == BjResult.WIN_21) {
            // 只拿大獎，累積 pot 清掉不加
            payLivingPrize(GamePrizes.BJ_JACKPOT_CENTS, "BJ21", "${TicketKind.BJ.symbol} 二十一點・21點")
        }
        return next
    }

    suspend fun blackjackStand(current: Blackjack21State): Blackjack21State {
        val next = Blackjack21.stand(current)
        when (next.result) {
            BjResult.WIN_BEAT -> {
                // 擊敗獎；pot 清掉不加
                payLivingPrize(GamePrizes.BJ_BEAT_CENTS, "BJ21", "${TicketKind.BJ.symbol} 擊敗莊家")
            }
            BjResult.LOSE -> {
                if (next.potCents > 0) {
                    payLivingPrize(next.potCents, "BJ21", "${TicketKind.BJ.symbol} 停牌")
                }
            }
            else -> { }
        }
        return next
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

    suspend fun tripleDrawSettle(current: TripleDrawState): TripleDrawState {
        val next = TripleDrawGame.settle(current)
        if (next.payoutCents > 0) {
            payLivingPrize(
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
        }
        return next
    }

    fun hiLoNewRound(): HiLoState = HiLoGame.newBase()

    suspend fun hiLoGuess(current: HiLoState, pick: HiLoPick): HiLoState {
        consumeTickets(TicketKind.SCRATCH, 1)
        val result = HiLoGame.resolve(current, pick)
        when (result.outcome) {
            HiLoOutcome.WIN, HiLoOutcome.TIE -> {
                payLivingPrize(
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
            }
            null -> { }
        }
        return result
    }

    private fun pickLabel(pick: HiLoPick): String = when (pick) {
        HiLoPick.HIGH -> "更高"
        HiLoPick.LOW -> "更低"
    }

    /** 中央 → 指定戶頭；餘額不足時記欠債 */
    private suspend fun payLivingPrize(amountCents: Long, txType: String, note: String): Long =
        payFromCentral(WalletCodes.LIVING, amountCents, note, txType)

    suspend fun drawSlot(): SlotDrawResult {
        val state = lotteryDao.getState() ?: error("無抽獎狀態")
        if (state.slotTickets <= 0) error("沒有${TicketKind.SLOT.symbol}次數")
        val newCount = state.drawCount + 1
        val boosted = newCount % 10 == 0
        lotteryDao.upsertState(state.copy(slotTickets = state.slotTickets - 1, drawCount = newCount))

        val wallets = walletDao.getAll().filter {
            !it.isCentral && !it.archived && it.id != WalletCodes.INVEST
        }
        val target = wallets.randomOrNull() ?: error("無可用中獎戶頭")
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
                note = "${TicketKind.SLOT.symbol} 抽中：${result.label}"
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
                "拉霸獎勵",
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

    suspend fun applyRewards(items: List<RewardItem>, allowedCentralOut: Boolean = true) {
        items.forEach { item ->
            when (item.kind) {
                "TRANSFER" -> {
                    val amount = item.amountCents ?: return@forEach
                    val from = item.fromWalletId ?: WalletCodes.CENTRAL
                    val to = item.toWalletId ?: return@forEach
                    if (from == WalletCodes.CENTRAL && !allowedCentralOut) error("此獎懲不可從中央轉出")
                    if (from == WalletCodes.CENTRAL) {
                        payFromCentral(to, amount, "獎懲轉帳", "REWARD_TRANSFER")
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
    }

    fun parseRewards(json: String): List<RewardItem> =
        try {
            gson.fromJson(json, rewardListType) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    fun rewardsToJson(items: List<RewardItem>): String = gson.toJson(items)

    // ---------- 專注結算 ----------

    /**
     * @param cashYuan 可為負（扣伙雜費）
     * @param effectiveMs 專注有效毫秒
     */
    suspend fun settleFocus(cashYuan: Int, effectiveMs: Long): Map<TicketKind, Int> {
        val cents = cashYuan.toLong() * 100L
        if (cents != 0L) {
            walletDao.adjustBalance(WalletCodes.LIVING, cents)
            transactionDao.insert(
                TransactionEntity(
                    type = "FOCUS_SETTLE",
                    amountCents = kotlin.math.abs(cents),
                    fromWalletId = if (cents < 0) WalletCodes.LIVING else null,
                    toWalletId = if (cents > 0) WalletCodes.LIVING else null,
                    note = if (cents >= 0) "專注結算 +${cashYuan} 元" else "專注結算 ${cashYuan} 元"
                )
            )
        }
        val units = FocusRules.ticketUnits(effectiveMs)
        val mix = GamePrizes.expandEvBudget(units * GamePrizes.EV_PER_LEVEL_YUAN)
        val extra = FocusRules.guaranteedTickets(effectiveMs)
        val pack = GamePrizes.mergeTicketMaps(mix, extra)
        pack.forEach { (kind, n) -> addTickets(kind, n) }
        return pack
    }

    // ---------- 平帳 ----------

    suspend fun setBalanceAdjust(walletId: String, targetCents: Long) {
        require(walletId == WalletCodes.LIVING || walletId == WalletCodes.INVEST) {
            "平帳僅限伙雜費與投資"
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
            "### 抽獎 🎰=${st?.slotTickets} 🃏=${st?.bjTickets} 🎫=${st?.scratchTickets} 🎴=${st?.drawTickets} drawCount=${st?.drawCount} nextFreeAt=${st?.nextFreeAt}"
        )
        val season = passDao.getActiveSeason()
        if (season != null) sb.appendLine("### 通行證 ${season.title} ${season.progress}/60")
        sb.appendLine()
        sb.appendLine("## 生活資料（可指令修改）")
        sb.appendLine()
        sb.appendLine("### 進行中任務")
        val openTasks = tasks.filter { it.status == TaskStatus.OPEN }
        if (openTasks.isEmpty()) sb.appendLine("- （無）")
        openTasks.forEach { t ->
            val lv = parseRewards(t.rewardJson).firstOrNull { it.kind == "TICKET_LEVEL" }?.level
            val lvText = lv?.let { " Lv.$it" }.orEmpty()
            val rep = if (t.repeatable) " 可重複" else ""
            sb.appendLine("- [OPEN] #${t.id} ${t.title} | ${t.category}$lvText$rep")
        }
        sb.appendLine()
        sb.appendLine("### 課表")
        if (courses.isEmpty()) sb.appendLine("- （無）")
        courses.forEach { c ->
            val time = PeriodTimes.formatPeriodRange(c.periodStart, c.periodEnd)
            val loc = if (c.location.isNotBlank()) " @ ${c.location}" else ""
            sb.appendLine("- 週${PeriodTimes.weekdayLabel(c.weekday)} ${c.periodStart}-${c.periodEnd}節($time) ${c.title}$loc")
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
        sb.appendLine("- schema 必須為 mywallet-cmd/v1；支援 op：task.create / course.upsert / course.replace_all / event.create / task.delete")
        return sb.toString()
    }

    private fun fmt(cents: Long): String {
        val neg = cents < 0
        val v = kotlin.math.abs(cents)
        val s = "%,d".format(v / 100)
        return if (neg) "-$$s" else "$$s"
    }
}
