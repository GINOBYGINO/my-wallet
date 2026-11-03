package com.gino.mywallet.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 折線圖：單序列（收盤價等） */
@Composable
internal fun LineChart(
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    minOverride: Double? = null,
    maxOverride: Double? = null,
    guides: List<Double> = emptyList(),
    guideColor: Color = MaterialTheme.colorScheme.outlineVariant
) {
    Canvas(modifier = modifier.fillMaxWidth().height(140.dp)) {
        if (values.size < 2) return@Canvas
        val min = minOverride ?: values.min()
        val max = maxOverride ?: values.max()
        drawGuides(guides, min, max, guideColor)
        drawLine(values, min, max, color)
    }
}

/** 折線圖：兩序列（比較頁正規化漲跌） */
@Composable
internal fun DualLineChart(
    a: List<Double>,
    b: List<Double>,
    modifier: Modifier = Modifier,
    colorA: Color = MaterialTheme.colorScheme.primary,
    colorB: Color = MaterialTheme.colorScheme.tertiary
) {
    val guideColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier.fillMaxWidth().height(160.dp)) {
        val all = a + b
        if (all.size < 2) return@Canvas
        val min = all.min()
        val max = all.max()
        // 0% 基準線
        drawGuides(listOf(0.0), min, max, guideColor)
        if (a.size >= 2) drawLine(a, min, max, colorA)
        if (b.size >= 2) drawLine(b, min, max, colorB)
    }
}

/** MACD 柱狀圖 + DIF/DEA 線 */
@Composable
internal fun MacdChart(
    dif: List<Double?>,
    dea: List<Double?>,
    histogram: List<Double?>,
    modifier: Modifier = Modifier
) {
    val difColor = MaterialTheme.colorScheme.primary
    val deaColor = MaterialTheme.colorScheme.tertiary
    val posColor = MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
    val negColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f)
    val guideColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier.fillMaxWidth().height(120.dp)) {
        val values = (dif + dea + histogram).filterNotNull()
        if (values.size < 2) return@Canvas
        val min = values.min().coerceAtMost(0.0)
        val max = values.max().coerceAtLeast(0.0)
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        fun y(v: Double): Float = (size.height * (1 - (v - min) / range)).toFloat()
        val n = histogram.size
        val stepX = size.width / (n - 1).coerceAtLeast(1)
        // 0 軸
        drawLine(
            color = guideColor,
            start = Offset(0f, y(0.0)),
            end = Offset(size.width, y(0.0)),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        )
        // 柱
        val barW = (stepX * 0.6f).coerceAtLeast(1f)
        for (i in 0 until n) {
            val h = histogram[i] ?: continue
            val x = i * stepX
            val zero = y(0.0)
            val top = y(h)
            drawLine(
                color = if (h >= 0) posColor else negColor,
                start = Offset(x, zero),
                end = Offset(x, top),
                strokeWidth = barW
            )
        }
        drawNullableLine(dif, min, max, difColor)
        drawNullableLine(dea, min, max, deaColor)
    }
}

@Composable
internal fun LegendDotSmall(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun DrawScope.drawGuides(
    guides: List<Double>,
    min: Double,
    max: Double,
    color: Color
) {
    val range = (max - min).takeIf { it > 0 } ?: 1.0
    guides.forEach { g ->
        if (g < min || g > max) return@forEach
        val y = (size.height * (1 - (g - min) / range)).toFloat()
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        )
    }
}

private fun DrawScope.drawLine(
    values: List<Double>,
    min: Double,
    max: Double,
    color: Color
) {
    val range = (max - min).takeIf { it > 0 } ?: 1.0
    val stepX = size.width / (values.size - 1)
    val path = Path()
    values.forEachIndexed { i, v ->
        val x = i * stepX
        val y = (size.height * (1 - (v - min) / range)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
}

private fun DrawScope.drawNullableLine(
    values: List<Double?>,
    min: Double,
    max: Double,
    color: Color
) {
    val range = (max - min).takeIf { it > 0 } ?: 1.0
    val n = values.size
    if (n < 2) return
    val stepX = size.width / (n - 1)
    val path = Path()
    var started = false
    values.forEachIndexed { i, v ->
        if (v == null) return@forEachIndexed
        val x = i * stepX
        val y = (size.height * (1 - (v - min) / range)).toFloat()
        if (!started) {
            path.moveTo(x, y)
            started = true
        } else {
            path.lineTo(x, y)
        }
    }
    drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
}
