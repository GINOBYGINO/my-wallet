package com.gino.mywallet.domain

import com.gino.mywallet.data.entity.TransactionEntity

/** 單筆交易對各錢包餘額的影響（分）；空 map＝不改餘額 */
fun transactionWalletDeltas(tx: TransactionEntity): Map<String, Long> {
    val amount = tx.amountCents
    if (amount <= 0L && tx.type != "BALANCE_ADJUST") return emptyMap()
    return when (tx.type) {
        "CENTRAL_DEBT_REPAY", "LOAN_INTEREST" -> emptyMap()
        "DONATION", "CENTRAL_DEBT_INTEREST", "STOCK_BUY", "CENTRAL_DEBT" -> {
            val from = tx.fromWalletId ?: return emptyMap()
            mapOf(from to -amount)
        }
        "INCOME_A", "STOCK_SELL" -> {
            val to = tx.toWalletId ?: return emptyMap()
            mapOf(to to amount)
        }
        "BALANCE_ADJUST" -> {
            when {
                tx.toWalletId != null -> mapOf(tx.toWalletId!! to amount)
                tx.fromWalletId != null -> mapOf(tx.fromWalletId!! to -amount)
                else -> emptyMap()
            }
        }
        else -> {
            val from = tx.fromWalletId
            val to = tx.toWalletId
            when {
                from != null && to != null -> mapOf(from to -amount, to to amount)
                from != null -> mapOf(from to -amount)
                to != null -> mapOf(to to amount)
                else -> emptyMap()
            }
        }
    }
}

/** 是否為「不進錢包餘額」的說明列 */
fun isNonBalanceTransaction(type: String): Boolean =
    type == "CENTRAL_DEBT_REPAY" || type == "LOAN_INTEREST"

data class WalletCashflowLine(
    val walletId: String,
    val openingCents: Long,
    val closingCents: Long,
    val netCents: Long
)

data class CashflowBucket(
    val key: String,
    val label: String,
    val cents: Long
)

data class MonthCashflowDigest(
    val month: String,
    val wallets: List<WalletCashflowLine>,
    val buckets: List<CashflowBucket>,
    /** 回推月初與「目前餘額 − 本月淨額」之間的絕對差額合計（分） */
    val reconcilingGapCents: Long,
    val nonBalanceNotes: List<CashflowBucket>
)

/**
 * 以目前餘額回推月初：opening = closing − Σ(本月影響)。
 * [reconcilingGapCents] 在純函式路徑恆為 0；引擎若另有外部對帳可覆寫。
 */
fun digestMonthCashflow(
    month: String,
    closingBalances: Map<String, Long>,
    monthTransactions: List<TransactionEntity>
): MonthCashflowDigest {
    val netByWallet = linkedMapOf<String, Long>()
    closingBalances.keys.forEach { netByWallet[it] = 0L }

    val bucketAcc = linkedMapOf(
        "income" to 0L,
        "transfer_net" to 0L,
        "reward" to 0L,
        "game" to 0L,
        "donation" to 0L,
        "loan_principal" to 0L,
        "stock" to 0L,
        "adjust" to 0L,
        "other" to 0L
    )
    val nonBalance = linkedMapOf<String, Long>()

    monthTransactions.forEach { tx ->
        if (isNonBalanceTransaction(tx.type)) {
            nonBalance[tx.type] = (nonBalance[tx.type] ?: 0L) + tx.amountCents
            return@forEach
        }
        val deltas = transactionWalletDeltas(tx)
        deltas.forEach { (id, d) ->
            netByWallet[id] = (netByWallet[id] ?: 0L) + d
        }
        val bucket = bucketKeyFor(tx.type)
        val signed = when (bucket) {
            "income" -> deltas.values.filter { it > 0 }.sum()
            "donation" -> deltas.values.filter { it < 0 }.sum().let { -it }
            "stock" -> deltas.filterKeys { it == "invest" }.values.sum().let { -it }
            "adjust" -> deltas.values.sum()
            "reward", "game" -> deltas.filterValues { it > 0 }.values.sum()
            "loan_principal" -> deltas.values.filter { it > 0 }.sum()
            else -> deltas.values.sum()
        }
        bucketAcc[bucket] = (bucketAcc[bucket] ?: 0L) + signed
    }

    val wallets = closingBalances.keys.sorted().map { id ->
        val closing = closingBalances[id] ?: 0L
        val net = netByWallet[id] ?: 0L
        val opening = closing - net
        WalletCashflowLine(walletId = id, openingCents = opening, closingCents = closing, netCents = net)
    }

    val labels = mapOf(
        "income" to "收入",
        "transfer_net" to "戶頭轉帳淨額",
        "reward" to "任務獎懲／發獎",
        "game" to "娛樂",
        "donation" to "公益捐獻",
        "loan_principal" to "借貸本金",
        "stock" to "股票買賣",
        "adjust" to "平帳",
        "other" to "其他"
    )
    val buckets = bucketAcc.map { (k, v) ->
        CashflowBucket(k, labels[k] ?: k, v)
    }.filter { it.cents != 0L }

    val nonBalanceNotes = nonBalance.map { (k, v) ->
        CashflowBucket(
            k,
            if (k == "LOAN_INTEREST") "借據月息（不進錢包）" else "中央還款記帳（不進錢包）",
            v
        )
    }

    return MonthCashflowDigest(
        month = month,
        wallets = wallets,
        buckets = buckets,
        reconcilingGapCents = 0L,
        nonBalanceNotes = nonBalanceNotes
    )
}

private fun bucketKeyFor(type: String): String = when (type) {
    "INCOME_A" -> "income"
    "TRANSFER", "PHYSICAL_TRANSFER", "FOCUS_SETTLE_DEFER", "CENTRAL_RELEASE" -> "transfer_net"
    "REWARD_TRANSFER", "FOCUS_SETTLE" -> "reward"
    "LOTTERY", "LOTTO", "ROULETTE", "TRIPLE_DRAW", "HILO", "WORDLE", "BJ", "MATCH", "MATH" -> "game"
    "DONATION" -> "donation"
    "LOAN_DISBURSE", "LOAN_REPAY", "LOAN_DEFAULT" -> "loan_principal"
    "STOCK_BUY", "STOCK_SELL" -> "stock"
    "BALANCE_ADJUST" -> "adjust"
    "CHARITY_TAX", "CENTRAL_DEBT", "CENTRAL_DEBT_INTEREST" -> "other"
    else -> "other"
}
