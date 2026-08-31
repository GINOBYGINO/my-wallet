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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gino.mywallet.domain.FocusRules
import com.gino.mywallet.focus.FocusForegroundService
import com.gino.mywallet.focus.FocusPhase
import com.gino.mywallet.focus.FocusSessionController
import com.gino.mywallet.focus.UsageAccess
import com.gino.mywallet.ui.AppViewModel
import kotlin.math.cos
import kotlin.math.sin

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
    var started by remember { mutableStateOf(false) }

    BackHandler(enabled = state.phase == FocusPhase.Running || state.phase == FocusPhase.Paused) {
        confirmDiscard = true
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
                "需要「使用情況存取權」",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "專注計時會偵測前景 App；離開本 App 時會自動暫停。請在系統設定中允許「我的錢包」。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { UsageAccess.openSettings(context) }) {
                Text("開啟設定")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                if (UsageAccess.hasPermission(context)) {
                    showUsageGate = false
                }
            }) {
                Text("我已授權，繼續")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onFinished) { Text("取消") }
        }
        return
    }

    LaunchedEffect(showUsageGate) {
        if (!showUsageGate && !started) {
            FocusForegroundService.start(context)
            started = true
        }
    }

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
            Text(
                "專注",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )

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
                    Text(
                        if (state.ticketEvYuan > 0) {
                            "混券預算 ${state.ticketEvYuan} 元＋保證券"
                        } else {
                            "未滿 30 分尚無混券"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        FocusRules.formatElapsed(state.displayMs),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "亮 ${state.litCount}/${FocusRules.RING_DOTS}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    when {
                        state.phase == FocusPhase.Paused && state.leftAppPaused ->
                            Text("已離開 App，計時暫停", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        state.phase == FocusPhase.Paused ->
                            Text("已暫停", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        state.rewardCapped ->
                            Text("獎勵已達上限，可繼續計時", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (state.phase == FocusPhase.Running) {
                            FocusForegroundService.pause(context)
                        } else if (state.phase == FocusPhase.Paused) {
                            FocusForegroundService.resume(context)
                        }
                    },
                    enabled = state.phase == FocusPhase.Running || state.phase == FocusPhase.Paused,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (state.phase == FocusPhase.Paused) "繼續" else "暫停")
                }
                Button(
                    onClick = {
                        if (state.cashYuan < 0) confirmEnd = true
                        else vm.endFocusSession(onFinished)
                    },
                    enabled = state.phase == FocusPhase.Running || state.phase == FocusPhase.Paused,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("結束")
                }
            }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("確定結束？") },
            text = {
                Text("目前累積 ${state.cashYuan} 元，結束後會從伙雜費扣款（或入帳）。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    vm.endFocusSession(onFinished)
                }) { Text("結束並結算") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnd = false }) { Text("取消") }
            }
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("離開專注？") },
            text = { Text("返回將取消本次專注，不會入帳。若要結算請按「結束」。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    FocusForegroundService.stop(context)
                    FocusSessionController.resetIdle()
                    onFinished()
                }) { Text("放棄並離開") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("繼續專注") }
            }
        )
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
