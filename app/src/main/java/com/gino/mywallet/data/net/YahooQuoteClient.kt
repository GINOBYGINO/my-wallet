package com.gino.mywallet.data.net

import com.gino.mywallet.data.entity.StockDailyBarEntity
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/** Yahoo Finance 日 K 抓取結果 */
data class QuoteChart(
    val bars: List<StockDailyBarEntity>,
    /** 盤中最新價（cents），可能比最後一根日 K 新 */
    val lastPriceCents: Long,
    val name: String
)

/**
 * 免金鑰報價：優先 Yahoo chart，失敗時台股改走證交所 MIS。
 */
object YahooQuoteClient {

    private const val TIMEOUT_MS = 15_000
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    private val hosts = listOf(
        "https://query1.finance.yahoo.com",
        "https://query2.finance.yahoo.com"
    )

    /**
     * 抓一年日 K + 最新價。
     * @param yahooSymbol 例：2330.TW、AAPL
     * @param storeSymbol 存進 DB 的代號（不含 .TW）
     */
    suspend fun fetchDaily(yahooSymbol: String, storeSymbol: String): QuoteChart =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for ((index, host) in hosts.withIndex()) {
                if (index > 0) delay(300)
                try {
                    return@withContext fetchYahoo(host, yahooSymbol, storeSymbol)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            // 台股備援：證交所即時 + 近一年日線（若有）
            if (yahooSymbol.endsWith(".TW", ignoreCase = true) ||
                yahooSymbol.endsWith(".TWO", ignoreCase = true)
            ) {
                try {
                    return@withContext fetchTwse(storeSymbol)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("報價失敗")
        }

    private fun fetchYahoo(host: String, yahooSymbol: String, storeSymbol: String): QuoteChart {
        val encoded = URLEncoder.encode(yahooSymbol, Charsets.UTF_8.name())
        val url = URL("$host/v8/finance/chart/$encoded?interval=1d&range=1y")
        val body = httpGet(url)
        return parseYahooChart(body, storeSymbol)
    }

    private fun fetchTwse(storeSymbol: String): QuoteChart {
        // 上市／上櫃即時價
        val encoded = URLEncoder.encode(storeSymbol, Charsets.UTF_8.name())
        val json = httpGet(
            URL("https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=tse_$encoded.tw|otc_$encoded.tw&json=1&delay=0")
        )
        val root = JsonParser.parseString(json).asJsonObject
        val arr = root.getAsJsonArray("msgArray")
            ?: error("證交所回應無資料")
        if (arr.size() == 0) error("證交所查無 $storeSymbol")
        val row = arr[0].asJsonObject
        val name = row.str("n").ifBlank { storeSymbol }
        val price = listOf("z", "y", "o")
            .mapNotNull { key -> row.str(key).toDoubleOrNull()?.takeIf { it > 0 } }
            .firstOrNull()
            ?: error("證交所缺最新價")
        val open = row.str("o").toDoubleOrNull()?.takeIf { it > 0 } ?: price
        val high = row.str("h").toDoubleOrNull()?.takeIf { it > 0 } ?: price
        val low = row.str("l").toDoubleOrNull()?.takeIf { it > 0 } ?: price
        val volume = row.str("v").replace(",", "").toLongOrNull() ?: 0L
        val today = LocalDate.now(ZoneId.of("Asia/Taipei")).toString()
        // 近數月日線；失敗則至少保留今日一根
        val history = runCatching { fetchTwseDaily(storeSymbol, months = 6) }.getOrDefault(emptyList())
        val bars = if (history.isNotEmpty()) {
            // 用即時價覆寫／補上今日
            val merged = history.associateBy { it.dateYmd }.toMutableMap()
            merged[today] = StockDailyBarEntity(
                symbol = storeSymbol,
                dateYmd = today,
                openCents = toCents(open),
                highCents = toCents(high),
                lowCents = toCents(low),
                closeCents = toCents(price),
                volume = volume
            )
            merged.values.sortedBy { it.dateYmd }
        } else {
            listOf(
                StockDailyBarEntity(
                    symbol = storeSymbol,
                    dateYmd = today,
                    openCents = toCents(open),
                    highCents = toCents(high),
                    lowCents = toCents(low),
                    closeCents = toCents(price),
                    volume = volume
                )
            )
        }
        return QuoteChart(
            bars = bars,
            lastPriceCents = toCents(price),
            name = name
        )
    }

    /** 證交所 STOCK_DAY 近 N 個月（上市） */
    private fun fetchTwseDaily(storeSymbol: String, months: Int): List<StockDailyBarEntity> {
        val zone = ZoneId.of("Asia/Taipei")
        val end = LocalDate.now(zone)
        val start = end.minusMonths(months.toLong()).withDayOfMonth(1)
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")
        val bars = LinkedHashMap<String, StockDailyBarEntity>()
        var cursor = start
        while (!cursor.isAfter(end)) {
            val dateParam = cursor.format(fmt)
            val url = URL(
                "https://www.twse.com.tw/exchangeReport/STOCK_DAY" +
                    "?response=json&date=$dateParam&stockNo=$storeSymbol"
            )
            try {
                val body = httpGet(url)
                val root = JsonParser.parseString(body).asJsonObject
                if (root.str("stat") == "OK") {
                    val data = root.getAsJsonArray("data")
                    if (data != null) {
                        for (i in 0 until data.size()) {
                            val row = data[i].asJsonArray
                            val roc = row[0].asString.trim()
                            val parts = roc.split("/")
                            if (parts.size != 3) continue
                            val y = parts[0].toIntOrNull()?.plus(1911) ?: continue
                            val m = parts[1].toIntOrNull() ?: continue
                            val d = parts[2].toIntOrNull() ?: continue
                            val dateYmd = LocalDate.of(y, m, d).toString()
                            val open = parseTwNum(row[3].asString) ?: continue
                            val high = parseTwNum(row[4].asString) ?: continue
                            val low = parseTwNum(row[5].asString) ?: continue
                            val close = parseTwNum(row[6].asString) ?: continue
                            val vol = parseTwNum(row[1].asString)?.toLong() ?: 0L
                            bars[dateYmd] = StockDailyBarEntity(
                                symbol = storeSymbol,
                                dateYmd = dateYmd,
                                openCents = toCents(open),
                                highCents = toCents(high),
                                lowCents = toCents(low),
                                closeCents = toCents(close),
                                volume = vol
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // 單月失敗略過
            }
            cursor = cursor.plusMonths(1)
            Thread.sleep(150)
        }
        return bars.values.sortedBy { it.dateYmd }
    }

    /** 供單元測試／內部呼叫 */
    internal fun parseYahooChartForTest(json: String, storeSymbol: String): QuoteChart =
        parseYahooChart(json, storeSymbol)

    private fun parseYahooChart(json: String, storeSymbol: String): QuoteChart {
        val root = JsonParser.parseString(json).asJsonObject
        val chart = root.getAsJsonObject("chart")
            ?: error("報價回應格式錯誤")
        // Yahoo 成功時 error 為 JsonNull，不可 getAsJsonArray
        val errEl = chart.get("error")
        if (errEl != null && !errEl.isJsonNull) {
            error("報價錯誤：$errEl")
        }
        val results = chart.get("result")
        if (results == null || results.isJsonNull || !results.isJsonArray || results.asJsonArray.size() == 0) {
            error("報價回應無資料")
        }
        val result = results.asJsonArray[0].asJsonObject
        val meta = result.getAsJsonObject("meta")
            ?: error("報價回應缺 meta")
        val zone = runCatching {
            ZoneId.of(meta.get("exchangeTimezoneName").asString)
        }.getOrDefault(ZoneId.of("Asia/Taipei"))
        val lastPrice = meta.num("regularMarketPrice")
            ?: meta.num("previousClose")
            ?: meta.num("chartPreviousClose")
            ?: error("報價回應缺最新價")
        val name = meta.str("shortName").ifBlank {
            meta.str("longName").ifBlank { storeSymbol }
        }

        val timestamps = result.getAsJsonArray("timestamp")
            ?: error("報價回應缺時間軸")
        val quote = result.getAsJsonObject("indicators")
            ?.getAsJsonArray("quote")
            ?.get(0)?.asJsonObject
            ?: error("報價回應缺 OHLCV")
        val opens = quote.getAsJsonArray("open")
        val highs = quote.getAsJsonArray("high")
        val lows = quote.getAsJsonArray("low")
        val closes = quote.getAsJsonArray("close")
        val volumes = quote.getAsJsonArray("volume")

        val bars = ArrayList<StockDailyBarEntity>(timestamps.size())
        for (i in 0 until timestamps.size()) {
            val closeEl = closes?.get(i)
            if (closeEl == null || closeEl.isJsonNull) continue
            val close = closeEl.asDouble
            val openEl = opens?.get(i)
            val highEl = highs?.get(i)
            val lowEl = lows?.get(i)
            val volEl = volumes?.get(i)
            val dateYmd = Instant.ofEpochSecond(timestamps[i].asLong)
                .atZone(zone).toLocalDate().toString()
            bars.add(
                StockDailyBarEntity(
                    symbol = storeSymbol,
                    dateYmd = dateYmd,
                    openCents = toCents(
                        if (openEl == null || openEl.isJsonNull) close else openEl.asDouble
                    ),
                    highCents = toCents(
                        if (highEl == null || highEl.isJsonNull) close else highEl.asDouble
                    ),
                    lowCents = toCents(
                        if (lowEl == null || lowEl.isJsonNull) close else lowEl.asDouble
                    ),
                    closeCents = toCents(close),
                    volume = if (volEl == null || volEl.isJsonNull) 0L else volEl.asLong
                )
            )
        }
        if (bars.isEmpty()) error("報價回應無有效日 K")
        return QuoteChart(
            bars = bars,
            lastPriceCents = toCents(lastPrice),
            name = name
        )
    }

    private fun httpGet(url: URL): String {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json,text/plain,*/*")
        conn.setRequestProperty("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.8")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                error("報價服務回應 $code${body.take(80).let { if (it.isBlank()) "" else "：$it" }}")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun JsonObject.str(key: String): String {
        val el = get(key) ?: return ""
        return if (el.isJsonNull) "" else el.asString
    }

    private fun JsonObject.num(key: String): Double? {
        val el = get(key) ?: return null
        if (el.isJsonNull) return null
        return runCatching { el.asDouble }.getOrNull()
    }

    private fun parseTwNum(raw: String): Double? =
        raw.trim().replace(",", "").takeIf { it.isNotEmpty() && it != "--" }?.toDoubleOrNull()

    private fun toCents(price: Double): Long = (price * 100).roundToLong()
}
