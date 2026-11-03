package com.gino.mywallet.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gino.mywallet.domain.game.BjPhase
import com.gino.mywallet.domain.game.BjResult
import com.gino.mywallet.domain.game.Blackjack21State
import com.gino.mywallet.domain.game.HiLoGame
import com.gino.mywallet.domain.game.HiLoOutcome
import com.gino.mywallet.domain.game.HiLoPhase
import com.gino.mywallet.domain.game.HiLoPick
import com.gino.mywallet.domain.game.Match3AnimStep
import com.gino.mywallet.domain.game.Match3Motion
import com.gino.mywallet.domain.game.RouletteCellKind
import com.gino.mywallet.domain.game.RouletteGame
import com.gino.mywallet.domain.game.RoulettePhase
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
                    "獎金 +\$${com.gino.mywallet.domain.game.GamePrizes.BJ_JACKPOT_CENTS / 100}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (state.result == BjResult.WIN_BEAT) {
                Text(
                    "獎金 +\$${com.gino.mywallet.domain.game.GamePrizes.BJ_BEAT_CENTS / 100}",
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

@Composable
fun LottoPanel(vm: AppViewModel) {
    val lottery = vm.lotteryState.collectAsStateWithLifecycle().value
    val tickets = lottery?.lottoTickets ?: 0
    val picks = remember(lottery?.lottoPicksJson) {
        try {
            com.google.gson.Gson().fromJson(
                lottery?.lottoPicksJson ?: "[]",
                Array<String>::class.java
            )?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }
    val drawAt = lottery?.lottoDrawAt ?: 0L
    val lastResult = lottery?.lottoLastResult.orEmpty()
    var pickInput by remember { mutableStateOf("") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val target = if (drawAt > now) drawAt else com.gino.mywallet.domain.game.LottoGame.nextDrawAt(now)
    val remain = (target - now).coerceAtLeast(0L)
    val h = remain / 3_600_000
    val m = (remain % 3_600_000) / 60_000
    val s = (remain % 60_000) / 1000
    val countdown = "%02d:%02d:%02d".format(h, m, s)
    val drawLabel = com.gino.mywallet.domain.game.LottoGame.formatDrawLabel(target)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("下一期 $drawLabel", fontWeight = FontWeight.Bold)
                Text(countdown, fontSize = 28.sp, fontWeight = FontWeight.Black)
                if (picks.isNotEmpty()) {
                    Text(
                        picks.joinToString("  "),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    "券 $tickets",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (lastResult.isNotBlank()) {
                    Text(
                        lastResult,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        OutlinedTextField(
            value = pickInput,
            onValueChange = { pickInput = it.filter { c -> c.isDigit() }.take(3) },
            label = { Text("三碼") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                vm.buyLotto(pickInput)
                pickInput = ""
            },
            enabled = tickets > 0 && pickInput.length == 3,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("買注($tickets)")
        }
    }
}

@Composable
fun DailyWordPanel(vm: AppViewModel) {
    val state by vm.wordleState.collectAsStateWithLifecycle()
    val busy by vm.wordleBusy.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastPeriod by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        vm.ensureWordle()
        while (true) {
            now = System.currentTimeMillis()
            val period = com.gino.mywallet.domain.game.DailyWord.periodId(now)
            if (period != lastPeriod) {
                lastPeriod = period
                draft = ""
                vm.ensureWordle()
            }
            delay(1000)
        }
    }

    LaunchedEffect(state.phase, state.periodId) {
        if (state.phase == com.gino.mywallet.domain.game.DailyWordPhase.PLAYING) {
            delay(100)
            runCatching { focusRequester.requestFocus() }
        }
    }

    val correct = Color(0xFF6AAA64)
    val present = Color(0xFFC9B458)
    val absent = Color(0xFF787C7E)
    val currentRow = state.guesses.size

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = state.phase == com.gino.mywallet.domain.game.DailyWordPhase.PLAYING) {
                runCatching { focusRequester.requestFocus() }
            },
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.phase == com.gino.mywallet.domain.game.DailyWordPhase.PLAYING) {
            BasicTextField(
                value = draft,
                onValueChange = { raw ->
                    val next = raw.uppercase().filter { it in 'A'..'Z' }.take(5)
                    draft = next
                    if (next.length == 5 && !busy) {
                        if (com.gino.mywallet.domain.game.DailyWord.isValidGuess(next)) {
                            vm.submitWordle(next)
                            draft = ""
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .alpha(0.01f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (draft.length == 5 && !busy) {
                            vm.submitWordle(draft)
                            draft = ""
                        }
                    }
                )
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(com.gino.mywallet.domain.game.DailyWord.MAX_GUESSES) { row ->
                val guess = state.guesses.getOrNull(row)
                val isDraftRow =
                    state.phase == com.gino.mywallet.domain.game.DailyWordPhase.PLAYING &&
                        row == currentRow
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    repeat(5) { col ->
                        val ch = when {
                            guess != null -> guess.word.getOrNull(col)?.toString() ?: ""
                            isDraftRow -> draft.getOrNull(col)?.toString() ?: ""
                            else -> ""
                        }
                        val mark = guess?.marks?.getOrNull(col)
                        val bg = when (mark) {
                            com.gino.mywallet.domain.game.LetterMark.CORRECT -> correct
                            com.gino.mywallet.domain.game.LetterMark.PRESENT -> present
                            com.gino.mywallet.domain.game.LetterMark.ABSENT -> absent
                            null -> MaterialTheme.colorScheme.surfaceVariant
                        }
                        val fg = if (mark != null) Color.White else MaterialTheme.colorScheme.onSurface
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(bg)
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(ch, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = fg)
                        }
                    }
                }
            }
        }

        when (state.phase) {
            com.gino.mywallet.domain.game.DailyWordPhase.WON -> {
                val zh = state.answerZh.ifBlank { state.answer }
                Text(
                    "${state.answer}（$zh）",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Text(
                    "+$${state.prizeYuan} 券",
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            com.gino.mywallet.domain.game.DailyWordPhase.LOST -> {
                val zh = state.answerZh.ifBlank { state.answer }
                Text(
                    "${state.answer}（$zh）",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            else -> { }
        }
    }
}

@Composable
fun Match3Panel(vm: AppViewModel) {
    val state by vm.match3State.collectAsStateWithLifecycle()
    val busy by vm.match3Busy.collectAsStateWithLifecycle()
    val animSteps by vm.match3AnimSteps.collectAsStateWithLifecycle()
    val tickets = vm.lotteryState.collectAsStateWithLifecycle().value?.matchTickets ?: 0
    var selected by remember { mutableIntStateOf(-1) }
    var displayGrid by remember { mutableStateOf(state.grid) }
    var highlight by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var clearing by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var fallMotions by remember { mutableStateOf<List<com.gino.mywallet.domain.game.Match3Motion>>(emptyList()) }
    var fallProgress by remember { mutableFloatStateOf(1f) }

    val colors = listOf(
        Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047),
        Color(0xFFFDD835), Color(0xFF8E24AA), Color(0xFFFF7043)
    )
    val size = state.size.coerceAtLeast(1)
    val locked = busy || state.animating || animSteps.isNotEmpty()

    LaunchedEffect(state.grid, state.animating) {
        if (!state.animating && animSteps.isEmpty()) {
            displayGrid = state.grid
            highlight = emptySet()
            clearing = emptySet()
            fallMotions = emptyList()
            fallProgress = 1f
        }
    }

    LaunchedEffect(animSteps) {
        if (animSteps.isEmpty()) return@LaunchedEffect
        for (step in animSteps) {
            when (step) {
                is com.gino.mywallet.domain.game.Match3AnimStep.Swapped -> {
                    displayGrid = step.grid
                    highlight = emptySet()
                    clearing = emptySet()
                    fallMotions = emptyList()
                    fallProgress = 1f
                    delay(140)
                }
                is com.gino.mywallet.domain.game.Match3AnimStep.Highlight -> {
                    displayGrid = step.grid
                    highlight = step.cells
                    clearing = emptySet()
                    delay(180)
                }
                is com.gino.mywallet.domain.game.Match3AnimStep.Cleared -> {
                    displayGrid = step.grid
                    highlight = emptySet()
                    clearing = step.cells
                    delay(200)
                }
                is com.gino.mywallet.domain.game.Match3AnimStep.Fallen -> {
                    clearing = emptySet()
                    highlight = emptySet()
                    displayGrid = step.grid
                    fallMotions = step.motions
                    fallProgress = 0f
                    val anim = Animatable(0f)
                    anim.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) {
                        fallProgress = value
                    }
                    fallMotions = emptyList()
                    fallProgress = 1f
                    delay(60)
                }
            }
        }
        displayGrid = state.grid
        highlight = emptySet()
        clearing = emptySet()
        fallMotions = emptyList()
        fallProgress = 1f
        vm.match3AnimFinished()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "券 $tickets · 本局 ${formatCentsYuan(state.sessionCents)}",
            fontWeight = FontWeight.Medium
        )
        if (state.grid.isEmpty()) {
            Button(
                onClick = { vm.match3Deal() },
                enabled = !locked,
                modifier = Modifier.fillMaxWidth()
            ) { Text("開局") }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val gap = 4.dp
                val cell = (maxWidth - gap * (size - 1)) / size
                val boardWidth = cell * size + gap * (size - 1)
                Column(
                    modifier = Modifier
                        .width(boardWidth)
                        .align(Alignment.Center),
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    for (r in 0 until size) {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            for (c in 0 until size) {
                                val i = r * size + c
                                Match3Cell(
                                    index = i,
                                    cell = cell,
                                    colorIdx = displayGrid.getOrElse(i) { -1 },
                                    colors = colors,
                                    selected = selected == i,
                                    highlighted = i in highlight,
                                    clearing = i in clearing,
                                    fallMotions = fallMotions,
                                    fallProgress = fallProgress,
                                    enabled = !locked && tickets > 0,
                                    onClick = {
                                        when {
                                            selected < 0 -> selected = i
                                            selected == i -> selected = -1
                                            else -> {
                                                val a = selected
                                                selected = -1
                                                vm.match3Swap(a, i)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    selected = -1
                    vm.match3Deal()
                },
                enabled = !locked,
                modifier = Modifier.fillMaxWidth()
            ) { Text("重開盤面") }
        }
    }
}

@Composable
private fun Match3Cell(
    index: Int,
    cell: androidx.compose.ui.unit.Dp,
    colorIdx: Int,
    colors: List<Color>,
    selected: Boolean,
    highlighted: Boolean,
    clearing: Boolean,
    fallMotions: List<Match3Motion>,
    fallProgress: Float,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val motion = fallMotions.find { it.toIndex == index }
    val density = LocalDensity.current
    val fallOffsetPx = if (motion != null && fallProgress < 1f) {
        with(density) {
            ((motion.fromRow - motion.toRow) * (1f - fallProgress) * cell.toPx())
        }
    } else {
        0f
    }
    val scale = when {
        clearing -> 0.15f
        highlighted -> 1.12f
        else -> 1f
    }
    val alpha = when {
        colorIdx < 0 -> 0f
        clearing -> 0.25f
        else -> 1f
    }
    val bg = if (colorIdx in colors.indices) colors[colorIdx] else Color.Transparent
    Box(
        modifier = Modifier
            .size(cell)
            .graphicsLayer {
                translationY = fallOffsetPx
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(
                width = if (selected || highlighted) 2.dp else 1.dp,
                color = when {
                    selected -> MaterialTheme.colorScheme.onSurface
                    highlighted -> Color.White
                    else -> Color.Black.copy(alpha = 0.12f)
                },
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(enabled = enabled && colorIdx >= 0, onClick = onClick)
    )
}

private fun formatCentsYuan(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents)
    return "$sign$%.2f".format(abs / 100.0)
}

@Composable
fun RoulettePanel(vm: AppViewModel) {
    val state by vm.rouletteState.collectAsStateWithLifecycle()
    val busy by vm.rouletteBusy.collectAsStateWithLifecycle()
    val tickets = vm.lotteryState.collectAsStateWithLifecycle().value?.mathTickets ?: 0
    var highlight by remember { mutableIntStateOf(-1) }
    var settleFlash by remember { mutableStateOf(false) }

    val spinning = state.phase == RoulettePhase.SPINNING
    val canSpin = !busy && !spinning && tickets > 0 && !state.needsForcedCashOut &&
        (state.phase == RoulettePhase.READY || state.phase == RoulettePhase.LIVE)
    val canCash = !busy && !spinning &&
        state.phase == RoulettePhase.LIVE && state.potCents > 0

    LaunchedEffect(state.phase, state.spinTargetIndex, state.cells) {
        if (state.phase != RoulettePhase.SPINNING) {
            highlight = state.lastLandedIndex
            return@LaunchedEffect
        }
        val path = RouletteGame.remainingIndices(state)
        if (path.isEmpty()) {
            vm.rouletteAnimFinished()
            return@LaunchedEffect
        }
        val target = state.spinTargetIndex
        val laps = 2
        val seq = buildList {
            repeat(laps) { addAll(path) }
            for (i in path) {
                add(i)
                if (i == target) break
            }
            if (lastOrNull() != target) add(target)
        }
        val n = seq.size.coerceAtLeast(1)
        for ((step, idx) in seq.withIndex()) {
            highlight = idx
            val t = step.toFloat() / (n - 1).coerceAtLeast(1)
            val delayMs = (40 + t * t * 160).toLong()
            delay(delayMs)
        }
        highlight = target
        delay(120)
        vm.rouletteAnimFinished()
    }

    LaunchedEffect(state.phase, state.lastKind) {
        if (state.phase == RoulettePhase.READY &&
            state.lastKind == RouletteCellKind.SETTLE &&
            state.lastDeltaCents > 0
        ) {
            settleFlash = true
            delay(350)
            settleFlash = false
        }
    }

    val hint = when {
        spinning -> "旋轉中…"
        state.needsForcedCashOut -> "格子用完 · 請結算"
        state.lastKind == RouletteCellKind.SETTLE && state.phase == RoulettePhase.READY ->
            "撞結算 · +${"%.0f".format(state.lastDeltaCents / 100.0)}"
        state.lastKind != null && state.phase == RoulettePhase.LIVE -> {
            val kind = state.lastKind!!
            when (kind) {
                RouletteCellKind.TIMES2 -> "上手 ×2"
                RouletteCellKind.TIMES15 -> "上手 ×1.5"
                RouletteCellKind.PLUS4 -> "上手 +\$4"
                RouletteCellKind.PLUS3 -> "上手 +\$3"
                RouletteCellKind.SETTLE -> ""
            } + " · 底金 +\$${RouletteGame.BASE_INJECT_CENTS / 100}"
        }
        else -> "每次再來 +\$3 · 撞結算立刻入帳"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (settleFlash) Modifier.background(Color(0x3343A047))
                else Modifier
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("本輪累積", style = MaterialTheme.typography.labelLarge)
        Text(
            formatCentsYuan(state.potCents),
            fontSize = 40.sp,
            fontWeight = FontWeight.Black,
            color = when {
                settleFlash -> Color(0xFF43A047)
                state.lastKind == RouletteCellKind.TIMES2 ||
                    state.lastKind == RouletteCellKind.TIMES15 -> Color(0xFFF9A825)
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
        Text(hint, style = MaterialTheme.typography.bodyMedium)
        Text(
            "券 $tickets · 本輪已用 ${state.ticketsThisRound}",
            style = MaterialTheme.typography.bodySmall
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { vm.rouletteCashOut() },
                enabled = canCash,
                modifier = Modifier.weight(1f)
            ) { Text("結算") }
            Button(
                onClick = { vm.rouletteSpin() },
                enabled = canSpin,
                modifier = Modifier.weight(1f)
            ) { Text("再來") }
        }

        val cells = state.cells
        if (cells.size == RouletteGame.CELL_COUNT) {
            val rows = listOf(0 until 4, 4 until 7)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (range in rows) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (i in range) {
                            val cell = cells[i]
                            val active = highlight == i
                            val bg = when {
                                cell.consumed && !active -> Color.Gray.copy(alpha = 0.25f)
                                cell.kind == RouletteCellKind.SETTLE -> Color(0xFF6A1B9A)
                                cell.kind == RouletteCellKind.TIMES2 ||
                                    cell.kind == RouletteCellKind.TIMES15 -> Color(0xFFF9A825)
                                cell.kind == RouletteCellKind.PLUS4 -> Color(0xFF43A047)
                                else -> Color(0xFF1E88E5)
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .graphicsLayer {
                                        scaleX = if (active) 1.08f else 1f
                                        scaleY = if (active) 1.08f else 1f
                                        alpha = when {
                                            cell.consumed && !active -> 0.35f
                                            else -> 1f
                                        }
                                    }
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(bg)
                                    .border(
                                        width = if (active) 3.dp else 1.dp,
                                        color = if (active) Color.White
                                        else Color.Black.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .padding(vertical = 18.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    cell.kind.label(),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
