package com.gino.mywallet.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

object StockMarkets {
    const val TW = "TW"
    const val US = "US"

    /** 轉成 Yahoo Finance 代號 */
    fun yahooSymbol(symbol: String, market: String): String =
        if (market == TW) "$symbol.TW" else symbol
}

/** 已買持股；symbol 為主鍵（台股 2330、美股 AAPL） */
@Entity(tableName = "stock_holdings")
data class StockHoldingEntity(
    @PrimaryKey val symbol: String,
    val name: String = "",
    val market: String = StockMarkets.TW,
    /** 毫股：1000 = 1 股，支援零股與小數股 */
    val sharesMilli: Long,
    /** 總成本（cents），賣出時按比例減少 */
    val costCents: Long = 0,
    /** 最近收盤/成交價快取（cents/股） */
    val lastPriceCents: Long = 0,
    val lastQuotedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 市值 = 股數 × 最近價 */
    fun marketValueCents(): Long = sharesMilli * lastPriceCents / 1000
}

/** 觀察清單（可標記待買） */
@Entity(tableName = "stock_watchlist")
data class StockWatchEntity(
    @PrimaryKey val symbol: String,
    val name: String = "",
    val market: String = StockMarkets.TW,
    val note: String = "",
    /** 0 = 未設定 */
    val targetPriceCents: Long = 0,
    val wantBuy: Boolean = false,
    val lastPriceCents: Long = 0,
    val lastQuotedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/** 日 K 快取，用於畫圖與計算 RSI/MACD */
@Entity(tableName = "stock_daily_bars", primaryKeys = ["symbol", "dateYmd"])
data class StockDailyBarEntity(
    val symbol: String,
    val dateYmd: String,
    val openCents: Long,
    val highCents: Long,
    val lowCents: Long,
    val closeCents: Long,
    val volume: Long
)
