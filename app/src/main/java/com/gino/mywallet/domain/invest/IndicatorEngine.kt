package com.gino.mywallet.domain.invest

/** MACD 序列：三個 list 對齊 closes 的索引；前段不足時為 null */
data class MacdSeries(
    val dif: List<Double?>,
    val dea: List<Double?>,
    val histogram: List<Double?>
) {
    fun latestDif(): Double? = dif.lastOrNull { it != null }
    fun latestDea(): Double? = dea.lastOrNull { it != null }
    fun latestHistogram(): Double? = histogram.lastOrNull { it != null }
}

/** RSI / MACD 純函數計算，輸入為收盤價序列（由舊到新） */
object IndicatorEngine {

    const val RSI_PERIOD = 14
    const val MACD_FAST = 12
    const val MACD_SLOW = 26
    const val MACD_SIGNAL = 9

    /** 最少需要的日 K 根數（MACD 26+9），不足回傳 null 指標 */
    const val MIN_BARS = MACD_SLOW + MACD_SIGNAL

    /**
     * Wilder RSI(14)。回傳與 closes 對齊的序列；索引 < period 為 null。
     */
    fun rsi(closes: List<Double>, period: Int = RSI_PERIOD): List<Double?> {
        if (closes.size <= period) return List(closes.size) { null }
        val result = arrayOfNulls<Double>(closes.size)
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val diff = closes[i] - closes[i - 1]
            if (diff >= 0) avgGain += diff else avgLoss -= diff
        }
        avgGain /= period
        avgLoss /= period
        result[period] = rsiValue(avgGain, avgLoss)
        for (i in period + 1 until closes.size) {
            val diff = closes[i] - closes[i - 1]
            val gain = if (diff > 0) diff else 0.0
            val loss = if (diff < 0) -diff else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
            result[i] = rsiValue(avgGain, avgLoss)
        }
        return result.toList()
    }

    private fun rsiValue(avgGain: Double, avgLoss: Double): Double =
        if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)

    /** 最新一筆 RSI；資料不足回傳 null */
    fun latestRsi(closes: List<Double>, period: Int = RSI_PERIOD): Double? =
        rsi(closes, period).lastOrNull { it != null }

    /**
     * MACD(12,26,9)：DIF = EMA12 − EMA26，DEA = DIF 的 EMA9，柱 = DIF − DEA。
     */
    fun macd(
        closes: List<Double>,
        fast: Int = MACD_FAST,
        slow: Int = MACD_SLOW,
        signal: Int = MACD_SIGNAL
    ): MacdSeries {
        val n = closes.size
        val emaFast = ema(closes, fast)
        val emaSlow = ema(closes, slow)
        val dif = arrayOfNulls<Double>(n)
        for (i in 0 until n) {
            val f = emaFast[i]
            val s = emaSlow[i]
            if (f != null && s != null) dif[i] = f - s
        }
        // DEA：對非 null 的 DIF 段再取 EMA(signal)
        val dea = arrayOfNulls<Double>(n)
        val firstDif = dif.indexOfFirst { it != null }
        if (firstDif >= 0) {
            val difValues = dif.drop(firstDif).map { it!! }
            val deaValues = ema(difValues, signal)
            for (i in difValues.indices) {
                deaValues[i]?.let { dea[firstDif + i] = it }
            }
        }
        val hist = arrayOfNulls<Double>(n)
        for (i in 0 until n) {
            val d = dif[i]
            val e = dea[i]
            if (d != null && e != null) hist[i] = d - e
        }
        return MacdSeries(dif.toList(), dea.toList(), hist.toList())
    }

    /** 指數移動平均；索引 < period-1 為 null，起點用 SMA */
    private fun ema(values: List<Double>, period: Int): Array<Double?> {
        val result = arrayOfNulls<Double>(values.size)
        if (values.size < period) return result
        var prev = values.take(period).average()
        result[period - 1] = prev
        val k = 2.0 / (period + 1)
        for (i in period until values.size) {
            prev = values[i] * k + prev * (1 - k)
            result[i] = prev
        }
        return result
    }
}
