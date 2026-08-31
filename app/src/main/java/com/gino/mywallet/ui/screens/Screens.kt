package com.gino.mywallet.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import android.content.Intent
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gino.mywallet.data.entity.CalendarEventEntity
import com.gino.mywallet.data.entity.CourseEntity
import com.gino.mywallet.data.entity.LoanEntity
import com.gino.mywallet.data.entity.LoanStatus
import com.gino.mywallet.data.entity.isCentralOverdraft
import com.gino.mywallet.data.entity.TaskCategories
import com.gino.mywallet.data.entity.TaskEntity
import com.gino.mywallet.data.entity.TaskStatus
import com.gino.mywallet.data.entity.WalletCodes
import com.gino.mywallet.data.entity.WalletEntity
import com.gino.mywallet.ui.AppViewModel
import com.gino.mywallet.ui.bpToPercent
import com.gino.mywallet.ui.components.DatePickerField
import com.gino.mywallet.ui.components.MoneyField
import com.gino.mywallet.ui.components.OptionalTimePickerField
import com.gino.mywallet.ui.components.PrimaryButton
import com.gino.mywallet.ui.components.QuietButton
import com.gino.mywallet.ui.components.RequiredTimePickerField
import com.gino.mywallet.ui.components.ScreenColumn
import com.gino.mywallet.ui.components.SectionTitle
import com.gino.mywallet.ui.components.WalletPicker
import com.gino.mywallet.ui.components.localDateTimeToEpoch
import com.gino.mywallet.ui.formatTwd
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
private fun <T> StateFlow<T>.collectAsStateAware(): State<T> = collectAsStateWithLifecycle()

private fun epochToLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

private val TASK_WHEN_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm")

private fun taskDueOverdue(task: TaskEntity): Boolean {
    val due = task.dueAt ?: return false
    return due < System.currentTimeMillis()
}

private fun taskRepeatNextAt(task: TaskEntity): Long? {
    if (!task.repeatable || task.repeatRuleJson.isBlank()) return null
    return Regex("\"nextAt\"\\s*:\\s*(\\d+)").find(task.repeatRuleJson)
        ?.groupValues?.get(1)?.toLongOrNull()
}

