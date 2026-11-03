package com.gino.mywallet.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 系統錢包代碼，種子資料用 */
object WalletCodes {
    const val CENTRAL = "central"
    /** 延後入帳的中央份額；公益完成後才轉入中央。顯示在撲滿，不參與捐款基數。 */
    const val CENTRAL_FUTURE = "central_future"
    const val LIVING = "living"       // 現金（舊名伙雜費）
    const val CARD = "card"           // 提款卡
    const val BCI = "bci"
    const val VTUBER = "vtuber"
    const val ACG = "acg"
    const val REAL = "real"           // 已廢止；僅遷移用
    const val INVEST = "invest"       // 投資（主要出借方）
    const val STUDY = "study"
    const val SOCIAL = "social"

    /** 錢包桶：現金或提款卡 */
    fun isWalletBucket(id: String): Boolean = id == LIVING || id == CARD

    /** 撲滿桶（待轉移語意）：非錢包桶、非投資、非已廢止戶、非中央、非未來中央。
     *  錢包頁「撲滿合計」另含中央與未來中央，見 UI；獎金路由池不含這兩戶。 */
    fun isPiggyWallet(id: String): Boolean =
        id != LIVING && id != CARD && id != INVEST && id != REAL && id != CENTRAL && id != CENTRAL_FUTURE
}

object TaskCategories {
    const val ALL = "全部"
    val PRESETS = listOf("生活", "學業", "專案", "社交", "財政", "其他")
}

@Entity(tableName = "wallets")
data class WalletEntity(
    @PrimaryKey val id: String,
    val name: String,
    val balanceCents: Long = 0L,
    val isCentral: Boolean = false,
    val canReceiveIncome: Boolean = true,
    val sortOrder: Int = 0,
    val archived: Boolean = false
)

/** 各戶頭月利率（市場借入用）；中央不建列 */
@Entity(tableName = "wallet_rates")
data class WalletRateEntity(
    @PrimaryKey val walletId: String,
    /** 500 = 5.00%/月 */
    val monthlyRateBp: Int
)

@Entity(tableName = "allocation_rules")
data class AllocationRuleEntity(
    @PrimaryKey val walletId: String,
    val percentBp: Int, // basis points: 4000 = 40.00%
    val isTaxShare: Boolean = false
)

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val amountCents: Long,
    val fromWalletId: String? = null,
    val toWalletId: String? = null,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "loan_offers")
data class LoanOfferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lenderWalletId: String,
    val maxAmountCents: Long,
    @ColumnInfo(name = "annualRateBp")
    val monthlyRateBp: Int,
    val termDays: Int,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "loans")
data class LoanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lenderWalletId: String,
    val borrowerWalletId: String,
    val principalCents: Long,
    val remainingCents: Long,
    @ColumnInfo(name = "annualRateBp")
    val monthlyRateBp: Int,
    val penaltyRateBp: Int = 0,
    val startAt: Long,
    val dueAt: Long,
    /** 月息已計入到此時刻（含）。0＝尚未回填；小於 dueAt 表示本期月息還沒入帳。 */
    val interestCoveredUntil: Long = 0,
    val status: String = LoanStatus.ACTIVE,
    val offerId: Long? = null
)

object LoanStatus {
    const val ACTIVE = "ACTIVE"
    const val DEFAULTED = "DEFAULTED"
    const val REPAID = "REPAID"
}

object LoanMarkers {
    /** 中央透支：餘額為負時的借據，由收入分配自動償還 */
    const val CENTRAL_OVERDRAFT_OFFER_ID = -1L
}

