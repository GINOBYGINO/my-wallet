package com.gino.mywallet.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gino.mywallet.domain.FocusMode
import com.gino.mywallet.domain.FocusRules
import com.gino.mywallet.domain.ShortFocusDuration
import com.gino.mywallet.focus.FocusForegroundService
import com.gino.mywallet.focus.FocusPhase
import com.gino.mywallet.focus.FocusSessionController
import com.gino.mywallet.focus.UsageAccess
import com.gino.mywallet.ui.AppViewModel
import kotlin.math.cos
import kotlin.math.sin

private data class TopicCompletePrompt(val topic: String, val effectiveMs: Long)

@Composable
fun FocusScreen(
    vm: AppViewModel,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val state by FocusSessionController.state.collectAsStateWithLifecycle()
    var showUsageGate by remember { mutableStateOf(!UsageAccess.hasPermission(context)) }
    var confirmEnd by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmShortFail by remember { mutableStateOf(false) }
    var confirmConvert by remember { mutableStateOf(false) }
    var chosenMode by remember { mutableStateOf(FocusMode.Long) }
    var chosenShort by remember { mutableStateOf(ShortFocusDuration.M10) }
    var topicDraft by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    var topicPrompt by remember { mutableStateOf<TopicCompletePrompt?>(null) }

    fun settleAndAskTopic() {
        ending = true
        vm.endFocusSession { topic, ms ->
            topicPrompt = TopicCompletePrompt(topic = topic, effectiveMs = ms)
        }
    }

    LaunchedEffect(state.phase) {
        if (state.phase == FocusPhase.Failed && !ending) {
            settleAndAskTopic()
        }
    }

    val backEnabled = state.phase == FocusPhase.Running ||
        state.phase == FocusPhase.Paused ||
        state.phase == FocusPhase.ShortComplete
    BackHandler(enabled = backEnabled) {
        when {
            state.phase == FocusPhase.Paused -> onFinished() // 暫停中回主頁，不放棄場次
            state.phase == FocusPhase.ShortComplete -> Unit
            state.mode == FocusMode.Short && state.phase == FocusPhase.Running ->
                confirmShortFail = true
            else -> confirmDiscard = true
        }
    }

    LaunchedEffect(Unit) {
        showUsageGate = !UsageAccess.hasPermission(context)
    }

    if (showUsageGate) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "需要使用情況存取權",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "長期離開會暫停；短期離開會失敗扣 30 元。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { UsageAccess.openSettings(context) }) { Text("開啟設定") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                if (UsageAccess.hasPermission(context)) showUsageGate = false
            }) { Text("已授權，繼續") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onFinished) { Text("取消") }
        }
        return
    }

    val inActiveSession = state.phase == FocusPhase.Running ||
        state.phase == FocusPhase.Paused ||
        state.phase == FocusPhase.ShortComplete ||
        state.phase == FocusPhase.Failed ||
        state.phase == FocusPhase.Settled

    if (!inActiveSession && !started && !ending && topicPrompt == null) {
        FocusSetupForm(
            mode = chosenMode,
            shortDuration = chosenShort,
            topic = topicDraft,
            onModeChange = { chosenMode = it },
            onShortChange = { chosenShort = it },
            onTopicChange = { topicDraft = it },
            onCancel = onFinished,
            onStart = {
                FocusSessionController.setSessionMeta(topicDraft)
                FocusSessionController.prepareStart(
                    mode = chosenMode,
                    shortDuration = if (chosenMode == FocusMode.Short) chosenShort else null
                )
                FocusForegroundService.start(context)
                started = true
            }
        )
        return
    }

    val canEditTopic = state.phase == FocusPhase.Paused ||
        state.phase == FocusPhase.ShortComplete

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (state.mode == FocusMode.Short) "短期專注" else "長期專注",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Spacer(Modifier.height(8.dp))
                if (canEditTopic) {
                    OutlinedTextField(
                        value = state.topic,
                        onValueChange = { FocusSessionController.updateTopic(it) },
                        label = { Text("主題名稱") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (state.topic.isNotBlank()) {
                    Text(
                        state.topic,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                FocusRing(
                    litCount = state.litCount,
                    seed = state.ringSeed,
                    running = state.phase == FocusPhase.Running
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val cash = state.cashYuan
                    val cashColor = when {
                        cash < 0 -> MaterialTheme.colorScheme.error
                        cash > 0 -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        text = if (cash >= 0) "+$cash 元" else "$cash 元",
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = cashColor
                    )
                    if (state.mode == FocusMode.Short) {
                        Text(
                            if (state.phase == FocusPhase.ShortComplete) {
                                "可領 +${state.shortDuration?.rewardYuan ?: 0} 或轉長期"
                            } else {
                                "押金"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (state.ticketEvYuan > 0) {
                        Text(
                            "混券預算 ${state.ticketEvYuan} 元",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val timeText = if (state.mode == FocusMode.Short &&
                        state.phase == FocusPhase.Running
                    ) {
                        val remain = ((state.shortDuration?.targetMs ?: 0L) - state.displayMs)
                            .coerceAtLeast(0L)
                        FocusRules.formatCountdown(remain)
                    } else {
                        FocusRules.formatElapsed(state.displayMs)
                    }
                    Text(timeText, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    when {
                        state.phase == FocusPhase.Paused && state.leftAppPaused ->
                            Text("已離開，已暫停", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        state.phase == FocusPhase.Paused ->
                            Text("已暫停", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        state.phase == FocusPhase.ShortComplete ->
                            Text("倒數結束", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                        state.rewardCapped ->
                            Text("獎勵已滿", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                    }
                }
            }

            when (state.phase) {
                FocusPhase.ShortComplete -> {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { settleAndAskTopic() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("領取 +${state.shortDuration?.rewardYuan ?: 0} 元")
                        }
                        OutlinedButton(
                            onClick = { confirmConvert = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("轉長期")
                        }
                    }
                }
                else -> {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (state.mode == FocusMode.Long) {
                                OutlinedButton(
                                    onClick = {
                                        if (state.phase == FocusPhase.Running) {
                                            FocusForegroundService.pause(context)
                                        } else if (state.phase == FocusPhase.Paused) {
                                            FocusForegroundService.resume(context)
                                        }
                                    },
                                    enabled = state.phase == FocusPhase.Running ||
                                        state.phase == FocusPhase.Paused,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (state.phase == FocusPhase.Paused) "繼續" else "暫停")
                                }
                                Button(
                                    onClick = {
                                        if (state.cashYuan < 0) confirmEnd = true
                                        else settleAndAskTopic()
                                    },
                                    enabled = state.phase == FocusPhase.Running ||
                                        state.phase == FocusPhase.Paused,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    )
                                ) {
                                    Text("結束")
                                }
                            } else {
                                Button(
                                    onClick = { confirmShortFail = true },
                                    enabled = state.phase == FocusPhase.Running,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    )
                                ) {
                                    Text("放棄（−30）")
                                }
                            }
                        }
                        if (state.mode == FocusMode.Long && state.phase == FocusPhase.Paused) {
                            Button(
                                onClick = onFinished,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("回主頁")
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("結束？") },
            text = { Text("目前 ${state.cashYuan} 元，將加入待轉移。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    settleAndAskTopic()
                }) { Text("結束") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnd = false }) { Text("取消") }
            }
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("離開？") },
            text = { Text("將取消本場，不入帳。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    FocusForegroundService.stop(context)
                    FocusSessionController.resetIdle()
                    onFinished()
                }) { Text("離開") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("取消") }
            }
        )
    }

    if (confirmShortFail) {
        AlertDialog(
            onDismissRequest = { confirmShortFail = false },
            title = { Text("放棄？") },
            text = { Text("扣除 30 元待轉移。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmShortFail = false
                    FocusSessionController.failShort(leftApp = false)
                }) { Text("確定") }
            },
            dismissButton = {
                TextButton(onClick = { confirmShortFail = false }) { Text("取消") }
            }
        )
    }

    if (confirmConvert) {
        val longCash = remember(state.displayMs) {
            val steps = FocusRules.ensureCashSteps(
                emptyList(),
                FocusRules.cashStepCount(state.displayMs)
            )
            FocusRules.longCashYuan(steps)
        }
        AlertDialog(
            onDismissRequest = { confirmConvert = false },
            title = { Text("轉長期？") },
            text = {
                Text(
                    "不領短期獎。時間帶入長期（目前 ${if (longCash >= 0) "+$longCash" else "$longCash"}）。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmConvert = false
                    FocusSessionController.convertShortToLong()
                }) { Text("轉長期") }
            },
            dismissButton = {
                TextButton(onClick = { confirmConvert = false }) { Text("取消") }
            }
        )
    }

    topicPrompt?.let { prompt ->
        val label = prompt.topic.ifBlank { "這次專注" }
        AlertDialog(
            onDismissRequest = {
                topicPrompt = null
                onFinished()
            },
            title = { Text("是否完成？") },
            text = { Text("「$label」完成了嗎？完成會記入今日日曆。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.recordFocusTopicOnCalendar(prompt.topic, prompt.effectiveMs)
                    topicPrompt = null
                    onFinished()
                }) { Text("已完成") }
            },
            dismissButton = {
                TextButton(onClick = {
                    topicPrompt = null
                    onFinished()
                }) { Text("尚未") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FocusSetupForm(
    mode: FocusMode,
    shortDuration: ShortFocusDuration,
    topic: String,
    onModeChange: (FocusMode) -> Unit,
    onShortChange: (ShortFocusDuration) -> Unit,
    onTopicChange: (String) -> Unit,
    onCancel: () -> Unit,
    onStart: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "專注",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = mode == FocusMode.Long,
                onClick = { onModeChange(FocusMode.Long) },
                label = { Text("長期") },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = mode == FocusMode.Short,
                onClick = { onModeChange(FocusMode.Short) },
                label = { Text("短期") },
                modifier = Modifier.weight(1f)
            )
        }
        if (mode == FocusMode.Short) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ShortFocusDuration.entries.forEach { d ->
                    FilterChip(
                        selected = shortDuration == d,
                        onClick = { onShortChange(d) },
                        label = { Text("${d.minutes}分") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        OutlinedTextField(
            value = topic,
            onValueChange = onTopicChange,
            label = { Text("主題名稱") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("開始")
        }
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("取消")
        }
    }
}

@Composable
private fun FocusRing(litCount: Int, seed: Int, running: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
    val litColor = remember(seed) {
        Color(
            red = 0.70f + 0.1f * ((seed % 50) / 50f),
            green = 0.78f,
            blue = 0.45f,
            alpha = 1f
        )
    }
    val count = litCount.coerceIn(0, FocusRules.RING_DOTS)

    Canvas(Modifier.size(280.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension * 0.42f
        val dotR = size.minDimension * 0.035f
        for (i in 0 until FocusRules.RING_DOTS) {
            val angle = (Math.PI * 2 * i / FocusRules.RING_DOTS.toDouble()) - Math.PI / 2
            val x = cx + (radius * cos(angle)).toFloat()
            val y = cy + (radius * sin(angle)).toFloat()
            val lit = i < count
            val color = when {
                lit && running -> accent
                lit -> litColor.copy(alpha = 0.85f)
                else -> dim
            }
            val r = if (lit) dotR * 1.2f else dotR
            drawCircle(color = color, radius = r, center = Offset(x, y))
        }
    }
}
