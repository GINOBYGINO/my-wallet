package com.gino.mywallet.ui.invest

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gino.mywallet.MyWalletApp
import com.gino.mywallet.data.entity.StockDailyBarEntity
import com.gino.mywallet.data.entity.StockHoldingEntity
import com.gino.mywallet.data.entity.StockMarkets
import com.gino.mywallet.data.entity.StockWatchEntity
import com.gino.mywallet.data.entity.WalletCodes
import com.gino.mywallet.data.net.YahooQuoteClient
import com.gino.mywallet.domain.invest.IndicatorEngine
import com.gino.mywallet.domain.invest.MacdSeries
import com.gino.mywallet.ui.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.roundToLong

/** 個股完整指標資料（詳情頁） */
data class StockDetailUi(
    val symbol: String,
    val name: String,
    val bars: List<StockDailyBarEntity>,
    val rsi: List<Double?>,
    val macd: MacdSeries,
    val holding: StockHoldingEntity?,
    val lastPriceCents: Long,
    val insufficient: Boolean
)

/** 比較頁單邊 */
data class CompareLeg(
    val symbol: String,
    val name: String,
    /** 期間內相對第一天的漲跌％（對齊 dates） */
    val normalizedPct: List<Double>,
    val changePct: Double,
    val latestRsi: Double?,
    val latestDif: Double?,
    val latestDea: Double?,
    val latestHistogram: Double?,
    val lastPriceCents: Long
)

data class CompareUi(val a: CompareLeg, val b: CompareLeg, val days: Int)

class InvestViewModel(app: Application) : AndroidViewModel(app) {
    private val db = MyWalletApp.instance.database
    private val engine get() = MyWalletApp.instance.engine
    private val stockDao = db.stockDao()

    companion object {
        /** 自動刷新最短間隔 */
        const val REFRESH_MIN_INTERVAL_MS = 5 * 60_000L
        /** 比較／圖表預設期間（交易日數） */
        const val CHART_DAYS = 120
    }

    val holdings: StateFlow<List<StockHoldingEntity>> =
        stockDao.observeHoldings()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val watchlist: StateFlow<List<StockWatchEntity>> =
        stockDao.observeWatchlist()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 投資錢包現金（cents） */
    val investCashCents: StateFlow<Long> =
        db.walletDao().observeAll()
            .map { list -> list.find { it.id == WalletCodes.INVEST }?.balanceCents ?: 0L }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val _detail = MutableStateFlow<StockDetailUi?>(null)
    val detail: StateFlow<StockDetailUi?> = _detail.asStateFlow()

    private val _compare = MutableStateFlow<CompareUi?>(null)
    val compare: StateFlow<CompareUi?> = _compare.asStateFlow()

    private var lastRefreshOkAt = 0L

