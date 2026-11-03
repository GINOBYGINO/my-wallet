package com.gino.mywallet.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YahooQuoteClientParseTest {

    @Test
    fun parseSucceedsWhenErrorIsJsonNull() {
        // Yahoo 成功回應的 error 欄位是 null，舊程式對此呼叫 getAsJsonArray 會崩潰
        val json = """
            {"chart":{"result":[{"meta":{
              "currency":"TWD","symbol":"2330.TW","exchangeTimezoneName":"Asia/Taipei",
              "regularMarketPrice":2460.0,"shortName":"TSMC","chartPreviousClose":2410.0
            },"timestamp":[1788224400,1788310800],
            "indicators":{"quote":[{
              "open":[2395.0,2415.0],"high":[2440.0,2420.0],
              "low":[2390.0,2385.0],"close":[2440.0,2385.0],
              "volume":[100,200]
            }]}}],"error":null}}
        """.trimIndent()
        val chart = YahooQuoteClient.parseYahooChartForTest(json, "2330")
        assertEquals(2, chart.bars.size)
        assertEquals(2460_00L, chart.lastPriceCents)
        assertEquals("TSMC", chart.name)
        assertEquals(2440_00L, chart.bars[0].closeCents)
        assertTrue(chart.bars[0].dateYmd.isNotBlank())
    }
}
