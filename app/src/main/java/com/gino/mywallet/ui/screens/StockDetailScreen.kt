package com.gino.mywallet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gino.mywallet.ui.components.SectionTitle
import com.gino.mywallet.ui.formatTwd
import com.gino.mywallet.ui.invest.InvestViewModel
import com.gino.mywallet.ui.invest.fmtShares

/** 個股詳情：價格走勢 + RSI + MACD */
@Composable
fun StockDetailScreen(symbol: String) {
    val investVm: InvestViewModel = viewModel()
    val detail by investVm.detail.collectAsStateWithLifecycle()

    LaunchedEffect(symbol) { investVm.loadDetail(symbol) }

    val d = detail
    if (d == null || d.symbol != symbol) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 標頭
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(d.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    d.symbol,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                fmtPrice(d.lastPriceCents),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        // 持股資訊
        d.holding?.let { h ->
            val mv = h.marketValueCents()
            val pnl = mv - h.costCents
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("我的部位", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "${fmtShares(h.sharesMilli)} 股 · ${formatTwd(mv)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "損益 ${formatTwd(pnl)}${pnlPctSuffix(pnl, h.costCents)}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = pnlColor(pnl)
                    )
                }
            }
        }

        if (d.bars.isEmpty()) {
            Text("尚無日 K", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            return@Column
        }

        val window = InvestViewModel.CHART_DAYS
        val bars = d.bars.takeLast(window)
        val closes = bars.map { it.closeCents / 100.0 }

        SectionTitle("價格")
        LineChart(values = closes)

        if (d.insufficient) {
            Text("資料不足", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            return@Column
        }

        val rsiWindow = d.rsi.takeLast(bars.size)
        val latestRsi = d.rsi.lastOrNull { it != null }
        SectionTitle("RSI(14)")
        latestRsi?.let { r ->
            Text(
                "%.1f".format(r) + rsiTag(r),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                color = when {
                    r >= 70 -> MaterialTheme.colorScheme.error
                    r <= 30 -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }
        LineChart(
            values = rsiWindow.map { it ?: 50.0 },
            minOverride = 0.0,
            maxOverride = 100.0,
            guides = listOf(30.0, 70.0),
            color = MaterialTheme.colorScheme.secondary
        )

        val macdDif = d.macd.dif.takeLast(bars.size)
        val macdDea = d.macd.dea.takeLast(bars.size)
        val macdHist = d.macd.histogram.takeLast(bars.size)
        SectionTitle("MACD(12,26,9)")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendDotSmall(
                MaterialTheme.colorScheme.primary,
                "DIF " + (d.macd.latestDif()?.let { "%.2f".format(it) } ?: "—")
            )
            LegendDotSmall(
                MaterialTheme.colorScheme.tertiary,
                "DEA " + (d.macd.latestDea()?.let { "%.2f".format(it) } ?: "—")
            )
            d.macd.latestHistogram()?.let { h ->
                Text(
                    "柱 ${"%.2f".format(h)}${macdTag(h)}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (h >= 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.secondary
                )
            }
        }
        MacdChart(dif = macdDif, dea = macdDea, histogram = macdHist)

        Spacer(Modifier.height(24.dp))
    }
}
