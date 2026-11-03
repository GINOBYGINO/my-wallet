package com.gino.mywallet.ui.screens



import androidx.compose.animation.core.Animatable

import androidx.compose.animation.core.FastOutSlowInEasing

import androidx.compose.animation.core.tween

import androidx.compose.foundation.BorderStroke

import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Arrangement

import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.Row

import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.Button

import androidx.compose.material3.ButtonDefaults

import androidx.compose.material3.Card

import androidx.compose.material3.CardDefaults

import androidx.compose.material3.LinearProgressIndicator

import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Text

import androidx.compose.runtime.Composable

import androidx.compose.runtime.LaunchedEffect

import androidx.compose.runtime.remember

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.alpha

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.unit.dp

import androidx.compose.ui.unit.sp

import com.gino.mywallet.data.entity.PassAdvanceResult

import com.gino.mywallet.domain.EconomyEngine

import com.gino.mywallet.data.entity.PassSeasonEntity

import com.gino.mywallet.data.entity.PassTierEntity

import java.time.Instant

import java.time.LocalDate

import java.time.ZoneId

import java.time.temporal.ChronoUnit



private val PASS_MILESTONE_TIERS = setOf(10, 20, 30, 40, 50, 60)



private enum class PassRewardKind { LIVING, TICKET, RELIEF, COMBO, REST, FINALE }



private fun passRewardKind(label: String): PassRewardKind = when {

    label.contains("休息") -> PassRewardKind.REST

    label.contains("通關") || label.contains("終局") -> PassRewardKind.FINALE

    label.contains("公益") || label.contains("基準") -> PassRewardKind.RELIEF

    label.contains("券") && (label.contains("🍔") || label.contains("💰") || label.contains("$") || label.contains("＋")) -> PassRewardKind.COMBO

    label.contains("券") -> PassRewardKind.TICKET

    label.contains("🍔") || label.contains("💰") || label.contains("$") -> PassRewardKind.LIVING

    else -> PassRewardKind.LIVING

}



private fun passRewardIcon(label: String): String = when (passRewardKind(label)) {

    PassRewardKind.REST -> "—"

    PassRewardKind.FINALE -> "👑"

    PassRewardKind.RELIEF -> "📉"

    PassRewardKind.COMBO -> "🎁"

    PassRewardKind.TICKET -> "🎫"

    PassRewardKind.LIVING -> "💰"

}



private fun passDaysLeft(season: PassSeasonEntity, today: LocalDate = LocalDate.now()): Int {

    val endDate = Instant.ofEpochMilli(season.endsAt).atZone(ZoneId.systemDefault()).toLocalDate()

    return ChronoUnit.DAYS.between(today, endDate).coerceAtLeast(0).toInt() + 1

}



private fun tierHasClaimableReward(tier: PassTierEntity): Boolean {

    val json = tier.rewardJson.trim()

    return json.isNotEmpty() && json != "[]"

}



fun passPendingClaimCount(season: PassSeasonEntity, tiers: List<PassTierEntity>): Int =

    tiers.count { it.tierIndex <= season.progress && !it.claimed && tierHasClaimableReward(it) }



@Composable

fun PassMiniBar(

    season: PassSeasonEntity?,

    tiers: List<PassTierEntity>,

    onOpenFull: () -> Unit

) {

    if (season == null) return

    val pending = remember(season.progress, tiers) { passPendingClaimCount(season, tiers) }

    Card(

        modifier = Modifier

            .fillMaxWidth()

            .clickable(onClick = onOpenFull),

        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))

    ) {

        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {

            Row(

                Modifier.fillMaxWidth(),

                horizontalArrangement = Arrangement.SpaceBetween,

                verticalAlignment = Alignment.CenterVertically

            ) {

                Text("通行證", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {

                    if (pending > 0) {

                        Text(

                            "待領 $pending",

                            fontWeight = FontWeight.Bold,

                            color = MaterialTheme.colorScheme.tertiary,

                            fontSize = 12.sp

                        )

                    }

                    Text(

                        "${season.progress}/${EconomyEngine.PASS_LENGTH}",

                        fontWeight = FontWeight.Bold,

                        color = MaterialTheme.colorScheme.primary,

                        fontSize = 13.sp

                    )

                }

            }

            Spacer(Modifier.height(6.dp))

            LinearProgressIndicator(

                progress = { (season.progress.toFloat() / EconomyEngine.PASS_LENGTH).coerceIn(0f, 1f) },

                modifier = Modifier.fillMaxWidth().height(4.dp)

            )

        }

    }

}



