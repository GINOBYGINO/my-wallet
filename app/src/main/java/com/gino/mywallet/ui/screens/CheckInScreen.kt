package com.gino.mywallet.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gino.mywallet.domain.EconomyEngine
import com.gino.mywallet.ui.AppViewModel
import com.gino.mywallet.ui.components.ScreenColumn

private enum class CheckInSection(val title: String) {
    SLOT("🎰 拉霸"),
    WORDLE("每日五字"),
    LOTTO("樂透");

    fun next(): CheckInSection {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

@Composable
fun CheckInScreen(
    vm: AppViewModel,
    onOpenPass: () -> Unit
) {
    val season by vm.season.collectAsStateWithLifecycle()
    val tiers by vm.tiers.collectAsStateWithLifecycle()
    val hasDueLotto by vm.hasDueLotto.collectAsStateWithLifecycle()
    var section by remember { mutableStateOf(CheckInSection.SLOT) }

    LaunchedEffect(Unit) {
        vm.refreshMilestones()
        vm.ensureWordle()
    }

    ScreenColumn {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { section = section.next() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    section.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = "切換",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val pending = season?.let { passPendingClaimCount(it, tiers) } ?: 0
            Row(
                modifier = Modifier
                    .clickable(onClick = onOpenPass)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Outlined.Star,
                    contentDescription = "通行證",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${season?.progress ?: 0}/${EconomyEngine.PASS_LENGTH}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                if (pending > 0) {
                    Text(
                        "+$pending",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        when (section) {
            CheckInSection.SLOT -> SlotGamePanel(vm)
            CheckInSection.WORDLE -> DailyWordPanel(vm)
            CheckInSection.LOTTO -> {
                if (hasDueLotto) {
                    Button(
                        onClick = { vm.settleLottoManual() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("開獎") }
                }
                LottoPanel(vm)
            }
        }
    }
}
