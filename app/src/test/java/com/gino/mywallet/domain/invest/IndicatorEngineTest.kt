package com.gino.mywallet.domain.invest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorEngineTest {

    /** Wilder RSI 經典範例資料（J. Welles Wilder 原書），RSI14 首值約 70.46 */
    private val wilderCloses = listOf(
        44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42,
        45.84, 46.08, 45.89, 46.03, 45.61, 46.28, 46.28, 46.00,
        46.03, 46.41, 46.22, 45.64, 46.21, 46.25, 45.71, 46.45,
        45.78, 45.35, 44.03, 44.18, 44.22, 44.57, 43.42, 42.66, 43.13
    )

    @Test
    fun rsiMatchesWilderReference() {
        val rsi = IndicatorEngine.rsi(wilderCloses)
        // 前 14 筆不足，應為 null
        for (i in 0 until 14) assertNull(rsi[i])
        assertEquals(70.46, rsi[14]!!, 0.1)
        assertEquals(66.25, rsi[15]!!, 0.2)
    }

    @Test
    fun rsiAllGainsIsHundred() {
        val closes = (1..40).map { it.toDouble() }
        assertEquals(100.0, IndicatorEngine.latestRsi(closes)!!, 1e-9)
    }

    @Test
    fun rsiInsufficientDataReturnsNull() {
        assertNull(IndicatorEngine.latestRsi(listOf(1.0, 2.0, 3.0)))
        assertTrue(IndicatorEngine.rsi(listOf(1.0, 2.0)).all { it == null })
    }

    @Test
    fun macdConstantSeriesIsZero() {
        val closes = List(60) { 100.0 }
        val macd = IndicatorEngine.macd(closes)
        assertEquals(0.0, macd.latestDif()!!, 1e-9)
        assertEquals(0.0, macd.latestDea()!!, 1e-9)
        assertEquals(0.0, macd.latestHistogram()!!, 1e-9)
    }

    @Test
    fun macdUptrendHasPositiveDif() {
        // 持續上漲：快線在慢線之上，DIF > 0
        val closes = (1..80).map { 100.0 + it }
        val macd = IndicatorEngine.macd(closes)
        assertNotNull(macd.latestDif())
        assertTrue(macd.latestDif()!! > 0)
        // 等速上漲收斂後 DIF 穩定，柱應接近 0
        assertEquals(0.0, macd.latestHistogram()!!, 0.05)
    }

    @Test
    fun macdAlignmentAndNullPrefix() {
        val closes = (1..60).map { 100.0 + it * 0.5 }
        val macd = IndicatorEngine.macd(closes)
        assertEquals(closes.size, macd.dif.size)
        assertEquals(closes.size, macd.dea.size)
        assertEquals(closes.size, macd.histogram.size)
        // DIF 從 EMA26 齊備處開始（索引 25）
        assertNull(macd.dif[24])
        assertNotNull(macd.dif[25])
        // DEA 需再等 9 筆 DIF（索引 25+8=33）
        assertNull(macd.dea[32])
        assertNotNull(macd.dea[33])
        assertNotNull(macd.histogram[33])
    }

    @Test
    fun minBarsConstant() {
        assertEquals(35, IndicatorEngine.MIN_BARS)
    }
}