fun LoanEntity.isCentralOverdraft(): Boolean =
    offerId == LoanMarkers.CENTRAL_OVERDRAFT_OFFER_ID && borrowerWalletId == WalletCodes.CENTRAL

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val note: String = "",
    val category: String = "其他",
    val repeatable: Boolean = false,
    /** {"freq":"daily"|"weekly"|"every_n","n":Int?,"nextAt":Long} */
    val repeatRuleJson: String = "",
    val status: String = TaskStatus.OPEN,
    val rewardJson: String = "[]",
    val penaltyJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null,
    /** 預計處理日（當天 00:00 epoch ms）；null＝未排程 */
    val plannedAt: Long? = null,
    /** 所屬長遠計畫 id；null＝未歸戶 */
    val planId: String? = null
) {
    fun ticketLevel(): Int {
        if (!rewardJson.contains("TICKET_LEVEL")) return 0
        return Regex("\"level\"\\s*:\\s*(\\d+)").find(rewardJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    fun repeatNextAt(): Long {
        if (!repeatable || repeatRuleJson.isBlank()) return 0L
        return Regex("\"nextAt\"\\s*:\\s*(-?\\d+)").find(repeatRuleJson)
            ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }

    fun isRepeatOnCooldown(now: Long = System.currentTimeMillis()): Boolean =
        repeatable && repeatNextAt() > now

    fun canClaim(now: Long = System.currentTimeMillis()): Boolean =
        status == TaskStatus.OPEN && !isRepeatOnCooldown(now)

    fun claimBlockReason(now: Long = System.currentTimeMillis()): String? = when {
        status != TaskStatus.OPEN -> "任務不是進行中"
        isRepeatOnCooldown(now) -> "下次可執行前不可再領取"
        else -> null
    }
}

object TaskStatus {
    const val OPEN = "OPEN"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
}

data class RepeatRule(
    val freq: String = "daily", // daily / weekly / every_n
    val n: Int? = null,
    val nextAt: Long = 0L
)

/** 獎懲項目 JSON 結構 */
data class RewardItem(
    val kind: String, // TRANSFER / PRIZE / ... / TICKET_SLOT / TICKET_BJ / TICKET_SCRATCH / TICKET_DRAW / TICKET_LOTTO / TICKET_LEVEL / TAX_RELIEF
    val amountCents: Long? = null,
    val fromWalletId: String? = null,
    val toWalletId: String? = null,
    val walletId: String? = null,
    val deltaBp: Int? = null,
    val tickets: Int? = null,
    /** 任務隨機券包等級 1～9（初 1–3／中 4–7／高 8–9） */
    val level: Int? = null,
    /** 公益基準％減成（例如 1 = -1%） */
    val reliefPercent: Int? = null
)

@Entity(tableName = "lottery_state")
data class LotteryStateEntity(
    @PrimaryKey val id: Int = 1,
    /** 🎰 拉霸次數（欄位名 tickets：舊資料遷移後即為拉霸券） */
    @ColumnInfo(name = "tickets")
    val slotTickets: Int = 0,
    val bjTickets: Int = 0,
    val scratchTickets: Int = 0,
    val drawTickets: Int = 0,
    /** 🎟 樂透券 */
    val lottoTickets: Int = 0,
    /** 🍬 消消樂券 */
    val matchTickets: Int = 0,
    /** 🧮 輪盤券（原速算欄位，張數沿用） */
    val mathTickets: Int = 0,
    /** 已買未開注數（與 lottoPicksJson 長度同步） */
    val lottoPending: Int = 0,
    /** 待開號碼 JSON 陣列，如 ["042","777"] */
    val lottoPicksJson: String = "[]",
    /** 這批注數對應的開獎時間（epoch ms）；0＝尚無 */
    val lottoDrawAt: Long = 0L,
    /** 上期開獎摘要（顯示用） */
    val lottoLastResult: String = "",
    val lastFreeDrawDay: String = "",
    /** 下次可領冷卻券的時間 */
    val nextFreeAt: Long = 0L,
    /** 累計已抽次數（倍率局用） */
    val drawCount: Int = 0,
    /** 冷卻輪替：0🎰 1🃏 2🎫 3🎴 4🎟 */
    val cooldownIndex: Int = 0
)

@Entity(tableName = "pass_seasons")
data class PassSeasonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startsAt: Long,
    val endsAt: Long,
    val progress: Int = 0,
    val active: Boolean = true
)

@Entity(tableName = "pass_tiers")
data class PassTierEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seasonId: Long,
    val tierIndex: Int,
    val label: String,
    val rewardJson: String,
    val claimed: Boolean = false
)

