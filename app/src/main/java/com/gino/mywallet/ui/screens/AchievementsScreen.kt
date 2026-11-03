package com.gino.mywallet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gino.mywallet.data.entity.FocusSessionEntity
import com.gino.mywallet.ui.AppViewModel
import com.gino.mywallet.ui.components.SectionTitle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SESSION_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm")

private fun fmtFocusDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "$h 小時 $m 分"
        h > 0 -> "$h 小時"
        else -> "$m 分"
    }
}

@Composable
fun AchievementsScreen(vm: AppViewModel) {
    val stats by vm.focusStats.collectAsStateWithLifecycle()
    val recent by vm.recentFocusSessions.collectAsStateWithLifecycle()
    var weekly by remember { mutableStateOf(true) }

    // 進頁與切換區間時重算；新場次入庫（recent 變動）也重算
    LaunchedEffect(weekly, recent) { vm.loadFocusStats(weekly) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = weekly,
                onClick = { weekly = true },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) { Text("本週") }
            SegmentedButton(
                selected = !weekly,
                onClick = { weekly = false },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) { Text("本月") }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatBlock("專注時數", fmtFocusDuration(stats?.totalMs ?: 0L), Modifier.weight(1f))
            StatBlock("場次", "${stats?.sessionCount ?: 0}", Modifier.weight(1f))
            StatBlock("完成任務", "${stats?.doneTasks ?: 0}", Modifier.weight(1f))
        }

        val byCategory = stats?.byCategory.orEmpty()
        if (byCategory.isNotEmpty()) {
            SectionTitle("各項專注了多久")
            val maxMs = byCategory.maxOf { it.second }.coerceAtLeast(1L)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                byCategory.forEach { (category, ms) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(category, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                fmtFocusDuration(ms),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LinearProgressIndicator(
                            progress = { ms.toFloat() / maxMs },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        SectionTitle("最近紀錄")
        if (recent.isEmpty()) {
            Text(
                "還沒有專注紀錄。從任務頁的時鐘圖示開始第一筆吧。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.forEach { FocusSessionRow(it) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1
            )
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FocusSessionRow(session: FocusSessionEntity) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    session.intent.ifBlank { "（未填主題）" },
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                val cash = session.cashYuan
                Text(
                    if (cash >= 0) "+$cash 元" else "$cash 元",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = if (cash < 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
            Text(
                buildString {
                    append(
                        Instant.ofEpochMilli(session.endedAt)
                            .atZone(ZoneId.systemDefault())
                            .format(SESSION_TIME_FMT)
                    )
                    append(" · ")
                    append(session.category)
                    append(" · ")
                    append(fmtFocusDuration(session.effectiveMs))
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
