package com.gino.mywallet.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gino.mywallet.domain.game.BjPhase
import com.gino.mywallet.domain.game.BjResult
import com.gino.mywallet.domain.game.Blackjack21State
import com.gino.mywallet.domain.game.HiLoGame
import com.gino.mywallet.domain.game.HiLoOutcome
import com.gino.mywallet.domain.game.HiLoPhase
import com.gino.mywallet.domain.game.HiLoPick
import com.gino.mywallet.domain.game.TripleDrawPhase
import com.gino.mywallet.ui.AppViewModel
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
fun Blackjack21Panel(vm: AppViewModel) {
    val state by vm.bjState.collectAsStateWithLifecycle()
    val busy by vm.bjBusy.collectAsStateWithLifecycle()
    val tickets = vm.lotteryState.collectAsStateWithLifecycle().value?.bjTickets ?: 0
    val playing = state.phase == BjPhase.PLAYING

    val displayScore = remember { Animatable(0f) }
    val bannerScale = remember { Animatable(0.85f) }
    val bannerAlpha = remember { Animatable(0f) }
    val pulse = remember { Animatable(1f) }

    val dealerZone = Color(0xFFE65100)
    val jackpotTint = Color(0xFFF9A825)
    val bustRed = MaterialTheme.colorScheme.error
    val youColor = MaterialTheme.colorScheme.primary
    val potYuan = state.potCents / 100

    LaunchedEffect(state.score, state.lastDelta, state.phase) {
        when (state.phase) {
            BjPhase.IDLE -> displayScore.snapTo(0f)
            BjPhase.PLAYING, BjPhase.RESULT -> {
                val delta = state.lastDelta
                val targetVisual = state.score.coerceAtMost(21).toFloat()
                val needsClimb = delta != null && delta > 0 &&
                    displayScore.value < targetVisual - 0.05f
                if (needsClimb) {
                    val from = (state.score - delta!!).coerceAtLeast(0).toFloat()
                    displayScore.snapTo(from.coerceAtMost(21f))
                    displayScore.animateTo(
                        targetVisual,
                        animationSpec = tween(450 + 80 * delta, easing = FastOutSlowInEasing)
                    )
                    pulse.snapTo(1.12f)
                    pulse.animateTo(1f, tween(220))
                } else {
                    displayScore.snapTo(targetVisual)
                }
            }
        }
    }

    LaunchedEffect(state.phase, state.result) {
        if (state.phase == BjPhase.RESULT && state.result != null) {
            bannerScale.snapTo(0.85f)
            bannerAlpha.snapTo(1f)
            bannerScale.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
        } else {
            bannerAlpha.snapTo(0f)
        }
    }

    val shown = displayScore.value.roundToInt()
    val delta = state.lastDelta
    val canAdvance = !busy && tickets > 0 && (playing || state.phase == BjPhase.RESULT || state.phase == BjPhase.IDLE)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFFFF3E0))
                    .border(2.dp, dealerZone, RoundedCornerShape(16.dp))
                    .padding(vertical = 20.dp, horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("莊家", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = dealerZone)
                Text(
                    text = if (state.phase == BjPhase.IDLE) "—" else "${state.dealer}",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Black,
                    color = dealerZone
                )
                Text("18～20", fontSize = 11.sp, color = dealerZone.copy(alpha = 0.7f))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .border(2.dp, youColor, RoundedCornerShape(16.dp))
                    .graphicsLayer {
                        scaleX = pulse.value
                        scaleY = pulse.value
                    }
                    .padding(vertical = 20.dp, horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("你", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = youColor)
                Text(
                    text = when {
                        state.phase == BjPhase.IDLE -> "0"
                        state.result == BjResult.BUST && state.score > 21 -> "${state.score}"
                        else -> "$shown"
                    },
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Black,
                    color = when (state.result) {
                        BjResult.BUST -> bustRed
                        BjResult.WIN_21 -> jackpotTint
                        BjResult.WIN_BEAT -> youColor
                        else -> MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
                Text(
                    text = when {
                        playing && delta != null && delta > 0 -> "+$delta · \$$potYuan"
                        state.result == BjResult.WIN_21 ->
                            "大獎 \$${com.gino.mywallet.domain.game.GamePrizes.BJ_JACKPOT_CENTS / 100}"
                        state.score > state.dealer && playing -> "已超過 · \$$potYuan"
                        potYuan > 0 -> "\$$potYuan"
                        else -> "目標 21"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }

        if (state.phase == BjPhase.RESULT && state.result != null) {
            BjResultBanner(
                state = state,
                scale = bannerScale.value,
                alpha = bannerAlpha.value
            )
        }

        Button(
            onClick = { vm.bjAdvance() },
            enabled = canAdvance,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    busy -> "推進中…"
                    state.phase == BjPhase.RESULT -> "再來($tickets)"
                    else -> "推進($tickets)"
                }
            )
        }
        OutlinedButton(
            onClick = { vm.bjStand() },
            enabled = !busy && playing && state.score > 0,
            modifier = Modifier.fillMaxWidth()
        ) { Text(state.standButtonLabel()) }
    }
}

@Composable
private fun BjResultBanner(
    state: Blackjack21State,
    scale: Float,
    alpha: Float
) {
    val win = state.result == BjResult.WIN_21 || state.result == BjResult.WIN_BEAT
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .background(
                if (win) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer,
                RoundedCornerShape(12.dp)
            )
            .padding(vertical = 14.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                resultLabel(state),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                color = if (win) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onErrorContainer
            )
            if (state.result == BjResult.WIN_21) {
                Text(
                    "伙雜費 +\$${com.gino.mywallet.domain.game.GamePrizes.BJ_JACKPOT_CENTS / 100}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (state.result == BjResult.WIN_BEAT) {
                Text(
                    "伙雜費 +\$${com.gino.mywallet.domain.game.GamePrizes.BJ_BEAT_CENTS / 100}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (state.result == BjResult.LOSE && state.potCents > 0) {
                Text(
                    "\$${state.potCents / 100}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun resultLabel(state: Blackjack21State): String = when (state.result) {
    BjResult.WIN_21 -> "二十一點！"
    BjResult.WIN_BEAT -> "擊敗莊家！"
    BjResult.BUST -> "爆牌 ${state.score} · 沒收"
    BjResult.LOSE -> "沒中"
    null -> "結束"
}

@Composable
fun HiLoPanel(vm: AppViewModel) {
    val state by vm.hiLoState.collectAsStateWithLifecycle()
    val busy by vm.hiLoBusy.collectAsStateWithLifecycle()
    val tickets = vm.lotteryState.collectAsStateWithLifecycle().value?.scratchTickets ?: 0
    var rollDisplay by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.phase, state.reveal) {
        val revealed = state.reveal
        when {
            state.phase == HiLoPhase.REVEALING && revealed != null -> {
                repeat(18) {
                    rollDisplay = Random.nextInt(HiLoGame.MIN, HiLoGame.MAX + 1)
                    delay(70)
                }
                rollDisplay = revealed
            }
            state.phase == HiLoPhase.RESULT && revealed != null -> {
                rollDisplay = revealed
            }
            state.phase == HiLoPhase.READY -> {
                rollDisplay = 0
            }
        }
    }

    val highPay =
        if (state.base in HiLoGame.MIN..HiLoGame.MAX) HiLoGame.payoutYuanIfHigh(state.base) else null
    val lowPay =
        if (state.base in HiLoGame.MIN..HiLoGame.MAX) HiLoGame.payoutYuanIfLow(state.base) else null
    val ready = state.phase == HiLoPhase.READY && !busy
    val revealing = state.phase == HiLoPhase.REVEALING

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("基準", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (state.base > 0) "${state.base}" else "—",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (revealing) "開獎中" else "開出",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = when {
                        revealing -> "$rollDisplay"
                        state.reveal != null -> "${state.reveal}"
                        else -> "?"
                    },
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        if (state.phase == HiLoPhase.RESULT && state.outcome != null) {
            val win = state.outcome == HiLoOutcome.WIN || state.outcome == HiLoOutcome.TIE
            Text(
                text = when (state.outcome) {
                    HiLoOutcome.WIN -> "猜對！+\$${state.payoutCents / 100}"
                    HiLoOutcome.TIE -> "相同！+\$${HiLoGame.TIE_PRIZE_YUAN}"
                    HiLoOutcome.LOSE -> "沒中"
                    null -> ""
                },
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = if (win) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { vm.hiLoGuess(HiLoPick.LOW) },
                enabled = ready && lowPay != null && tickets > 0,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (lowPay != null) "更低 · \$$lowPay" else "更低",
                    textAlign = TextAlign.Center
                )
            }
            Button(
                onClick = { vm.hiLoGuess(HiLoPick.HIGH) },
                enabled = ready && highPay != null && tickets > 0,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (highPay != null) "更高 · \$$highPay" else "更高",
                    textAlign = TextAlign.Center
                )
            }
        }

        if (state.phase == HiLoPhase.RESULT || state.phase == HiLoPhase.IDLE) {
            OutlinedButton(
                onClick = { vm.hiLoNewRound() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("再來($tickets)") }
        } else {
            Text(
                "剩餘 $tickets 次",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun TripleDrawPanel(vm: AppViewModel) {
    val state by vm.tripleDrawState.collectAsStateWithLifecycle()
    val busy by vm.tripleDrawBusy.collectAsStateWithLifecycle()
    val tickets = vm.lotteryState.collectAsStateWithLifecycle().value?.drawTickets ?: 0
    val playing = state.phase == TripleDrawPhase.PLAYING
    val canRedraw = playing && state.discard.isNotEmpty() && tickets > 0 && !busy

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "點選要換的牌，消耗 🎴 可一次換掉任意張",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (state.cards.isEmpty()) {
                repeat(5) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(vertical = 28.dp)
                    )
                }
            } else {
                state.cards.forEachIndexed { index, card ->
                    val marked = index in state.discard
                    val red = card.isRed
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (marked) MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                width = if (marked) 2.dp else 1.dp,
                                color = if (marked) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable(enabled = playing && !busy) { vm.tripleDrawToggle(index) }
                            .padding(vertical = 12.dp, horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            card.suitLabel,
                            fontSize = 18.sp,
                            color = if (red) Color(0xFFC62828) else Color.Unspecified
                        )
                        Text(
                            card.rankLabel,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = if (red) Color(0xFFC62828) else Color.Unspecified
                        )
                        if (marked) {
                            Text("換", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        Text(
            text = when (state.phase) {
                TripleDrawPhase.IDLE -> "尚未發牌"
                TripleDrawPhase.PLAYING -> "${state.hand.label} · 現值 \$${state.payoutCents / 100}"
                TripleDrawPhase.SETTLED -> "${state.hand.label} · \$${state.payoutCents / 100}"
            },
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = if (state.payoutCents > 0) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )

        when (state.phase) {
            TripleDrawPhase.IDLE, TripleDrawPhase.SETTLED -> {
                Button(
                    onClick = { vm.tripleDrawDeal() },
                    enabled = !busy && tickets > 0,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("發牌($tickets)") }
            }
            TripleDrawPhase.PLAYING -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { vm.tripleDrawRedraw() },
                        enabled = canRedraw,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("換牌($tickets)")
                    }
                    Button(
                        onClick = { vm.tripleDrawSettle() },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(state.settleButtonLabel())
                    }
                }
            }
        }
    }
}