/** 完成任務推進通行證一格後的回報（供 Snackbar） */
data class PassAdvanceResult(
    val tierIndex: Int,
    val label: String,
    val claimedReward: Boolean,
    /** 動態獎金入帳戶提示，如「 → 學業」 */
    val prizeHint: String = ""
)

@Entity(tableName = "app_meta")
data class AppMetaEntity(
    @PrimaryKey val key: String,
    val value: String
)

object MetaKeys {
    const val TAX_BP = "tax_bp"
    const val VERSION = "version"
    const val CHARITY_MONTH = "charity_month"
    const val CHARITY_DONE = "charity_done"
    const val LOAN_INTEREST_MONTH = "loan_interest_month"
    /** 本月公益基準已減成的％總和 */
    const val CHARITY_RELIEF_PERCENT = "charity_relief_percent"
    /** 收入分配上次重置月 yyyy-MM */
    const val ALLOCATION_MONTH = "allocation_month"
    /** 通知總開關 "1"/"0" */
    const val NOTIFICATIONS_ENABLED = "notifications_enabled"
    /** 中間 Tab："calendar" | "exam"（預設 calendar） */
    const val MIDDLE_TAB = "middle_tab"
    /** 中央欠債本金（分） */
    const val CENTRAL_DEBT_PRINCIPAL = "central_debt_principal"
    /** 中央欠債起算時間（epoch ms） */
    const val CENTRAL_DEBT_SINCE_MS = "central_debt_since_ms"
    const val GAME_BJ_JSON = "game_bj_json"
    const val GAME_HILO_JSON = "game_hilo_json"
    const val GAME_DRAW_JSON = "game_draw_json"
    const val GAME_WORDLE_JSON = "game_wordle_json"
    const val GAME_MATCH3_JSON = "game_match3_json"
    const val GAME_MATH_JSON = "game_math_json"
    const val GAME_SELECTED = "game_selected"
    /** 通行證獎池定義版本；與 EconomyEngine.PASS_POOL_VERSION 對齊 */
    const val PASS_POOL_VERSION = "pass_pool_version"
    /** 07:00 拉霸券已領日 yyyy-MM-dd */
    const val CHECKIN_SLOT_CLAIM_DAY = "checkin_slot_claim_day"
    /** 累積領獎：已兌換的完成任務數游標 */
    const val MILESTONE_TASKS_REDEEMED = "milestone_tasks_redeemed"
    /** 累積領獎：已兌換的專注毫秒游標 */
    const val MILESTONE_FOCUS_REDEEMED_MS = "milestone_focus_redeemed_ms"
    /** 學期第 1 週週一 yyyy-MM-dd */
    const val SEMESTER_WEEK1_MONDAY = "semester_week1_monday"
    /** 學期週數（預設 18） */
    const val SEMESTER_WEEKS = "semester_weeks"
    /** 舊版專注結算（已直接入帳）已改列待轉移 */
    const val FOCUS_SETTLE_PHYSICAL_BACKFILL = "focus_settle_physical_backfill"
    /** 外部 App 入帳冪等前綴；完整鍵 = EXT_GRANT_PREFIX + source + ":" + date + ":" + nonce */
    const val EXT_GRANT_PREFIX = "ext_grant:"
    /** Bot 摘要 revision（整數字串） */
    const val BOT_REVISION = "bot_revision"
    /** Bot 已套用指令前綴；完整鍵 = BOT_CMD_PREFIX + id */
    const val BOT_CMD_PREFIX = "bot_cmd:"
    /** reward.grant 冪等前綴；完整鍵 = BOT_REWARD_PREFIX + nonce */
    const val BOT_REWARD_PREFIX = "bot_reward:"
}