@Composable
fun PassFullScreen(
    season: PassSeasonEntity?,
    tiers: List<PassTierEntity>,
    onClaim: (Long) -> Unit = {},
    claimFlash: PassAdvanceResult? = null,
    onDismissClaimFlash: () -> Unit = {},
    milestone: com.gino.mywallet.domain.MilestoneSnapshot? = null,
    onClaimTaskMilestones: () -> Unit = {},
    onClaimFocusMilestones: () -> Unit = {},
    onAppear: () -> Unit = {}
) {
    LaunchedEffect(Unit) { onAppear() }

    if (season == null) {
        Text("尚無進行中賽季", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column {
            PassHeroRow(season)
            Spacer(Modifier.height(12.dp))
            PassMilestoneCard(
                milestone = milestone,
                onClaimTask = onClaimTaskMilestones,
                onClaimFocus = onClaimFocusMilestones
            )
            Spacer(Modifier.height(12.dp))
            if (season.progress >= EconomyEngine.PASS_LENGTH) {
                Text("本月通關！", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            } else if (season.progress >= EconomyEngine.PASS_SPRINT_FROM) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Text(
                        "終局衝刺 · 距通關還有 ${(EconomyEngine.PASS_LENGTH - season.progress).coerceAtLeast(0)} 格",
                        modifier = Modifier.padding(12.dp),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            val sorted = tiers.sortedBy { it.tierIndex }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sorted.forEach { tier ->
                    PassTierRow(
                        tier = tier,
                        progress = season.progress,
                        onClaim = onClaim
                    )
                }
            }
        }
        claimFlash?.let { flash ->
            PassClaimFlash(result = flash, onDismiss = onDismissClaimFlash)
        }
    }
}

@Composable
private fun PassMilestoneCard(
    milestone: com.gino.mywallet.domain.MilestoneSnapshot?,
    onClaimTask: () -> Unit,
    onClaimFocus: () -> Unit
) {
    val m = milestone
    val taskProg = (m?.taskProgress ?: 0).toFloat() / com.gino.mywallet.domain.MilestoneTrack.TASK_STEP
    val focusProg = ((m?.focusProgressMs ?: 0L).toFloat() / com.gino.mywallet.domain.MilestoneTrack.FOCUS_STEP_MS)
    val focusH = ((m?.focusProgressMs ?: 0L) / 3_600_000.0)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("累積領獎", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                "任務 ${m?.taskProgress ?: 0}/${com.gino.mywallet.domain.MilestoneTrack.TASK_STEP}" +
                    if ((m?.claimableTasks ?: 0) > 0) " · ×${m?.claimableTasks}" else "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = { taskProg.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp)
            )
            Button(
                onClick = onClaimTask,
                enabled = (m?.claimableTasks ?: 0) > 0,
                modifier = Modifier.fillMaxWidth()
            ) { Text("領任務") }
            Text(
                "專注 ${"%.1f".format(focusH)}/24h" +
                    if ((m?.claimableFocus ?: 0) > 0) " · ×${m?.claimableFocus}" else "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = { focusProg.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp)
            )
            Button(
                onClick = onClaimFocus,
                enabled = (m?.claimableFocus ?: 0) > 0,
                modifier = Modifier.fillMaxWidth()
            ) { Text("領專注") }
        }
    }
}



@Composable

private fun PassClaimFlash(result: PassAdvanceResult, onDismiss: () -> Unit) {

    val scale = remember { Animatable(0.6f) }

    val alpha = remember { Animatable(1f) }

    LaunchedEffect(result) {

        scale.snapTo(0.6f)

        alpha.snapTo(1f)

        scale.animateTo(1f, tween(320, easing = FastOutSlowInEasing))

        kotlinx.coroutines.delay(1200)

        alpha.animateTo(0f, tween(400))

        onDismiss()

    }

    Box(

        modifier = Modifier

            .fillMaxWidth()

            .padding(top = 8.dp)

            .graphicsLayer {

                scaleX = scale.value

                scaleY = scale.value

                this.alpha = alpha.value

            },

        contentAlignment = Alignment.TopCenter

    ) {

        Card(

            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),

            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)

        ) {

            Column(

                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),

                horizontalAlignment = Alignment.CenterHorizontally

            ) {

                Text("已領取", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))

                Text(

                    buildString {
                        append("第 ${result.tierIndex} 格 · ${result.label}")
                        if (result.prizeHint.isNotBlank()) append(result.prizeHint)
                    },

                    fontWeight = FontWeight.Bold,

                    fontSize = 16.sp,

                    color = MaterialTheme.colorScheme.onPrimaryContainer

                )

            }

        }

    }

}



