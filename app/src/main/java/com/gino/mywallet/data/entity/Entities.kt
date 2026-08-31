package com.gino.mywallet.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 系統錢包代碼，種子資料用 */
object WalletCodes {
    const val CENTRAL = "central"
    const val LIVING = "living"       // 伙雜費
    const val BCI = "bci"
    const val VTUBER = "vtuber"
    const val ACG = "acg"
    const val REAL = "real"           // 已廢止；僅遷移用
    const val INVEST = "invest"       // 投資（主要出借方）
    const val STUDY = "study"
    const val SOCIAL = "social"
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
    /** 可選到期時間（epoch ms）；null＝無到期 */
    val dueAt: Long? = null
) {
    fun ticketLevel(): Int {
        if (!rewardJson.contains("TICKET_LEVEL")) return 0
        return Regex("\"level\"\\s*:\\s*(\\d+)").find(rewardJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0
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
    val kind: String, // TRANSFER / ... / TICKET_SLOT / TICKET_BJ / TICKET_SCRATCH / TICKET_DRAW / TICKET_LEVEL / TAX_RELIEF
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
    val lastFreeDrawDay: String = "",
    /** 下次可領冷卻券的時間 */
    val nextFreeAt: Long = 0L,
    /** 累計已抽次數（倍率局用） */
    val drawCount: Int = 0,
    /** 冷卻輪替：0🎰 1🃏 2🎫 3🎴 */
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
    val claimedReward: Boolean
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
    /** 中央欠債本金（分） */
    const val CENTRAL_DEBT_PRINCIPAL = "central_debt_principal"
    /** 中央欠債起算時間（epoch ms） */
    const val CENTRAL_DEBT_SINCE_MS = "central_debt_since_ms"
    const val GAME_BJ_JSON = "game_bj_json"
    const val GAME_HILO_JSON = "game_hilo_json"
    const val GAME_DRAW_JSON = "game_draw_json"
    const val GAME_SELECTED = "game_selected"
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