@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey val id: String,
    val title: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

object PlanIds {
    private val PATTERN = Regex("^[A-Za-z0-9_\\-]{1,32}$")

    fun requireValid(id: String): String {
        val trimmed = id.trim()
        require(trimmed.isNotBlank()) { "計畫 id 不可空白" }
        require(PATTERN.matches(trimmed)) { "計畫 id 須為 1–32 字英數、連字或底線" }
        return trimmed
    }
}

data class CentralDebtStatus(
    val principalCents: Long,
    val interestCents: Long,
    val totalOwedCents: Long,
    val sinceMs: Long
)

data class CharityStatus(
    val month: String,
    val done: Boolean,
    val ratePercent: Int,
    val amountCents: Long = 0L
)

/** 三段式拉霸結果 */
data class SlotDrawResult(
    val walletId: String,
    val walletName: String,
    val reel0: String,
    val reel1: String,
    val reel2: String,
    val label: String,
    val boosted: Boolean,
    val cashCents: Long = 0L,
    /** MISS / SMALL / BIG */
    val tier: String = SlotTiers.MISS,
    /** 前兩輪相同、第三輪差一點 */
    val nearMiss: Boolean = false
)

object SlotTiers {
    const val MISS = "MISS"
    const val SMALL = "SMALL"
    const val BIG = "BIG"
}

/** 一場已結算的專注（放棄的不寫入） */
@Entity(tableName = "focus_sessions")
data class FocusSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 這次是為了什麼（一行） */
    val intent: String,
    val category: String = "其他",
    /** 關聯任務 id 的 JSON 陣列，如 "[1,2]" */
    val linkedTaskIdsJson: String = "[]",
    val startedAt: Long,
    val endedAt: Long,
    /** 有效專注毫秒（暫停不含） */
    val effectiveMs: Long,
    /** 結算現金（元，可為負） */
    val cashYuan: Int
)

/** 撲滿 ↔ 現金待轉移；確認後才入帳／扣款 */
@Entity(tableName = "pending_physical_transfers")
data class PendingPhysicalTransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountCents: Long,
    val fromWalletId: String,
    val toWalletId: String = WalletCodes.LIVING,
    val taskId: Long? = null,
    val taskTitle: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val confirmedAt: Long? = null
) {
    fun isFromLivingToPiggy(): Boolean =
        fromWalletId == WalletCodes.LIVING && WalletCodes.isPiggyWallet(toWalletId)

    /**
     * 對現金戶的淨效果。正值＝進入現金；負值＝離開現金。
     * 離開現金含撲滿，以及專注結算扣回中央（中央不在撲滿桶內，仍要計入待轉移金額）。
     */
    fun livingNetCents(): Long = when {
        toWalletId == WalletCodes.LIVING -> amountCents
        fromWalletId == WalletCodes.LIVING && (
            WalletCodes.isPiggyWallet(toWalletId) ||
                toWalletId == WalletCodes.CENTRAL ||
                toWalletId == WalletCodes.CENTRAL_FUTURE
            ) -> -amountCents
        else -> 0L
    }

    fun repayLoanId(): Long? = PhysicalTransferNotes.parseRepayLoanId(taskTitle)

    fun borrowLoanId(): Long? = PhysicalTransferNotes.parseBorrowLoanId(taskTitle)
}

object PhysicalTransferNotes {
    fun repay(loanId: Long) = "還款 #$loanId"
    fun borrow(loanId: Long) = "借款 #$loanId"

    fun parseRepayLoanId(title: String): Long? =
        Regex("""^還款 #(\d+)$""").matchEntire(title)?.groupValues?.get(1)?.toLongOrNull()

    fun parseBorrowLoanId(title: String): Long? =
        Regex("""^借款 #(\d+)$""").matchEntire(title)?.groupValues?.get(1)?.toLongOrNull()
}
