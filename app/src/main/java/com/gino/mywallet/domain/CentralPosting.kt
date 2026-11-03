package com.gino.mywallet.domain

import com.gino.mywallet.data.entity.WalletCodes

/** 勾選延後時，中央份額改記未來中央，等下一次公益完成才入帳。 */
fun centralShareDestination(deferCentral: Boolean): String =
    if (deferCentral) WalletCodes.CENTRAL_FUTURE else WalletCodes.CENTRAL

/** 把已算出的中央份額改記到 [destinationWalletId]；其餘戶頭不動。 */
fun routeCentralShare(splits: Map<String, Long>, destinationWalletId: String): LinkedHashMap<String, Long> {
    val out = LinkedHashMap<String, Long>()
    splits.forEach { (id, cents) ->
        val target = if (id == WalletCodes.CENTRAL) destinationWalletId else id
        if (cents != 0L) out[target] = (out[target] ?: 0L) + cents
    }
    return out
}