@Composable

private fun PassHeroRow(season: PassSeasonEntity) {

    val daysLeft = remember(season.endsAt) { passDaysLeft(season) }

    val progressPercent = ((season.progress.toFloat() / EconomyEngine.PASS_LENGTH) * 100).toInt()

    Card(

        modifier = Modifier.fillMaxWidth(),

        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)

    ) {

        Row(

            modifier = Modifier

                .fillMaxWidth()

                .padding(horizontal = 16.dp, vertical = 16.dp),

            horizontalArrangement = Arrangement.SpaceBetween,

            verticalAlignment = Alignment.CenterVertically

        ) {

            Column {

                Text(

                    season.title,

                    style = MaterialTheme.typography.titleMedium,

                    fontWeight = FontWeight.Bold

                )

                Text(

                    "剩 $daysLeft 天",

                    fontSize = 12.sp,

                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)

                )

            }

            Column(horizontalAlignment = Alignment.End) {

                Text(

                    "${season.progress}",

                    fontSize = 36.sp,

                    fontWeight = FontWeight.Black,

                    color = MaterialTheme.colorScheme.primary

                )

                Text("/ ${EconomyEngine.PASS_LENGTH} · $progressPercent%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)

            }

        }

    }

}



@Composable

private fun PassTierRow(tier: PassTierEntity, progress: Int, onClaim: (Long) -> Unit) {

    val isNext = tier.tierIndex == progress + 1

    val isClaimed = tier.claimed

    val isUnlocked = tier.tierIndex <= progress

    val isClaimable = isUnlocked && !isClaimed && tierHasClaimableReward(tier)

    val isMilestone = tier.tierIndex in PASS_MILESTONE_TIERS

    val isFinale = tier.tierIndex >= EconomyEngine.PASS_SPRINT_FROM



    val borderColor = when {

        isClaimable -> MaterialTheme.colorScheme.tertiary

        isNext -> MaterialTheme.colorScheme.primary

        isClaimed -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)

        isMilestone -> MaterialTheme.colorScheme.tertiary

        else -> MaterialTheme.colorScheme.outlineVariant

    }

    val borderW = when {

        isClaimable || isNext -> 2.dp

        isMilestone -> 1.5.dp

        else -> 1.dp

    }

    val containerColor = when {

        isClaimable -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f)

        isNext -> MaterialTheme.colorScheme.primaryContainer

        isClaimed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

        isMilestone -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)

        isFinale -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)

        else -> MaterialTheme.colorScheme.surface

    }



    Card(

        modifier = Modifier

            .fillMaxWidth()

            .height(56.dp)

            .then(if (isClaimed) Modifier.alpha(0.55f) else Modifier),

        colors = CardDefaults.cardColors(containerColor = containerColor),

        border = BorderStroke(borderW, borderColor),

        shape = RoundedCornerShape(10.dp)

    ) {

        Row(

            modifier = Modifier

                .fillMaxWidth()

                .padding(horizontal = 14.dp),

            verticalAlignment = Alignment.CenterVertically,

            horizontalArrangement = Arrangement.spacedBy(12.dp)

        ) {

            Text(

                "${tier.tierIndex}",

                fontWeight = FontWeight.SemiBold,

                fontSize = 14.sp,

                color = when {

                    isMilestone -> MaterialTheme.colorScheme.tertiary

                    else -> MaterialTheme.colorScheme.onSurfaceVariant

                }

            )

            Text(

                tier.label,

                modifier = Modifier.weight(1f),

                fontSize = 14.sp,

                fontWeight = if (isNext || isClaimable) FontWeight.Bold else FontWeight.Normal,

                maxLines = 1,

                overflow = TextOverflow.Ellipsis

            )

            when {

                isClaimable -> Button(

                    onClick = { onClaim(tier.id) },

                    modifier = Modifier.height(32.dp),

                    contentPadding = ButtonDefaults.ContentPadding,

                    shape = RoundedCornerShape(8.dp)

                ) {

                    Text("領取", fontSize = 12.sp)

                }

                isClaimed -> Text("已領", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                isNext -> Text("下一格", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

            }

        }

    }

}