    fun clearMessage() {
        _message.value = null
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

    // ---------- 報價刷新 ----------

    /** 刷新已買＋觀察所有代號；非強制時 5 分鐘內不重打 */
    fun refreshQuotes(force: Boolean = false) {
        viewModelScope.launch {
            if (_refreshing.value) return@launch
            val now = System.currentTimeMillis()
            if (!force && now - lastRefreshOkAt < REFRESH_MIN_INTERVAL_MS) return@launch
            _refreshing.value = true
            try {
                val targets = LinkedHashMap<String, String>() // symbol -> market
                stockDao.getHoldings().forEach { targets[it.symbol] = it.market }
                stockDao.getWatchlist().forEach { targets.putIfAbsent(it.symbol, it.market) }
                if (targets.isEmpty()) {
                    _message.value = UiMessage("尚無持股或觀察，無法更新報價")
                    return@launch
                }
                var ok = 0
                var fail = 0
                var firstError: String? = null
                targets.forEach { (symbol, market) ->
                    val err = fetchSymbol(symbol, market)
                    if (err == null) ok++ else {
                        fail++
                        if (firstError == null) firstError = "$symbol：$err"
                    }
                }
                if (ok > 0) lastRefreshOkAt = System.currentTimeMillis()
                _message.value = UiMessage(
                    when {
                        fail == 0 -> "報價已更新（$ok 檔）"
                        ok == 0 -> "報價失敗：${firstError ?: "未知錯誤"}"
                        else -> "報價：成功 $ok、失敗 $fail（${firstError ?: "保留舊價"}）"
                    }
                )
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** 抓單一代號；成功回傳 null，失敗回傳錯誤訊息（保留舊快取） */
    private suspend fun fetchSymbol(symbol: String, market: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val chart = YahooQuoteClient.fetchDaily(
                    StockMarkets.yahooSymbol(symbol, market),
                    symbol
                )
                stockDao.upsertBars(chart.bars)
                stockDao.pruneBars(symbol, LocalDate.now().minusYears(1).toString())
                val now = System.currentTimeMillis()
                stockDao.getHolding(symbol)?.let { h ->
                    stockDao.upsertHolding(
                        h.copy(
                            lastPriceCents = chart.lastPriceCents,
                            lastQuotedAt = now,
                            name = h.name.ifBlank { chart.name }
                        )
                    )
                }
                stockDao.getWatch(symbol)?.let { w ->
                    stockDao.upsertWatch(
                        w.copy(
                            lastPriceCents = chart.lastPriceCents,
                            lastQuotedAt = now,
                            name = w.name.ifBlank { chart.name }
                        )
                    )
                }
                null
            }.getOrElse { e ->
                e.message?.take(120) ?: e.javaClass.simpleName
            }
        }

    // ---------- 持股 ----------

    /**
     * 新增／加碼持股。
     * @param buyWithCash true = 從投資現金扣款（記 STOCK_BUY）；false = 只記錄現有部位
     */
    fun addHolding(
        symbol: String,
        market: String,
        sharesText: String,
        priceText: String,
        buyWithCash: Boolean
    ) = run {
        val sym = normalizeSymbol(symbol)
        val sharesMilli = parseSharesMilli(sharesText)
        val priceCents = parsePriceCents(priceText)
        val totalCents = sharesMilli * priceCents / 1000
        require(totalCents > 0) { "金額需大於 0" }
        if (buyWithCash) engine.stockBuy(totalCents, sym)
        val existing = stockDao.getHolding(sym)
        if (existing == null) {
            stockDao.upsertHolding(
                StockHoldingEntity(
                    symbol = sym,
                    market = market,
                    sharesMilli = sharesMilli,
                    costCents = totalCents,
                    lastPriceCents = priceCents,
                    lastQuotedAt = System.currentTimeMillis()
                )
            )
        } else {
            stockDao.upsertHolding(
                existing.copy(
                    sharesMilli = existing.sharesMilli + sharesMilli,
                    costCents = existing.costCents + totalCents,
                    lastPriceCents = priceCents,
                    lastQuotedAt = System.currentTimeMillis()
                )
            )
        }
        _message.value = UiMessage(if (buyWithCash) "已買入 $sym" else "已記錄持股 $sym")
        // 背景抓日 K，詳情頁才有指標
        viewModelScope.launch { fetchSymbol(sym, market) }
    }

    /** 賣出：加回現金、按比例減成本；全數賣出即移除 */
    fun sellHolding(symbol: String, sharesText: String, priceText: String) = run {
        val h = stockDao.getHolding(symbol) ?: error("查無持股 $symbol")
        val sharesMilli = parseSharesMilli(sharesText)
        require(sharesMilli <= h.sharesMilli) {
            "持股不足（現有 ${fmtShares(h.sharesMilli)} 股）"
        }
        val priceCents = parsePriceCents(priceText)
        val totalCents = sharesMilli * priceCents / 1000
        require(totalCents > 0) { "金額需大於 0" }
        engine.stockSell(totalCents, symbol)
        val remaining = h.sharesMilli - sharesMilli
        if (remaining == 0L) {
            stockDao.deleteHolding(symbol)
        } else {
            stockDao.upsertHolding(
                h.copy(
                    sharesMilli = remaining,
                    costCents = h.costCents * remaining / h.sharesMilli,
                    lastPriceCents = priceCents,
                    lastQuotedAt = System.currentTimeMillis()
                )
            )
        }
        _message.value = UiMessage("已賣出 $symbol")
    }

    /** 直接移除持股（不動現金；例如記錯） */
    fun deleteHolding(symbol: String) = run {
        stockDao.deleteHolding(symbol)
        _message.value = UiMessage("已移除持股 $symbol")
    }

    // ---------- 觀察清單 ----------

    fun addWatch(
        symbol: String,
        market: String,
        note: String,
        targetPriceText: String,
        wantBuy: Boolean
    ) = run {
        val sym = normalizeSymbol(symbol)
        val target = targetPriceText.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { parsePriceCents(it) }
            ?: 0L
        val existing = stockDao.getWatch(sym)
        stockDao.upsertWatch(
            StockWatchEntity(
                symbol = sym,
                name = existing?.name.orEmpty(),
                market = market,
                note = note.trim(),
                targetPriceCents = target,
                wantBuy = wantBuy,
                lastPriceCents = existing?.lastPriceCents ?: 0L,
                lastQuotedAt = existing?.lastQuotedAt ?: 0L,
                createdAt = existing?.createdAt ?: System.currentTimeMillis()
            )
        )
        _message.value = UiMessage(if (existing == null) "已加入觀察 $sym" else "已更新觀察 $sym")
        viewModelScope.launch { fetchSymbol(sym, market) }
    }

    fun deleteWatch(symbol: String) = run {
        stockDao.deleteWatch(symbol)
        _message.value = UiMessage("已移除觀察 $symbol")
    }

    fun toggleWantBuy(symbol: String) = run {
        val w = stockDao.getWatch(symbol) ?: return@run
        stockDao.upsertWatch(w.copy(wantBuy = !w.wantBuy))
    }

    // ---------- 現金平帳 ----------

    /** 對齊券商可用現金（沿用平帳，僅現金） */
    fun adjustCash(targetYuan: String) = run {
        val v = targetYuan.trim().toDoubleOrNull() ?: error("金額格式錯誤")
        require(v >= 0) { "金額不可為負" }
        engine.setBalanceAdjust(WalletCodes.INVEST, (v * 100).roundToLong())
        _message.value = UiMessage("投資現金已更新")
    }

    // ---------- 個股詳情 ----------

    fun loadDetail(symbol: String) {
        viewModelScope.launch {
            _detail.value = null
            _detail.value = buildDetail(symbol)
            // 沒日 K 時嘗試抓一次再重建
            if (_detail.value?.bars.isNullOrEmpty()) {
                val market = marketOf(symbol)
                if (fetchSymbol(symbol, market) == null) {
                    _detail.value = buildDetail(symbol)
                }
            }
        }
    }

    private suspend fun buildDetail(symbol: String): StockDetailUi {
        val bars = stockDao.getBars(symbol)
        val holding = stockDao.getHolding(symbol)
        val watch = stockDao.getWatch(symbol)
        val closes = bars.map { it.closeCents / 100.0 }
        val lastPrice = holding?.lastPriceCents
            ?: watch?.lastPriceCents
            ?: bars.lastOrNull()?.closeCents
            ?: 0L
        return StockDetailUi(
            symbol = symbol,
            name = holding?.name ?: watch?.name ?: symbol,
            bars = bars,
            rsi = IndicatorEngine.rsi(closes),
            macd = IndicatorEngine.macd(closes),
            holding = holding,
            lastPriceCents = lastPrice,
            insufficient = closes.size < IndicatorEngine.MIN_BARS
        )
    }

    // ---------- 兩檔比較 ----------

    fun loadCompare(symbolA: String, symbolB: String, days: Int = CHART_DAYS) {
        viewModelScope.launch {
            try {
                val a = buildLeg(symbolA, days) ?: error("$symbolA 尚無日 K，請先更新報價")
                val b = buildLeg(symbolB, days) ?: error("$symbolB 尚無日 K，請先更新報價")
                _compare.value = CompareUi(a, b, days)
            } catch (e: Exception) {
                _compare.value = null
                _message.value = UiMessage(e.message ?: e.toString())
            }
        }
    }

    fun clearCompare() {
        _compare.value = null
    }

    private suspend fun buildLeg(symbol: String, days: Int): CompareLeg? {
        val allBars = stockDao.getBars(symbol)
        if (allBars.isEmpty()) return null
        val allCloses = allBars.map { it.closeCents / 100.0 }
        val window = allCloses.takeLast(days)
        val base = window.first()
        val normalized = window.map { (it / base - 1) * 100 }
        val macd = IndicatorEngine.macd(allCloses)
        val holding = stockDao.getHolding(symbol)
        val watch = stockDao.getWatch(symbol)
        return CompareLeg(
            symbol = symbol,
            name = holding?.name ?: watch?.name ?: symbol,
            normalizedPct = normalized,
            changePct = normalized.last(),
            latestRsi = IndicatorEngine.latestRsi(allCloses),
            latestDif = macd.latestDif(),
            latestDea = macd.latestDea(),
            latestHistogram = macd.latestHistogram(),
            lastPriceCents = holding?.lastPriceCents
                ?: watch?.lastPriceCents
                ?: allBars.last().closeCents
        )
    }

    // ---------- 小工具 ----------

    private suspend fun marketOf(symbol: String): String =
        stockDao.getHolding(symbol)?.market
            ?: stockDao.getWatch(symbol)?.market
            ?: if (symbol.all { it.isDigit() }) StockMarkets.TW else StockMarkets.US

    private fun normalizeSymbol(raw: String): String {
        val s = raw.trim().uppercase().removeSuffix(".TW").removeSuffix(".TWO")
        require(s.isNotBlank()) { "請輸入代號" }
        require(s.all { it.isLetterOrDigit() || it == '.' || it == '-' }) { "代號格式錯誤" }
        return s
    }

    private fun parseSharesMilli(text: String): Long {
        val v = text.trim().toDoubleOrNull() ?: error("股數格式錯誤")
        require(v > 0) { "股數需大於 0" }
        return (v * 1000).roundToLong()
    }

    private fun parsePriceCents(text: String): Long {
        val v = text.trim().toDoubleOrNull() ?: error("價格格式錯誤")
        require(v > 0) { "價格需大於 0" }
        return (v * 100).roundToLong()
    }
}

fun fmtShares(sharesMilli: Long): String {
    val whole = sharesMilli / 1000
    val frac = sharesMilli % 1000
    return if (frac == 0L) "$whole" else "%.3f".format(sharesMilli / 1000.0).trimEnd('0').trimEnd('.')
}