private fun taskMetaLine(task: TaskEntity): String? {
    val parts = mutableListOf<String>()
    task.dueAt?.let { due ->
        val t = Instant.ofEpochMilli(due).atZone(ZoneId.systemDefault())
        val prefix = if (due < System.currentTimeMillis()) "已過期" else "到期"
        parts += "$prefix ${t.format(TASK_WHEN_FMT)}"
    }
    if (task.repeatable) {
        val next = taskRepeatNextAt(task) ?: 0L
        val freq = when {
            task.repeatRuleJson.contains("\"weekly\"") -> "每週"
            task.repeatRuleJson.contains("\"every_n\"") -> "週期"
            else -> "每日"
        }
        if (next <= 0L || next <= System.currentTimeMillis()) {
            parts += "$freq · 可開始"
        } else {
            val t = Instant.ofEpochMilli(next).atZone(ZoneId.systemDefault())
            parts += "$freq · 下次 ${t.format(TASK_WHEN_FMT)}"
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

// ---------- 錢包頁 ----------

@Composable
fun WalletScreen(
    vm: AppViewModel,
    onOpenIncome: () -> Unit,
    onOpenMarket: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val wallets by vm.wallets.collectAsStateAware()
    val charity by vm.charity.collectAsStateAware()

    val living = wallets.filter { it.id == WalletCodes.LIVING }
    val invest = wallets.filter { it.id == WalletCodes.INVEST }
    val piggy = wallets.filter { it.id != WalletCodes.LIVING && it.id != WalletCodes.INVEST && !it.archived }
    val piggyTotal = piggy.sumOf { it.balanceCents }
    val livingTotal = living.sumOf { it.balanceCents }
    val investTotal = invest.sumOf { it.balanceCents }
    val total = wallets.sumOf { it.balanceCents }

    var expandedBucket by remember { mutableStateOf<String?>(null) }
    var balanceEditId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refreshBootstrap() }

    ScreenColumn {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "錢包",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "設定")
            }
        }

        Text(
            text = formatTwd(total),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssetBucketChip(
                title = "撲滿",
                totalCents = piggyTotal,
                selected = expandedBucket == "piggy",
                modifier = Modifier.weight(1f),
                onClick = { expandedBucket = if (expandedBucket == "piggy") null else "piggy" }
            )
            AssetBucketChip(
                title = "錢包",
                totalCents = livingTotal,
                selected = expandedBucket == "living",
                modifier = Modifier.weight(1f),
                onClick = { expandedBucket = if (expandedBucket == "living") null else "living" },
                onAmountClick = { balanceEditId = WalletCodes.LIVING }
            )
            AssetBucketChip(
                title = "投資",
                totalCents = investTotal,
                selected = expandedBucket == "invest",
                modifier = Modifier.weight(1f),
                onClick = { expandedBucket = if (expandedBucket == "invest") null else "invest" },
                onAmountClick = { balanceEditId = WalletCodes.INVEST }
            )
        }

        when (expandedBucket) {
            "piggy" -> AssetBucketDetail(piggy)
            "living" -> AssetBucketDetail(living, onAmountClick = { balanceEditId = it })
            "invest" -> AssetBucketDetail(invest, onAmountClick = { balanceEditId = it })
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FlatActionButton("記一筆收入", "入帳／轉帳", Modifier.weight(1f), onOpenIncome)
            FlatActionButton("借貸市場", "利率／借還款", Modifier.weight(1f), onOpenMarket)
        }

        if (charity.done) {
            Text(
                "${charity.month} 公益已完成",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("本月公益待辦", fontWeight = FontWeight.Bold)
                    Text(
                        formatTwd(charity.amountCents),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "應捐中央 ${charity.ratePercent}%",
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "再拖 1 天 → ${charity.ratePercent + 1}%",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    PrimaryButton("完成公益捐獻") { vm.completeCharity() }
                }
            }
        }
    }

    balanceEditId?.let { walletId ->
        val wallet = wallets.find { it.id == walletId }
        var target by remember(walletId) {
            mutableStateOf(wallet?.let { (it.balanceCents / 100.0).toString() } ?: "")
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { balanceEditId = null },
            title = { Text("平帳 · ${wallet?.name ?: walletId}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("輸入目標餘額（元）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyField(target, { target = it }, "目標餘額")
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.balanceAdjust(walletId, target)
                    balanceEditId = null
                }) { Text("套用") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { balanceEditId = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AssetBucketChip(
    title: String,
    totalCents: Long,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onAmountClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            formatTwd(totalCents),
            modifier = if (onAmountClick != null) {
                Modifier.clickable { onAmountClick() }
            } else {
                Modifier
            },
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            maxLines = 1,
            color = if (totalCents < 0) MaterialTheme.colorScheme.error else Color.Unspecified
        )
    }
}

@Composable
private fun AssetBucketDetail(
    wallets: List<WalletEntity>,
    onAmountClick: ((String) -> Unit)? = null
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (wallets.isEmpty()) {
            Text("無帳戶", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            wallets.sortedBy { it.sortOrder }.forEach { w ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(w.name, fontSize = 13.sp)
                        if (!w.canReceiveIncome) {
                            Text("停收收入", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                        }
                    }
                    Text(
                        formatTwd(w.balanceCents),
                        modifier = if (onAmountClick != null) {
                            Modifier.clickable { onAmountClick(w.id) }
                        } else {
                            Modifier
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = if (w.balanceCents < 0) MaterialTheme.colorScheme.error else Color.Unspecified
                    )
                }
            }
        }
    }
}

@Composable
private fun FlatActionButton(title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun txTypeLabel(type: String): String = when (type) {
    "INCOME_A" -> "收入分配"
    "INCOME_B" -> "直匯收入"
    "TRANSFER" -> "轉帳"
    "DONATION" -> "公益捐獻"
    "CHARITY_TAX" -> "公益徵稅"
    "LOAN_DISBURSE" -> "借款撥付"
    "LOAN_REPAY" -> "還款"
    "LOAN_INTEREST" -> "借貸月息"
    "LOAN_RATE_CUT" -> "降息"
    "BALANCE_ADJUST" -> "平帳"
    "LOAN_DEFAULT" -> "違約"
    "CENTRAL_DEBT" -> "中央欠債"
    "CENTRAL_DEBT_INTEREST" -> "欠債利息"
    "CENTRAL_DEBT_REPAY" -> "償還欠債"
    "REWARD_TRANSFER" -> "獎懲轉帳"
    "LOTTERY" -> "抽獎"
    "BJ21" -> "二十一點"
    "SCRATCH" -> "比大小"
    "HILO" -> "比大小"
    "TRIPLE_DRAW" -> "殺撲克"
    "MIGRATE" -> "帳戶調整"
    else -> type
}

@Composable
private fun EntryCard(title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FlatActionButton(title, subtitle, modifier, onClick)
}

// ---------- 收入子頁 ----------

@Composable
fun IncomeScreen(vm: AppViewModel, onDone: () -> Unit = {}) {
    val wallets by vm.wallets.collectAsStateAware()
    val allocation by vm.allocation.collectAsStateAware()
    var tab by remember { mutableIntStateOf(0) } // 0 入帳 1 轉帳
    var amount by remember { mutableStateOf("") }
    var quick by remember { mutableStateOf<String?>(null) } // "500" / "1000" / "custom"

    ScreenColumn {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = tab == 0,
                onClick = { tab = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) { Text("入帳") }
            SegmentedButton(
                selected = tab == 1,
                onClick = { tab = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) { Text("轉帳") }
        }

        if (tab == 0) {
            OutlinedTextField(
                value = amount,
                onValueChange = {
                    amount = it.filter { c -> c.isDigit() }
                    quick = "custom"
                },
                label = { Text("金額（元）") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 28.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                ),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("500", "1000").forEach { preset ->
                    FilterChip(
                        selected = quick == preset,
                        onClick = {
                            quick = preset
                            amount = preset
                        },
                        label = { Text(preset) }
                    )
                }
                FilterChip(
                    selected = quick == "custom" || (quick == null && amount.isNotBlank() && amount !in listOf("500", "1000")),
                    onClick = { quick = "custom" },
                    label = { Text("自訂") }
                )
            }

            val yuan = amount.toLongOrNull() ?: 0L
            val cents = yuan * 100
            if (cents > 0) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("標準分配預覽", style = MaterialTheme.typography.labelMedium)
                        val rules = allocation.ifEmpty {
                            listOf(
                                com.gino.mywallet.data.entity.AllocationRuleEntity(WalletCodes.CENTRAL, 4000, true),
                                com.gino.mywallet.data.entity.AllocationRuleEntity(WalletCodes.LIVING, 5000),
                                com.gino.mywallet.data.entity.AllocationRuleEntity(WalletCodes.BCI, 1000)
                            )
                        }
                        var left = cents
                        rules.forEachIndexed { i, rule ->
                            val share = if (i == rules.lastIndex) left else cents * rule.percentBp / 10_000
                            if (i != rules.lastIndex) left -= share
                            val name = wallets.find { it.id == rule.walletId }?.name ?: rule.walletId
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("$name  ${bpToPercent(rule.percentBp)}", fontSize = 13.sp)
                                Text(formatTwd(share), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            PrimaryButton("入帳並分配", enabled = amount.isNotBlank()) {
                vm.incomeStandard(amount, "", onDone = onDone)
            }
        } else {
            TransferBlock(vm, wallets, onDone = onDone)
        }
    }
}

@Composable
private fun TransferBlock(vm: AppViewModel, wallets: List<WalletEntity>, onDone: () -> Unit = {}) {
    var from by remember { mutableStateOf(WalletCodes.LIVING) }
    var to by remember { mutableStateOf(WalletCodes.ACG) }
    var amount by remember { mutableStateOf("") }
    WalletPicker("從", wallets, from, { from = it }, filter = { !it.isCentral })
    WalletPicker("到", wallets, to, { to = it })
    MoneyField(amount, { amount = it })
    PrimaryButton("轉帳", enabled = amount.isNotBlank()) {
        vm.transfer(from, to, amount, "", onDone = onDone)
    }
}

// ---------- 借貸子頁 ----------

@Composable
private fun RateAutoSaveRow(
    name: String,
    walletId: String,
    rateBp: Int,
    onSave: (String) -> Unit
) {
    var edit by remember(walletId) { mutableStateOf((rateBp / 100.0).toString()) }
    LaunchedEffect(rateBp) {
        val fromDb = if (rateBp % 100 == 0) (rateBp / 100).toString() else (rateBp / 100.0).toString()
        val currentBp = edit.toDoubleOrNull()?.let { (it * 100).toInt() }
        if (currentBp != rateBp) edit = fromDb
    }
    LaunchedEffect(edit) {
        delay(600)
        val parsed = edit.toDoubleOrNull() ?: return@LaunchedEffect
        if (parsed < 0) return@LaunchedEffect
        val bp = (parsed * 100).toInt()
        if (bp != rateBp) onSave(edit)
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = edit,
            onValueChange = { edit = it },
            label = { Text("%") },
            modifier = Modifier.width(100.dp),
            singleLine = true
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(vm: AppViewModel) {
    val wallets by vm.wallets.collectAsStateAware()
    val rates by vm.walletRates.collectAsStateAware()
    val loans by vm.loans.collectAsStateAware()
    var showBorrow by remember { mutableStateOf(false) }
    var repayLoanId by remember { mutableStateOf<Long?>(null) }
    var defaultConfirm by remember { mutableStateOf<Long?>(null) }

    fun walletName(id: String) = wallets.find { it.id == id }?.name ?: id
    fun statusLabel(s: String) = when (s) {
        LoanStatus.ACTIVE -> "進行中"
        LoanStatus.DEFAULTED -> "已違約"
        LoanStatus.REPAID -> "已還清"
        else -> s
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (loans.isEmpty()) {
                Text("目前無借據", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                loans.forEach { loan ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    if (loan.isCentralOverdraft()) "中央透支"
                                    else "${walletName(loan.borrowerWalletId)} ← ${walletName(loan.lenderWalletId)}",
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                                Text(
                                    statusLabel(loan.status),
                                    fontSize = 12.sp,
                                    color = when (loan.status) {
                                        LoanStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                                        LoanStatus.DEFAULTED -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Text(
                                formatTwd(loan.remainingCents),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            val dueDate = epochToLocalDate(loan.dueAt)
                            val daysUntil = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), dueDate)
                            Text(
                                "還債日 ${dueDate.format(DateTimeFormatter.ofPattern("MM/dd"))} · 剩 $daysUntil 天 · ${bpToPercent(loan.monthlyRateBp)}",
                                fontSize = 12.sp,
                                color = if (daysUntil < 7) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!loan.isCentralOverdraft() &&
                                (loan.status == LoanStatus.ACTIVE || loan.status == LoanStatus.DEFAULTED)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    QuietButton("還款") { repayLoanId = loan.id }
                                    QuietButton("還清") { vm.repayFull(loan.id) }
                                    if (loan.status == LoanStatus.ACTIVE) {
                                        androidx.compose.material3.TextButton(onClick = { defaultConfirm = loan.id }) {
                                            Text("違約", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            } else if (loan.isCentralOverdraft() &&
                                (loan.status == LoanStatus.ACTIVE || loan.status == LoanStatus.DEFAULTED)
                            ) {
                                Text(
                                    "由中央收入分配自動償還",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { showBorrow = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            icon = { Icon(Icons.Outlined.Add, contentDescription = "增加借款") },
            text = { Text("增加借款", fontWeight = FontWeight.Bold) }
        )
    }

    if (showBorrow) {
        var lender by remember { mutableStateOf(WalletCodes.INVEST) }
        var borrower by remember { mutableStateOf(WalletCodes.LIVING) }
        var borrowAmt by remember { mutableStateOf("") }
        var dueThisMonth by remember { mutableStateOf(true) }
        val preview = vm.previewRepay(borrowAmt, lender, dueThisMonth)
        val lenderRate = rates.find { it.walletId == lender }?.monthlyRateBp ?: 0

        ModalBottomSheet(onDismissRequest = { showBorrow = false }) {
            Column(
                Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("增加借款", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WalletPicker(
                        "從（出借）",
                        wallets,
                        lender,
                        { lender = it },
                        modifier = Modifier.weight(1f)
                    )
                    Text("→", fontWeight = FontWeight.Bold)
                    WalletPicker(
                        "到（借入）",
                        wallets,
                        borrower,
                        { borrower = it },
                        modifier = Modifier.weight(1f),
                        filter = { !it.isCentral }
                    )
                }
                Text(
                    "出借戶月利率 ${bpToPercent(lenderRate)}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
                MoneyField(borrowAmt, { borrowAmt = it }, "借款金額")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = dueThisMonth,
                        onClick = { dueThisMonth = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text("本月還") }
                    SegmentedButton(
                        selected = !dueThisMonth,
                        onClick = { dueThisMonth = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text("下月還") }
                }
                Text(
                    if (borrowAmt.isBlank()) "預估應還 —"
                    else "預估應還 ${formatTwd(preview)}（${if (dueThisMonth) "含最低息" else "含一個月息"}）",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PrimaryButton("確認借款", enabled = borrowAmt.isNotBlank()) {
                    vm.borrowDirect(lender, borrower, borrowAmt, dueThisMonth)
                    showBorrow = false
                }
            }
        }
    }

    repayLoanId?.let { loanId ->
        val loan = loans.find { it.id == loanId }
        var repay by remember(loanId) { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { repayLoanId = null },
            title = { Text("還款") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (loan != null) {
                        Text("應還 ${formatTwd(loan.remainingCents)}", fontSize = 13.sp)
                    }
                    MoneyField(repay, { repay = it }, "還款金額")
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.repay(loanId, repay)
                    repayLoanId = null
                }) { Text("確認") }
            },
            dismissButton = {
                Row {
                    if (loan != null) {
                        androidx.compose.material3.TextButton(onClick = {
                            vm.repayFull(loanId)
                            repayLoanId = null
                        }) { Text("還清") }
                    }
                    androidx.compose.material3.TextButton(onClick = { repayLoanId = null }) { Text("取消") }
                }
            }
        )
    }

    defaultConfirm?.let { loanId ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { defaultConfirm = null },
            title = { Text("確認違約") },
            text = { Text("標記此借據為違約後，將啟動違約利率計算。確定要執行嗎？") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.markDefault(loanId)
                    defaultConfirm = null
                }) { Text("確定", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { defaultConfirm = null }) { Text("取消") }
            }
        )
    }
}

// ---------- 任務頁（清單／行事曆） ----------

private enum class CreateSheetTab { TASK, EVENT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    vm: AppViewModel,
    onOpenFocus: () -> Unit = {}
) {
    var calendarMode by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var sheetTab by remember { mutableStateOf(CreateSheetTab.TASK) }
    var calendarSelectedDate by remember { mutableStateOf(LocalDate.now()) }
    var selectingTasks by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "任務",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onOpenFocus) {
                    Text("開始專注")
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !calendarMode,
                    onClick = { calendarMode = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("清單") }
                SegmentedButton(
                    selected = calendarMode,
                    onClick = {
                        calendarMode = true
                        selectingTasks = false
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("行事曆") }
            }
            if (calendarMode) {
                TaskCalendarSection(
                    vm = vm,
                    selectedDate = calendarSelectedDate,
                    onSelectDate = { calendarSelectedDate = it }
                )
            } else {
                TaskListSection(
                    vm = vm,
                    onSelectionModeChange = { selectingTasks = it }
                )
            }
        }

        if (!selectingTasks) {
            SmallFloatingActionButton(
                onClick = {
                    sheetTab = if (calendarMode) CreateSheetTab.EVENT else CreateSheetTab.TASK
                    showSheet = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "新增")
            }
        }
    }

    if (showSheet) {
        CreateActionSheet(
            vm = vm,
            initialTab = sheetTab,
            defaultDate = calendarSelectedDate,
            onDismiss = { showSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateActionSheet(
    vm: AppViewModel,
    initialTab: CreateSheetTab,
    defaultDate: LocalDate,
    onDismiss: () -> Unit
) {
    var tab by remember(initialTab) { mutableStateOf(initialTab) }
    val sheetMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.88f

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = sheetMaxHeight)
        ) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = tab == CreateSheetTab.TASK,
                        onClick = { tab = CreateSheetTab.TASK },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text("任務") }
                    SegmentedButton(
                        selected = tab == CreateSheetTab.EVENT,
                        onClick = { tab = CreateSheetTab.EVENT },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text("行程") }
                }
            }
            when (tab) {
                CreateSheetTab.TASK -> CreateTaskFormContent(vm = vm, onDismiss = onDismiss)
                CreateSheetTab.EVENT -> CreateEventFormContent(vm = vm, defaultDate = defaultDate, onDismiss = onDismiss)
            }
        }
    }
}

@Composable
private fun CreateTaskFormContent(vm: AppViewModel, onDismiss: () -> Unit) {
    val wallets by vm.wallets.collectAsStateAware()
    var showDetail by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("生活") }
    var ticketLevel by remember { mutableIntStateOf(2) }
    var hasReward by remember { mutableStateOf(false) }
    var repeatable by remember { mutableStateOf(false) }
    var repeatFreq by remember { mutableStateOf("daily") }
    var repeatN by remember { mutableStateOf("1") }
    var reward by remember { mutableStateOf("") }
    var rewardTo by remember { mutableStateOf(WalletCodes.LIVING) }
    var hasDue by remember { mutableStateOf(false) }
    var dueDate by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    var dueTime by remember { mutableStateOf(LocalTime.of(18, 0)) }
    var repeatFirstDate by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    var repeatFirstTime by remember { mutableStateOf(LocalTime.of(9, 0)) }

    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("標題") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TaskCategories.PRESETS.forEach { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = { category = cat },
                        label = { Text(cat) }
                    )
                }
            }
            TicketLevelStepper(
                level = ticketLevel,
                onLevelChange = { ticketLevel = it }
            )
            QuietButton(if (showDetail) "收合詳細" else "詳細") { showDetail = !showDetail }
            if (showDetail) {
                TaskSheetSection("到期") {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("設定到期", fontWeight = FontWeight.Medium)
                        Switch(checked = hasDue, onCheckedChange = { hasDue = it })
                    }
                    if (hasDue) {
                        DatePickerField("到期日", dueDate) { dueDate = it }
                        RequiredTimePickerField("到期時間", dueTime) { dueTime = it }
                    }
                }
                TaskSheetSection("說明") {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("補充說明（可空）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
                TaskSheetSection("完成獎勵") {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("有獎勵")
                        Switch(checked = hasReward, onCheckedChange = { hasReward = it })
                    }
                    if (hasReward) {
                        MoneyField(reward, { reward = it }, "獎勵金額")
                        WalletPicker("入帳到", wallets, rewardTo, { rewardTo = it }, { !it.isCentral })
                    }
                }
                TaskSheetSection("重複") {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("可重複")
                        Switch(checked = repeatable, onCheckedChange = { repeatable = it })
                    }
                    if (repeatable) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("daily" to "每日", "weekly" to "每週", "every_n" to "每N天").forEach { (k, lab) ->
                                FilterChip(
                                    selected = repeatFreq == k,
                                    onClick = { repeatFreq = k },
                                    label = { Text(lab) }
                                )
                            }
                        }
                        if (repeatFreq == "every_n") {
                            OutlinedTextField(
                                value = repeatN,
                                onValueChange = { repeatN = it.filter { c -> c.isDigit() } },
                                label = { Text("間隔天數") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                        DatePickerField("下次可做日期", repeatFirstDate) { repeatFirstDate = it }
                        RequiredTimePickerField("下次可做時間", repeatFirstTime) { repeatFirstTime = it }
                    }
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 32.dp)
        ) {
            PrimaryButton("新增任務", enabled = title.isNotBlank()) {
                vm.createTask(
                    title = title,
                    note = note,
                    category = category,
                    repeatable = repeatable,
                    repeatFreq = repeatFreq,
                    repeatN = repeatN,
                    rewardYuan = if (hasReward) reward else "",
                    rewardTo = rewardTo,
                    ticketLevel = ticketLevel,
                    dueAt = if (hasDue) localDateTimeToEpoch(dueDate, dueTime) else null,
                    repeatFirstAt = if (repeatable) {
                        localDateTimeToEpoch(repeatFirstDate, repeatFirstTime)
                    } else {
                        null
                    }
                )
                onDismiss()
            }
        }
    }
}

@Composable
private fun CreateEventFormContent(
    vm: AppViewModel,
    defaultDate: LocalDate,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var date by remember(defaultDate) { mutableStateOf(defaultDate) }
    var timeMinutes by remember { mutableIntStateOf(-1) }
    var location by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("標題") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            DatePickerField("日期", date) { date = it }
            OptionalTimePickerField("時間", timeMinutes) { timeMinutes = it }
            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("地點") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("備註（可空）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            if (timeMinutes >= 0) {
                Text(
                    "將於開始前 15 分鐘提醒",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 32.dp)
        ) {
            PrimaryButton("新增行程", enabled = title.isNotBlank()) {
                vm.createCalendarEvent(
                    title = title,
                    dateYmd = date.toString(),
                    timeMinutes = timeMinutes,
                    location = location,
                    note = note
                )
                onDismiss()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseFormSheet(
    vm: AppViewModel,
    editing: CourseEntity?,
    defaultWeekday: Int,
    onDismiss: () -> Unit
) {
    var title by remember(editing) { mutableStateOf(editing?.title.orEmpty()) }
    var weekday by remember(editing, defaultWeekday) {
        mutableIntStateOf(editing?.weekday ?: defaultWeekday)
    }
    var periodStart by remember(editing) { mutableIntStateOf(editing?.periodStart ?: 3) }
    var periodEnd by remember(editing) { mutableIntStateOf(editing?.periodEnd ?: 4) }
    var location by remember(editing) { mutableStateOf(editing?.location.orEmpty()) }
    val sheetMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.75f

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = sheetMaxHeight)
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("課程名稱") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text("星期", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..7).forEach { d ->
                        val label = com.gino.mywallet.domain.PeriodTimes.weekdayLabel(d)
                        FilterChip(
                            selected = weekday == d,
                            onClick = { weekday = d },
                            label = { Text("週$label") }
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("開始節次", fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { periodStart = (periodStart - 1).coerceAtLeast(1) }) {
                            Text("−", fontWeight = FontWeight.Bold)
                        }
                        Text("$periodStart", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { periodStart = (periodStart + 1).coerceAtMost(periodEnd) }) {
                            Text("+", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("結束節次", fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { periodEnd = (periodEnd - 1).coerceAtLeast(periodStart) }) {
                            Text("−", fontWeight = FontWeight.Bold)
                        }
                        Text("$periodEnd", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { periodEnd = (periodEnd + 1).coerceAtMost(12) }) {
                            Text("+", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("地點") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 32.dp)
            ) {
                PrimaryButton(
                    if (editing == null) "新增課程" else "儲存",
                    enabled = title.isNotBlank()
                ) {
                    vm.saveCourse(
                        id = editing?.id ?: 0L,
                        title = title,
                        weekday = weekday,
                        periodStart = periodStart,
                        periodEnd = periodEnd,
                        location = location
                    )
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun TicketLevelStepper(
    level: Int,
    onLevelChange: (Int) -> Unit
) {
    val maxLevel = com.gino.mywallet.domain.game.GamePrizes.MAX_TICKET_LEVEL
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "券包等級",
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 8.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onLevelChange((level - 1).coerceAtLeast(1)) },
                enabled = level > 1
            ) {
                Text("−", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(
                "$level",
                modifier = Modifier.width(48.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            IconButton(
                onClick = { onLevelChange((level + 1).coerceAtMost(maxLevel)) },
                enabled = level < maxLevel
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TaskSheetSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun TaskListSection(
    vm: AppViewModel,
    onSelectionModeChange: (Boolean) -> Unit = {}
) {
    val tasks by vm.tasks.collectAsStateAware()
    var filterCat by remember { mutableStateOf(TaskCategories.ALL) }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var completingIds by remember { mutableStateOf(setOf<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun setSelecting(on: Boolean) {
        selecting = on
        if (!on) selectedIds = emptySet()
        onSelectionModeChange(on)
    }

    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = filterCat == TaskCategories.ALL,
            onClick = { filterCat = TaskCategories.ALL },
            label = { Text(TaskCategories.ALL) }
        )
        TaskCategories.PRESETS.forEach { cat ->
            FilterChip(
                selected = filterCat == cat,
                onClick = { filterCat = cat },
                label = { Text(cat) }
            )
        }
    }

    val openTasks = tasks
        .filter { it.status == TaskStatus.OPEN }
        .filter { filterCat == TaskCategories.ALL || it.category == filterCat }
        .sortedWith(
            compareBy<TaskEntity> { it.ticketLevel() }.thenBy { it.createdAt }
        )

    if (selecting) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("已選 ${selectedIds.size}", fontWeight = FontWeight.Medium)
            Row {
                TextButton(onClick = { selectedIds = openTasks.map { it.id }.toSet() }) {
                    Text("全選")
                }
                TextButton(onClick = { setSelecting(false) }) { Text("取消") }
                TextButton(
                    onClick = { confirmDelete = true },
                    enabled = selectedIds.isNotEmpty()
                ) { Text("刪除") }
            }
        }
    }

    if (openTasks.isEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "尚無任務",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "點右下角 ＋ 新增",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        openTasks.forEach { t ->
            OpenTaskCard(
                task = t,
                selected = t.id in selectedIds,
                selecting = selecting,
                completing = t.id in completingIds,
                onToggleSelect = {
                    selectedIds = if (t.id in selectedIds) selectedIds - t.id else selectedIds + t.id
                },
                onEnterSelect = {
                    setSelecting(true)
                    selectedIds = setOf(t.id)
                },
                onCompleteAnimStart = { completingIds = completingIds + t.id },
                onCompleteAnimEnd = {
                    completingIds = completingIds - t.id
                    vm.completeTask(t.id)
                }
            )
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("刪除任務") },
            text = { Text("將刪除 ${selectedIds.size} 筆任務，不會發放獎勵。此操作無法復原。") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        vm.deleteTasks(selectedIds)
                        confirmDelete = false
                        setSelecting(false)
                    }
                ) { Text("確定刪除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OpenTaskCard(
    task: TaskEntity,
    selected: Boolean,
    selecting: Boolean,
    completing: Boolean,
    onToggleSelect: () -> Unit,
    onEnterSelect: () -> Unit,
    onCompleteAnimStart: () -> Unit,
    onCompleteAnimEnd: () -> Unit
) {
    val scale = remember { Animatable(1f) }
    val alpha = remember { Animatable(1f) }
    val categoryColor = taskCategoryBorderColor(task.category)
    val successGreen = Color(0xFF43A047)

    LaunchedEffect(completing) {
        if (!completing) return@LaunchedEffect
        coroutineScope {
            launch { scale.animateTo(0.72f, tween(380, easing = FastOutSlowInEasing)) }
            launch { alpha.animateTo(0f, tween(380, easing = FastOutSlowInEasing)) }
        }
        onCompleteAnimEnd()
    }

    val borderColor = when {
        completing -> successGreen.copy(alpha = 0.55f)
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else -> categoryColor
    }

    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            }
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
        }
        Row(
            Modifier
                .weight(1f)
                .combinedClickable(
                    enabled = !completing,
                    onClick = {
                        if (selecting) onToggleSelect() else onCompleteAnimStart()
                    },
                    onLongClick = {
                        if (!selecting) onEnterSelect()
                    }
                )
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(task.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (task.note.isNotBlank()) {
                    Text(
                        task.note,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                taskMetaLine(task)?.let { meta ->
                    Text(
                        meta,
                        fontSize = 12.sp,
                        color = if (taskDueOverdue(task)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            val lv = task.ticketLevel()
            Text(
                if (lv > 0) "Lv.$lv" else "Lv.—",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = if (completing) successGreen else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun taskCategoryBorderColor(category: String): Color = when (category) {
    "生活" -> Color(0xFF7CB342).copy(alpha = 0.42f)
    "學業" -> Color(0xFF42A5F5).copy(alpha = 0.42f)
    "專案" -> Color(0xFFAB47BC).copy(alpha = 0.42f)
    "社交" -> Color(0xFFFF7043).copy(alpha = 0.42f)
    "財政" -> Color(0xFFFFB300).copy(alpha = 0.42f)
    else -> Color(0xFF90A4AE).copy(alpha = 0.38f)
}

@Composable
private fun TaskCalendarSection(
    vm: AppViewModel,
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit
) {
    val loans by vm.loans.collectAsStateAware()
    val tasks by vm.tasks.collectAsStateAware()
    val charity by vm.charity.collectAsStateAware()
    val courses by vm.courses.collectAsStateAware()
    val events by vm.calendarEvents.collectAsStateAware()
    val wallets by vm.wallets.collectAsStateAware()

    fun walletName(id: String): String = wallets.find { it.id == id }?.name ?: id

    var ym by remember { mutableStateOf(YearMonth.from(selectedDate)) }

    val dueDates = loans
        .filter { it.status != LoanStatus.REPAID }
        .groupBy { epochToLocalDate(it.dueAt) }
    val doneDates = tasks
        .filter { it.status == TaskStatus.DONE && it.resolvedAt != null }
        .groupBy { epochToLocalDate(it.resolvedAt!!) }
    val openDueDates = tasks
        .filter { it.status == TaskStatus.OPEN && it.dueAt != null }
        .groupBy { epochToLocalDate(it.dueAt!!) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { ym = ym.minusMonths(1) }) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "上個月")
        }
        Text("$ym", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { ym = ym.plusMonths(1) }) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "下個月")
        }
    }

    // 星期列（週日開頭）
    Row(Modifier.fillMaxWidth()) {
        listOf("日", "一", "二", "三", "四", "五", "六").forEach {
            Text(
                it,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    val firstDay = ym.atDay(1)
    val leadingBlanks = firstDay.dayOfWeek.value % 7 // 週日=0
    val totalCells = leadingBlanks + ym.lengthOfMonth()
    val weeks = (totalCells + 6) / 7

    repeat(weeks) { week ->
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { dow ->
                val cellIndex = week * 7 + dow
                val dayNum = cellIndex - leadingBlanks + 1
                if (dayNum in 1..ym.lengthOfMonth()) {
                    val date = ym.atDay(dayNum)
                    DayCell(
                        date = date,
                        isSelected = selectedDate == date,
                        isToday = date == LocalDate.now(),
                        hasCharity = dayNum == 1,
                        hasEvent = events.any { it.occursOn(date) },
                        hasTaskDone = doneDates.containsKey(date),
                        hasTaskDue = openDueDates.containsKey(date),
                        modifier = Modifier.weight(1f)
                    ) { onSelectDate(date) }
                } else {
                    Spacer(Modifier.weight(1f).aspectRatio(1f))
                }
            }
        }
    }

    // 圖例
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendDot(MaterialTheme.colorScheme.primary, "公益日")
        LegendDot(MaterialTheme.colorScheme.error, "待辦")
        LegendSwatch(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f), "任務完成")
        LegendDot(MaterialTheme.colorScheme.secondary, "行程")
    }

    val date = selectedDate
    SectionTitle("$date")
    val systemItems = buildList {
        if (date.dayOfMonth == 1) {
            add("公益日：本月公益任務開始（基準 10%，每延期一天 +1%）")
        }
        if (YearMonth.from(date).toString() == charity.month && !charity.done && date == LocalDate.now()) {
            add("本月公益尚未完成，今日應捐 ${charity.ratePercent}%")
        }
        dueDates[date]?.forEach { loan: LoanEntity ->
            add(
                "借款 #${loan.id} 到期：${walletName(loan.borrowerWalletId)} ← ${walletName(loan.lenderWalletId)} 餘 ${formatTwd(loan.remainingCents)}"
            )
        }
        doneDates[date]?.forEach { t: TaskEntity ->
            add("完成任務：[${t.category}] ${t.title}")
        }
        openDueDates[date]?.forEach { t: TaskEntity ->
            val time = t.dueAt?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime()
                    .format(DateTimeFormatter.ofPattern("HH:mm"))
            }
            add("待辦：[${t.category}] ${t.title}${time?.let { " · $it" } ?: ""}")
        }
    }
    val dayEvents = events.filter { it.occursOn(date) }.sortedBy { it.timeMinutes }
    val dayCourses = courses
        .filter { it.weekday == date.dayOfWeek.value }
        .sortedBy { it.periodStart }

    if (systemItems.isEmpty() && dayEvents.isEmpty() && dayCourses.isEmpty()) {
        Text("這天沒有記錄", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    } else {
        systemItems.forEach { Text("· $it", fontSize = 13.sp) }
        dayEvents.forEach { event ->
            val timePrefix = event.timeLabel()?.let { "$it " }.orEmpty()
            val loc = event.location.takeIf { it.isNotBlank() }?.let { " @ $it" }.orEmpty()
            val noteSuffix = event.note.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            Text(
                "· 行程：$timePrefix${event.title}$loc$noteSuffix",
                fontSize = 13.sp
            )
        }
    }

    SectionTitle("課表")
    if (dayCourses.isEmpty()) {
        Text("這天沒有課", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    } else {
        val dayLabel = com.gino.mywallet.domain.PeriodTimes.weekdayLabel(date.dayOfWeek.value)
        Text(
            "週$dayLabel",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 4.dp)
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text("時間", Modifier.width(100.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("課程", Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("地點", Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                dayCourses.forEachIndexed { index, c ->
                    val time = com.gino.mywallet.domain.PeriodTimes.formatPeriodRange(c.periodStart, c.periodEnd)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (index % 2 == 1) {
                                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                                } else Modifier
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(time, Modifier.width(100.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text(c.title, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(
                            c.location,
                            Modifier.weight(1f),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isSelected: Boolean,
    isToday: Boolean,
    hasCharity: Boolean,
    hasEvent: Boolean,
    hasTaskDone: Boolean,
    hasTaskDue: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        isToday -> MaterialTheme.colorScheme.surfaceVariant
        hasTaskDue -> MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
        hasTaskDone -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)
        else -> Color.Transparent
    }
    Column(
        modifier = modifier
            .aspectRatio(1f)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "${date.dayOfMonth}",
            fontSize = 13.sp,
            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (hasCharity) Dot(MaterialTheme.colorScheme.primary)
            if (hasTaskDue) Dot(MaterialTheme.colorScheme.error)
            if (hasEvent) Dot(MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        Modifier
            .size(5.dp)
            .background(color, CircleShape)
    )
}

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .size(width = 10.dp, height = 8.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Dot(color)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- 娛樂頁 ----------

@Composable
fun EntertainmentScreen(
    vm: AppViewModel,
    onOpenPass: () -> Unit = {}
) {
    val lottery = vm.lotteryState.collectAsStateAware().value
    val slotTickets = lottery?.slotTickets ?: 0
    val lotteryState = lottery
    val wallets by vm.wallets.collectAsStateAware()
    val lastDraw by vm.lastDraw.collectAsStateAware()
    val drawing by vm.drawing.collectAsStateAware()
    val game by vm.entertainmentGame.collectAsStateAware()
    val season by vm.season.collectAsStateAware()
    val tiers by vm.tiers.collectAsStateAware()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(250)
        }
    }

    val nextFree = lotteryState?.nextFreeAt ?: 0L
    val remain = (nextFree - nowMs).coerceAtLeast(0L)
    val canClaim = remain <= 0L
    val h = remain / 3_600_000
    val m = (remain % 3_600_000) / 60_000
    val s = (remain % 60_000) / 1000
    val cooldownText = "%02d:%02d:%02d".format(h, m, s)
    val walletNames = remember(wallets) {
        wallets.filter { !it.isCentral && !it.archived && it.id != WalletCodes.INVEST }.map { it.name }
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
                    .clickable { vm.cycleEntertainmentGame() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = game.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = "切換遊戲",
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
                    "${season?.progress ?: 0}/60",
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

        when (game) {
            com.gino.mywallet.domain.game.EntertainmentGame.SLOT -> {
                SlotCabinet(
                    lastDraw = lastDraw,
                    spinning = drawing,
                    walletNames = walletNames,
                    boosted = lastDraw?.boosted == true && drawing
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (canClaim) {
                        OutlinedButton(
                            onClick = { vm.claimCooldown() },
                            enabled = !drawing,
                            modifier = Modifier.weight(1f)
                        ) { Text("每日免費") }
                    } else {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.weight(1f)
                        ) { Text(cooldownText) }
                    }
                    Button(
                        onClick = { vm.draw() },
                        enabled = slotTickets > 0 && !drawing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (drawing) "開獎中…" else "拉霸($slotTickets)")
                    }
                }
            }
            com.gino.mywallet.domain.game.EntertainmentGame.BLACKJACK21 -> Blackjack21Panel(vm)
            com.gino.mywallet.domain.game.EntertainmentGame.SCRATCH -> HiLoPanel(vm)
            com.gino.mywallet.domain.game.EntertainmentGame.TRIPLE_DRAW -> TripleDrawPanel(vm)
        }
    }
}

/** @deprecated 請用 EntertainmentScreen */
@Composable
fun DrawScreen(vm: AppViewModel) {
    EntertainmentScreen(vm)
}

@Composable
private fun SlotResultBanner(result: com.gino.mywallet.data.entity.SlotDrawResult) {
    val scale = remember(result) { Animatable(0.85f) }
    val alpha = remember(result) { Animatable(1f) }
    LaunchedEffect(result) {
        scale.snapTo(0.85f)
        alpha.snapTo(1f)
        scale.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
        kotlinx.coroutines.delay(2000)
        alpha.animateTo(0f, tween(500))
    }
    val isMiss = result.tier == com.gino.mywallet.data.entity.SlotTiers.MISS
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            },
        contentAlignment = Alignment.Center
    ) {
        if (isMiss) {
            Text(
                "銘謝惠顧",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (result.cashCents > 0) {
                    Text(
                        formatTwd(result.cashCents),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        result.label,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    result.walletName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (result.boosted) {
                    Text("×3", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

@Composable
private fun SlotCabinet(
    lastDraw: com.gino.mywallet.data.entity.SlotDrawResult?,
    spinning: Boolean,
    walletNames: List<String>,
    boosted: Boolean
) {
    val frame = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val body = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(body)
            .padding(3.dp)
            .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 機體頂燈條
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (boosted) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                )
        )
        Spacer(Modifier.height(10.dp))
        // 戶頭揭示窗
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.75f))
                .border(1.dp, frame, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            WalletRevealStrip(
                names = walletNames,
                lockedName = lastDraw?.walletName,
                spinning = spinning
            )
        }
        Spacer(Modifier.height(12.dp))
        // 轉輪窗
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .border(2.dp, frame, RoundedCornerShape(12.dp))
                .padding(vertical = 14.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            SlotVisual3Phase(lastDraw = lastDraw, spinning = spinning)
        }
        if (!spinning && lastDraw != null) {
            Spacer(Modifier.height(10.dp))
            SlotResultBanner(result = lastDraw)
        }
        Spacer(Modifier.height(10.dp))
        // 機體底飾
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
private fun WalletRevealStrip(
    names: List<String>,
    lockedName: String?,
    spinning: Boolean
) {
    var display by remember { mutableStateOf(lockedName ?: "—") }
    LaunchedEffect(spinning, lockedName, names) {
        if (spinning && names.isNotEmpty()) {
            val end = System.currentTimeMillis() + 1000
            var i = 0
            while (System.currentTimeMillis() < end) {
                display = names[i % names.size]
                i++
                delay(70)
            }
            display = lockedName ?: names.first()
        } else if (lockedName != null) {
            display = lockedName
        }
    }
    Text(
        text = display,
        color = Color(0xFFFFE082),
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun SlotVisual3Phase(
    lastDraw: com.gino.mywallet.data.entity.SlotDrawResult?,
    spinning: Boolean
) {
    val symbols = listOf("7", "⭐", "$", "💎")
    var target0 by remember { mutableStateOf(symbols[0]) }
    var target1 by remember { mutableStateOf(symbols[1]) }
    var target2 by remember { mutableStateOf(symbols[2]) }
    var spin0 by remember { mutableStateOf(false) }
    var spin1 by remember { mutableStateOf(false) }
    var spin2 by remember { mutableStateOf(false) }
    var nearMissFor2 by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(spinning, lastDraw) {
        if (spinning && lastDraw != null) {
            // 段1：戶頭揭示由外層處理，此處等 1s
            delay(1000)
            target0 = lastDraw.reel0
            target1 = lastDraw.reel1
            target2 = lastDraw.reel2
            nearMissFor2 = if (lastDraw.nearMiss) lastDraw.reel0 else null
            spin0 = true
            spin1 = true
            spin2 = true
            delay(1000) // 第 1 秒停輪1
            spin0 = false
            delay(1000) // 第 2 秒停輪2
            spin1 = false
            delay(2000) // 第 4 秒停輪3（含 near-miss 滑動）
            spin2 = false
            delay(400)
        } else if (lastDraw != null) {
            target0 = lastDraw.reel0
            target1 = lastDraw.reel1
            target2 = lastDraw.reel2
            nearMissFor2 = null
            spin0 = false
            spin1 = false
            spin2 = false
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SlotReelColumn(symbols, target0, spin0, nearMissHint = null, stopMs = 420)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SlotReelColumn(symbols, target1, spin1, nearMissHint = null, stopMs = 720)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SlotReelColumn(symbols, target2, spin2, nearMissHint = nearMissFor2, stopMs = 1100)
        }
    }
}

@Composable
private fun SlotReelColumn(
    symbols: List<String>,
    target: String,
    spinning: Boolean,
    nearMissHint: String?,
    stopMs: Int
) {
    val itemH = 48.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val itemPx = with(density) { itemH.toPx() }
    val loop = symbols.size
    val loopPx = itemPx * loop
    val offset = remember { Animatable(0f) }

    fun sym(i: Int) = symbols[((i % loop) + loop) % loop]
    val targetIdx = symbols.indexOf(target).coerceAtLeast(0)
    val nearIdx = nearMissHint?.let { symbols.indexOf(it).coerceAtLeast(0) }

    LaunchedEffect(spinning, target, nearMissHint) {
        if (spinning) {
            while (true) {
                val start = offset.value
                offset.animateTo(
                    targetValue = start - loopPx,
                    animationSpec = tween(durationMillis = 280, easing = LinearEasing)
                )
                // 保持 offset 在合理範圍，避免累積誤差
                val wrapped = ((offset.value % loopPx) + loopPx) % loopPx - loopPx
                offset.snapTo(wrapped)
            }
        } else {
            // 停輪時對齊到整數格：中線格子剛好顯示 idx
            suspend fun landOn(idx: Int, duration: Int) {
                val curPos = -offset.value / itemPx
                val curFloor = kotlin.math.floor(curPos.toDouble()).toInt()
                val curSym = ((curFloor % loop) + loop) % loop
                var steps = (idx - curSym + loop) % loop
                if (steps < 3) steps += loop
                val aligned = -((curFloor + steps).toFloat()) * itemPx
                offset.animateTo(
                    targetValue = aligned,
                    animationSpec = tween(durationMillis = duration, easing = FastOutSlowInEasing)
                )
                offset.snapTo(aligned)
            }
            if (nearIdx != null && nearIdx != targetIdx) {
                landOn(nearIdx, (stopMs * 0.65f).toInt().coerceAtLeast(280))
                delay(180)
                landOn(targetIdx, 320)
            } else {
                landOn(targetIdx, stopMs)
            }
        }
    }

    // 整數格時 frac≈0，符號垂直落在中線窗格
    val pos = -offset.value / itemPx
    val baseIndex = kotlin.math.floor(pos.toDouble()).toInt()
    val frac = (pos - baseIndex).toFloat()

    Box(
        modifier = Modifier
            .width(64.dp)
            .height(itemH * 3)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = -frac * itemPx },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 多畫一格供滾動過渡；k=0 對應中線窗格
            for (k in -1..2) {
                Box(
                    modifier = Modifier
                        .height(itemH)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = sym(baseIndex + k),
                        fontSize = if (k == 0) 30.sp else 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (k == 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemH)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(itemH)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(itemH)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
        )
    }
}



// ---------- 設定子頁 ----------

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val export by vm.exportText.collectAsStateAware()
    val wallets by vm.wallets.collectAsStateAware()
    val rates by vm.walletRates.collectAsStateAware()
    val tasks by vm.tasks.collectAsStateAware()
    val recentTx by vm.recentTx.collectAsStateAware()
    val courses by vm.courses.collectAsStateAware()
    val calendarEvents by vm.calendarEvents.collectAsStateAware()
    val notificationsOn by vm.notificationsEnabled.collectAsStateAware()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var expandTx by remember { mutableStateOf(false) }
    var expandHistory by remember { mutableStateOf(false) }
    var expandRates by remember { mutableStateOf(false) }
    var expandCourses by remember { mutableStateOf(false) }
    var expandEvents by remember { mutableStateOf(false) }
    var showAllTx by remember { mutableStateOf(false) }
    var showAllHist by remember { mutableStateOf(false) }
    var txFilter by remember { mutableStateOf("ALL") }
    var wipeConfirm by remember { mutableStateOf(false) }
    var wipeText by remember { mutableStateOf("") }
    var courseSheet by remember { mutableStateOf<CourseEntity?>(null) }
    var showNewCourse by remember { mutableStateOf(false) }

    fun walletName(id: String?): String =
        id?.let { wallets.find { w -> w.id == it }?.name ?: it } ?: "—"

    ScreenColumn {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("通知", fontWeight = FontWeight.Medium)
                Text(
                    "抽獎冷卻、任務、公益、課表",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = notificationsOn,
                onCheckedChange = { vm.setNotificationsEnabled(it) }
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            QuietButton("開啟精準鬧鐘權限") {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                }
                runCatching { context.startActivity(intent) }
            }
        }

        SettingsCollapseHeader("近期交易", expandTx) { expandTx = !expandTx }
        if (expandTx) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "ALL" to "全部",
                    "INCOME" to "收入",
                    "LOAN" to "借貸",
                    "DONATION" to "公益",
                    "OTHER" to "其他"
                ).forEach { (key, labelTx) ->
                    FilterChip(
                        selected = txFilter == key,
                        onClick = { txFilter = key },
                        label = { Text(labelTx) }
                    )
                }
            }
            val filtered = recentTx.filter { txMatchesFilter(it.type, txFilter) }.take(20)
            if (filtered.isEmpty()) {
                Text("尚無交易", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            } else {
                val visible = if (showAllTx) filtered else filtered.take(5)
                visible.forEach { tx ->
                    SettingsTxRow(tx) { id -> walletName(id) }
                }
                if (filtered.size > 5) {
                    QuietButton(if (showAllTx) "只顯示 5 筆" else "顯示更多") {
                        showAllTx = !showAllTx
                    }
                }
            }
        }

        SettingsCollapseHeader("任務歷史", expandHistory) { expandHistory = !expandHistory }
        if (expandHistory) {
            val history = tasks.filter { it.status != TaskStatus.OPEN }.take(30)
            if (history.isEmpty()) {
                Text("尚無歷史任務", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val visibleHist = if (showAllHist) history else history.take(5)
                visibleHist.forEach { SettingsHistoryRow(it) }
                if (history.size > 5) {
                    QuietButton(if (showAllHist) "只顯示 5 筆" else "顯示更多") {
                        showAllHist = !showAllHist
                    }
                }
            }
        }

        SettingsCollapseHeader("借貸利率", expandRates) { expandRates = !expandRates }
        if (expandRates) {
            rates.filter { it.walletId != WalletCodes.CENTRAL }.forEach { r ->
                RateAutoSaveRow(
                    name = wallets.find { it.id == r.walletId }?.name ?: r.walletId,
                    walletId = r.walletId,
                    rateBp = r.monthlyRateBp,
                    onSave = { pct -> vm.updateRate(r.walletId, pct, notify = false) }
                )
            }
        }

        SettingsCollapseHeader("課程清單", expandCourses) { expandCourses = !expandCourses }
        if (expandCourses) {
            QuietButton("新增課程") { showNewCourse = true }
            if (courses.isEmpty()) {
                Text("尚無課程", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val byWeekday = courses.groupBy { it.weekday }
                (1..7).forEach { day ->
                    val dayCourses = byWeekday[day]?.sortedBy { it.periodStart }.orEmpty()
                    if (dayCourses.isEmpty()) return@forEach
                    val dayLabel = com.gino.mywallet.domain.PeriodTimes.weekdayLabel(day)
                    Text(
                        "週$dayLabel",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    dayCourses.forEach { course ->
                        val time = com.gino.mywallet.domain.PeriodTimes.formatPeriodRange(
                            course.periodStart,
                            course.periodEnd
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(course.title, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text(
                                    "$time · ${course.location}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row {
                                TextButton(onClick = { courseSheet = course }) {
                                    Text("編輯", fontSize = 12.sp)
                                }
                                TextButton(onClick = { vm.deleteCourse(course.id) }) {
                                    Text("刪除", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        SettingsCollapseHeader("行程管理", expandEvents) { expandEvents = !expandEvents }
        if (expandEvents) {
            val sortedEvents = calendarEvents.sortedWith(
                compareBy<CalendarEventEntity> { it.dateYmd }.thenBy { it.timeMinutes }
            )
            if (sortedEvents.isEmpty()) {
                Text("尚無行程", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                sortedEvents.forEach { event ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(event.title, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(
                                buildString {
                                    append(event.dateYmd)
                                    event.timeLabel()?.let { append(" · $it") }
                                    if (event.location.isNotBlank()) append(" · ${event.location}")
                                    if (event.note.isNotBlank()) append(" · ${event.note}")
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { vm.deleteCalendarEvent(event.id) }) {
                            Text("刪除", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        SectionTitle("匯出")
        PrimaryButton("匯出狀態報告") { vm.export() }
        export?.let { text ->
            OutlinedTextField(
                value = text,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth().height(160.dp),
                readOnly = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton("複製") {
                    clipboard.setText(AnnotatedString(text))
                }
                QuietButton("分享") {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(intent, "匯出報告"))
                }
                QuietButton("關閉") { vm.clearExport() }
            }
            Text("可貼到 Cursor／其他 LLM，請其產出 mywallet-cmd 指令", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        CommandConsoleSection(vm = vm, clipboard = clipboard)

        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("危險區域", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            Text("清空後無法復原", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            QuietButton("清空個人資料…") { wipeConfirm = true }
        }

        Text(
            "版本 ${com.gino.mywallet.BuildConfig.VERSION_NAME}（${com.gino.mywallet.BuildConfig.VERSION_CODE}）",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 8.dp)
        )
    }

    if (wipeConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { wipeConfirm = false },
            title = { Text("清空個人資料") },
            text = {
                Column {
                    Text("將重置錢包、借據、任務、抽獎與通行證至出廠狀態，不可復原。請輸入「清空」確認。")
                    OutlinedTextField(
                        value = wipeText,
                        onValueChange = { wipeText = it },
                        label = { Text("輸入 清空") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        if (wipeText == "清空") {
                            vm.wipeData()
                            wipeConfirm = false
                            wipeText = ""
                        }
                    }
                ) { Text("確定清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { wipeConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showNewCourse) {
        CourseFormSheet(
            vm = vm,
            editing = null,
            defaultWeekday = LocalDate.now().dayOfWeek.value,
            onDismiss = { showNewCourse = false }
        )
    }
    courseSheet?.let { editing ->
        CourseFormSheet(
            vm = vm,
            editing = editing,
            defaultWeekday = editing.weekday,
            onDismiss = { courseSheet = null }
        )
    }
}

@Composable
private fun CommandConsoleSection(
    vm: AppViewModel,
    clipboard: androidx.compose.ui.platform.ClipboardManager
) {
    val input by vm.commandInput.collectAsStateAware()
    val preview by vm.commandPreview.collectAsStateAware()
    val resultText by vm.commandResultText.collectAsStateAware()
    val pendingReplace by vm.pendingReplaceAll.collectAsStateAware()
    var expandPrompt by remember { mutableStateOf(false) }

    SectionTitle("指令控制台")
    Text(
        "貼上 LLM 產出的 mywallet-cmd JSON，批次建立任務／課表／行程",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(
        value = input,
        onValueChange = { vm.setCommandInput(it) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
        label = { Text("指令（JSON 或 ```mywallet-cmd 區塊）") },
        minLines = 5
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QuietButton("貼上") {
            clipboard.getText()?.text?.let { vm.setCommandInput(it) }
        }
        QuietButton("清空") { vm.clearCommandConsole() }
        PrimaryButton("執行") { vm.applyCommands() }
    }

    preview?.let { p ->
        Text(
            "結果：成功 ${p.applied}／失敗 ${p.failed}（${p.mode}）",
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = if (p.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        p.results.take(12).forEach { item ->
            Text(
                "[${item.index}] ${item.op} · ${item.status} · ${item.title ?: ""} — ${item.message}",
                fontSize = 11.sp,
                color = when (item.status) {
                    "error" -> MaterialTheme.colorScheme.error
                    "preview" -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }
        if (p.results.size > 12) {
            Text("…另有 ${p.results.size - 12} 筆", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    resultText?.let { text ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton("複製結果給 LLM") {
                clipboard.setText(AnnotatedString(text))
            }
        }
    }

    QuietButton(if (expandPrompt) "收合 LLM 提示詞" else "複製 LLM 提示詞…") {
        expandPrompt = !expandPrompt
    }
    if (expandPrompt) {
        Text(
            "System Prompt（可貼到 Cursor／ChatGPT 自訂指令）",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        QuietButton("複製 System Prompt") {
            clipboard.setText(AnnotatedString(vm.llmSystemPrompt()))
        }
        QuietButton("複製 User 範本") {
            clipboard.setText(AnnotatedString(vm.llmUserTemplate()))
        }
    }

    pendingReplace?.let {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.cancelReplaceAll() },
            title = { Text("確認替換課表") },
            text = {
                Text("指令含 course.replace_all，將清空現有課表再寫入新課表。確定執行？")
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.confirmReplaceAll() }) {
                    Text("確定替換")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { vm.cancelReplaceAll() }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun SettingsCollapseHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(if (expanded) "收合" else "展開", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun SettingsTxRow(tx: com.gino.mywallet.data.entity.TransactionEntity, walletName: (String?) -> String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(txTypeLabel(tx.type), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            val route = when {
                tx.fromWalletId != null || tx.toWalletId != null ->
                    "${walletName(tx.fromWalletId)}→${walletName(tx.toWalletId)}"
                else -> ""
            }
            val note = tx.note.ifBlank { route }
            Text(
                note + " · " + epochToLocalDate(tx.createdAt).format(DateTimeFormatter.ofPattern("MM/dd")),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (tx.amountCents != 0L) {
            Text(formatTwd(tx.amountCents), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SettingsHistoryRow(task: TaskEntity) {
    val statusColor = when (task.status) {
        TaskStatus.DONE -> MaterialTheme.colorScheme.primary
        TaskStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val whenText = task.resolvedAt?.let {
        epochToLocalDate(it).format(DateTimeFormatter.ofPattern("MM/dd"))
    } ?: ""
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "[${task.category}] ${task.title}",
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            listOfNotNull(task.status, whenText.ifBlank { null }).joinToString(" · "),
            fontSize = 11.sp,
            color = statusColor
        )
    }
}

private fun txMatchesFilter(type: String, filter: String): Boolean = when (filter) {
    "ALL" -> true
    "INCOME" -> type.startsWith("INCOME") || type == "TRANSFER"
    "LOAN" -> type.startsWith("LOAN") || type.startsWith("CENTRAL_DEBT")
    "DONATION" -> type == "DONATION" || type == "CHARITY_TAX"
    "OTHER" -> !(type.startsWith("INCOME") || type == "TRANSFER" || type.startsWith("LOAN") ||
        type.startsWith("CENTRAL_DEBT") ||
        type == "DONATION" || type == "CHARITY_TAX")
    else -> true
}
