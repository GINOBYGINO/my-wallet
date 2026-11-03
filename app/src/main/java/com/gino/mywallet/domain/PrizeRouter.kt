package com.gino.mywallet.domain

import com.gino.mywallet.data.entity.WalletCodes
import com.gino.mywallet.data.entity.WalletEntity
import kotlin.random.Random

/** 動態獎金入帳結果（供通知／Toast） */
data class RoutedPrize(
    val walletId: String,
    val walletName: String,
    val amountCents: Long,
    /** 現金中獎＝待轉移，尚未實入帳 */
    val deferred: Boolean = false
) {
    fun arrowLabel(): String =
        if (deferred) " → ${walletName}（待轉移）" else " → $walletName"

    fun amountYuan(): Long = amountCents / 100
}

/** 遊戲結算：狀態 + 可選中獎戶／發券摘要 */
data class Priced<T>(
    val state: T,
    val prize: RoutedPrize? = null,
    /** 打卡發券結果文字，如 🎰×1 🃏×2 */
    val ticketGrant: String = ""
)

/**
 * 娛樂／通行證現金獎金抽選：
 * - 池子：現金 + 撲滿戶（不含中央、未來中央、投資、提款卡）
 * - 撲滿權重恆 1；現金超過 1500 元後降權＝1500／現金元
 * - 錢包頁「撲滿合計」另含中央與未來中央，與本池無關
 */
object PrizeRouter {
    const val CASH_SOFT_CAP_YUAN = 1500L

    fun cashWeight(cashBalanceCents: Long): Double {
        val yuan = cashBalanceCents.coerceAtLeast(0) / 100.0
        return if (yuan <= CASH_SOFT_CAP_YUAN) 1.0 else CASH_SOFT_CAP_YUAN / yuan
    }

    fun eligibleWallets(wallets: List<WalletEntity>): List<WalletEntity> =
        wallets.filter {
            !it.archived &&
                !it.isCentral &&
                it.id != WalletCodes.CENTRAL_FUTURE &&
                it.id != WalletCodes.INVEST &&
                it.id != WalletCodes.CARD
        }

    fun pickTarget(
        wallets: List<WalletEntity>,
        random: Random = Random.Default
    ): WalletEntity {
        val pool = eligibleWallets(wallets)
        require(pool.isNotEmpty()) { "無可用中獎戶頭" }
        val cashCents = pool.find { it.id == WalletCodes.LIVING }?.balanceCents ?: 0L
        val weighted = pool.map { w ->
            val weight = if (w.id == WalletCodes.LIVING) cashWeight(cashCents) else 1.0
            w to weight
        }
        val total = weighted.sumOf { it.second }
        var r = random.nextDouble() * total
        for ((w, weight) in weighted) {
            r -= weight
            if (r < 0) return w
        }
        return weighted.last().first
    }
}
