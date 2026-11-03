package com.gino.mywallet.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gino.mywallet.data.entity.StockHoldingEntity
import com.gino.mywallet.data.entity.StockMarkets
import com.gino.mywallet.data.entity.StockWatchEntity
import com.gino.mywallet.ui.AppViewModel
import com.gino.mywallet.ui.components.PrimaryButton
import com.gino.mywallet.ui.formatTwd
import com.gino.mywallet.ui.invest.InvestViewModel
import com.gino.mywallet.ui.invest.fmtShares
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------- 投資主頁 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvestScreen(
    vm: AppViewModel,
    onOpenStock: (String) -> Unit
) {
    val investVm: InvestViewModel = viewModel()
    val holdings by investVm.holdings.collectAsStateWithLifecycle()
    val watchlist by investVm.watchlist.collectAsStateWithLifecycle()
    val cash by investVm.investCashCents.collectAsStateWithLifecycle()
    val refreshing by investVm.refreshing.collectAsStateWithLifecycle()
    val message by investVm.message.collectAsStateWithLifecycle()

    // 借用全域 Snackbar
    LaunchedEffect(message?.id) {
        message?.let {
            vm.notify(it.text)
            investVm.clearMessage()
        }
    }
    LaunchedEffect(Unit) { investVm.refreshQuotes() }

    var tab by remember { mutableIntStateOf(0) }
    var addHoldingFor by remember { mutableStateOf<Pair<String, String>?>(null) } // symbol+market 預填
    var showAddHolding by remember { mutableStateOf(false) }
    var sellTarget by remember { mutableStateOf<StockHoldingEntity?>(null) }
    var editWatch by remember { mutableStateOf<StockWatchEntity?>(null) }
    var showAddWatch by remember { mutableStateOf(false) }
    var showCashDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("總覽", "已買", "觀察", "比較").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = tab == i,
                        onClick = { tab = i },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 4)
                    ) { Text(label, fontSize = 13.sp) }
                }
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (tab) {
                0 -> InvestOverviewTab(
                    holdings = holdings,
                    cashCents = cash,
                    refreshing = refreshing,
                    onRefresh = { investVm.refreshQuotes(force = true) },
                    onEditCash = { showCashDialog = true }
                )
                1 -> HoldingsTab(
                    holdings = holdings,
                    onAdd = {
                        addHoldingFor = null
                        showAddHolding = true
                    },
                    onOpen = onOpenStock,
                    onBuyMore = { h ->
                        addHoldingFor = h.symbol to h.market
                        showAddHolding = true
                    },
                    onSell = { sellTarget = it },
                    onDelete = { investVm.deleteHolding(it.symbol) }
                )
                2 -> WatchlistTab(
                    watchlist = watchlist,
                    onAdd = {
                        editWatch = null
                        showAddWatch = true
                    },
                    onOpen = onOpenStock,
                    onEdit = {
                        editWatch = it
                        showAddWatch = true
                    },
                    onBuy = { w ->
                        addHoldingFor = w.symbol to w.market
                        showAddHolding = true
                    },
                    onToggleWantBuy = { investVm.toggleWantBuy(it.symbol) },
                    onDelete = { investVm.deleteWatch(it.symbol) }
                )
                else -> CompareTab(
                    investVm = investVm,
                    symbols = (holdings.map { it.symbol } + watchlist.map { it.symbol }).distinct()
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAddHolding) {
        AddHoldingDialog(
            prefillSymbol = addHoldingFor?.first,
            prefillMarket = addHoldingFor?.second,
            onConfirm = { symbol, market, shares, price, buyWithCash ->
                investVm.addHolding(symbol, market, shares, price, buyWithCash)
                showAddHolding = false
            },
            onDismiss = { showAddHolding = false }
        )
    }
    sellTarget?.let { h ->
        SellHoldingDialog(
            holding = h,
            onConfirm = { shares, price ->
                investVm.sellHolding(h.symbol, shares, price)
                sellTarget = null
            },
            onDismiss = { sellTarget = null }
        )
    }
    if (showAddWatch) {
        AddWatchDialog(
            editing = editWatch,
            onConfirm = { symbol, market, note, target, wantBuy ->
                investVm.addWatch(symbol, market, note, target, wantBuy)
                showAddWatch = false
            },
            onDismiss = { showAddWatch = false }
        )
    }
    if (showCashDialog) {
        CashAdjustDialog(
            currentCents = cash,
            onConfirm = {
                investVm.adjustCash(it)
                showCashDialog = false
            },
            onDismiss = { showCashDialog = false }
        )
    }
}

// ---------- 總覽 ----------

@Composable
private fun InvestOverviewTab(
    holdings: List<StockHoldingEntity>,
    cashCents: Long,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onEditCash: () -> Unit
) {
    val marketValue = holdings.sumOf { it.marketValueCents() }
    val cost = holdings.sumOf { it.costCents }
    val pnl = marketValue - cost
    val lastQuotedAt = holdings.maxOfOrNull { it.lastQuotedAt } ?: 0L

    Text("投資總額", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        formatTwd(cashCents + marketValue),
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace
    )

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewRow("現金", formatTwd(cashCents), action = "平帳", onAction = onEditCash)
            OverviewRow("持股市值", formatTwd(marketValue))
            OverviewRow(
                "未實現損益",
                formatTwd(pnl) + pnlPctSuffix(pnl, cost),
                valueColor = pnlColor(pnl)
            )
            if (lastQuotedAt > 0) {
                Text(
                    Instant.ofEpochMilli(lastQuotedAt)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("MM/dd HH:mm")),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (refreshing) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("更新中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        PrimaryButton("更新報價") { onRefresh() }
    }
}

@Composable
private fun OverviewRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = valueColor ?: MaterialTheme.colorScheme.onSurface
            )
            if (action != null && onAction != null) {
                TextButton(onClick = onAction) { Text(action, fontSize = 12.sp) }
            }
        }
    }
}

// ---------- 已買 ----------

@Composable
private fun HoldingsTab(
    holdings: List<StockHoldingEntity>,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onBuyMore: (StockHoldingEntity) -> Unit,
    onSell: (StockHoldingEntity) -> Unit,
    onDelete: (StockHoldingEntity) -> Unit
) {
    PrimaryButton("新增持股") { onAdd() }
    if (holdings.isEmpty()) {
        Text("尚無持股", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    holdings.forEach { h ->
        val mv = h.marketValueCents()
        val pnl = mv - h.costCents
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(h.symbol) }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${h.symbol}  ${h.name}".trim(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        formatTwd(mv),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Text(
                    "${fmtPrice(h.lastPriceCents)} × ${fmtShares(h.sharesMilli)} · 成本 ${formatTwd(h.costCents)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "損益 ${formatTwd(pnl)}${pnlPctSuffix(pnl, h.costCents)}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = pnlColor(pnl)
                    )
                    Row {
                        TextButton(onClick = { onBuyMore(h) }) { Text("買", fontSize = 12.sp) }
                        TextButton(onClick = { onSell(h) }) { Text("賣", fontSize = 12.sp) }
                        TextButton(onClick = { onDelete(h) }) {
                            Text("移除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

// ---------- 觀察 ----------

@Composable
private fun WatchlistTab(
    watchlist: List<StockWatchEntity>,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onEdit: (StockWatchEntity) -> Unit,
    onBuy: (StockWatchEntity) -> Unit,
    onToggleWantBuy: (StockWatchEntity) -> Unit,
    onDelete: (StockWatchEntity) -> Unit
) {
    PrimaryButton("加入觀察") { onAdd() }
    if (watchlist.isEmpty()) {
        Text("尚無觀察股票", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    watchlist.forEach { w ->
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(w.symbol) }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${w.symbol}  ${w.name}".trim(),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        if (w.wantBuy) {
                            Spacer(Modifier.padding(start = 6.dp))
                            Text(
                                "待買",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        if (w.lastPriceCents > 0) fmtPrice(w.lastPriceCents) else "—",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                val sub = buildString {
                    if (w.targetPriceCents > 0) append("目標 ${fmtPrice(w.targetPriceCents)}")
                    if (w.note.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(w.note)
                    }
                }
                if (sub.isNotBlank()) {
                    Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (w.targetPriceCents > 0 && w.lastPriceCents in 1..w.targetPriceCents) {
                    Text(
                        "已達目標價",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { onToggleWantBuy(w) }) {
                        Text(if (w.wantBuy) "取消待買" else "待買", fontSize = 12.sp)
                    }
                    TextButton(onClick = { onBuy(w) }) { Text("買入", fontSize = 12.sp) }
                    TextButton(onClick = { onEdit(w) }) { Text("編輯", fontSize = 12.sp) }
                    TextButton(onClick = { onDelete(w) }) {
                        Text("刪除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

// ---------- 比較 ----------

@Composable
private fun CompareTab(
    investVm: InvestViewModel,
    symbols: List<String>
) {
    val compare by investVm.compare.collectAsStateWithLifecycle()
    var symbolA by remember { mutableStateOf<String?>(null) }
    var symbolB by remember { mutableStateOf<String?>(null) }

    if (symbols.size < 2) {
        Text("至少需要兩檔", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    LaunchedEffect(symbolA, symbolB) {
        val a = symbolA
        val b = symbolB
        if (a != null && b != null && a != b) {
            investVm.loadCompare(a, b)
        } else {
            investVm.clearCompare()
        }
    }

    SymbolChipRow("A", symbols, symbolA) { symbolA = it }
    SymbolChipRow("B", symbols, symbolB) { symbolB = it }

    compare?.let { c ->
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendDotSmall(MaterialTheme.colorScheme.primary, "${c.a.symbol} ${fmtPct(c.a.changePct)}")
            LegendDotSmall(MaterialTheme.colorScheme.tertiary, "${c.b.symbol} ${fmtPct(c.b.changePct)}")
        }
        DualLineChart(a = c.a.normalizedPct, b = c.b.normalizedPct)
        CompareIndicatorTable(c)
    }
}

@Composable
private fun SymbolChipRow(
    label: String,
    symbols: List<String>,
    selected: String?,
    onSelect: (String) -> Unit
) {
    Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        symbols.forEach { s ->
            FilterChip(
                selected = selected == s,
                onClick = { onSelect(s) },
                label = { Text(s, fontSize = 12.sp) }
            )
        }
    }
}

@Composable
private fun CompareIndicatorTable(c: com.gino.mywallet.ui.invest.CompareUi) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CompareRow("", c.a.symbol, c.b.symbol, bold = true)
            CompareRow(
                "現價",
                fmtPrice(c.a.lastPriceCents),
                fmtPrice(c.b.lastPriceCents)
            )
            CompareRow("期間漲跌", fmtPct(c.a.changePct), fmtPct(c.b.changePct))
            CompareRow(
                "RSI(14)",
                c.a.latestRsi?.let { "%.1f".format(it) + rsiTag(it) } ?: "—",
                c.b.latestRsi?.let { "%.1f".format(it) + rsiTag(it) } ?: "—"
            )
            CompareRow(
                "MACD DIF",
                c.a.latestDif?.let { "%.2f".format(it) } ?: "—",
                c.b.latestDif?.let { "%.2f".format(it) } ?: "—"
            )
            CompareRow(
                "MACD DEA",
                c.a.latestDea?.let { "%.2f".format(it) } ?: "—",
                c.b.latestDea?.let { "%.2f".format(it) } ?: "—"
            )
            CompareRow(
                "MACD 柱",
                c.a.latestHistogram?.let { "%.2f".format(it) + macdTag(it) } ?: "—",
                c.b.latestHistogram?.let { "%.2f".format(it) + macdTag(it) } ?: "—"
            )
        }
    }
}

@Composable
private fun CompareRow(label: String, a: String, b: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            Modifier.weight(0.8f),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            a,
            Modifier.weight(1f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            b,
            Modifier.weight(1f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// ---------- 對話框 ----------

@Composable
private fun AddHoldingDialog(
    prefillSymbol: String?,
    prefillMarket: String?,
    onConfirm: (symbol: String, market: String, shares: String, price: String, buyWithCash: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var symbol by remember { mutableStateOf(prefillSymbol.orEmpty()) }
    var market by remember { mutableStateOf(prefillMarket ?: StockMarkets.TW) }
    var shares by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var buyWithCash by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (prefillSymbol == null) "新增持股" else "買入 $prefillSymbol") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (prefillSymbol == null) {
                    OutlinedTextField(
                        value = symbol,
                        onValueChange = { symbol = it },
                        label = { Text("代號") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    MarketChips(market) { market = it }
                }
                OutlinedTextField(
                    value = shares,
                    onValueChange = { shares = it },
                    label = { Text("股數") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("每股價格（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { buyWithCash = !buyWithCash }
                ) {
                    Checkbox(checked = buyWithCash, onCheckedChange = { buyWithCash = it })
                    Text("從現金扣款", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(symbol, market, shares, price, buyWithCash) },
                enabled = symbol.isNotBlank() && shares.isNotBlank() && price.isNotBlank()
            ) { Text("確定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SellHoldingDialog(
    holding: StockHoldingEntity,
    onConfirm: (shares: String, price: String) -> Unit,
    onDismiss: () -> Unit
) {
    var shares by remember { mutableStateOf(fmtShares(holding.sharesMilli)) }
    var price by remember {
        mutableStateOf(
            if (holding.lastPriceCents > 0) "%.2f".format(holding.lastPriceCents / 100.0) else ""
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("賣出 ${holding.symbol}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "現有 ${fmtShares(holding.sharesMilli)} 股",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = shares,
                    onValueChange = { shares = it },
                    label = { Text("賣出股數") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("每股價格（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(shares, price) },
                enabled = shares.isNotBlank() && price.isNotBlank()
            ) { Text("賣出") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AddWatchDialog(
    editing: StockWatchEntity?,
    onConfirm: (symbol: String, market: String, note: String, targetPrice: String, wantBuy: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var symbol by remember(editing) { mutableStateOf(editing?.symbol.orEmpty()) }
    var market by remember(editing) { mutableStateOf(editing?.market ?: StockMarkets.TW) }
    var note by remember(editing) { mutableStateOf(editing?.note.orEmpty()) }
    var target by remember(editing) {
        mutableStateOf(
            editing?.targetPriceCents?.takeIf { it > 0 }
                ?.let { "%.2f".format(it / 100.0) }
                .orEmpty()
        )
    }
    var wantBuy by remember(editing) { mutableStateOf(editing?.wantBuy ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) "加入觀察" else "編輯 ${editing.symbol}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editing == null) {
                    OutlinedTextField(
                        value = symbol,
                        onValueChange = { symbol = it },
                        label = { Text("代號") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    MarketChips(market) { market = it }
                }
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("目標價") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("備註") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { wantBuy = !wantBuy }
                ) {
                    Checkbox(checked = wantBuy, onCheckedChange = { wantBuy = it })
                    Text("待買", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(symbol, market, note, target, wantBuy) },
                enabled = symbol.isNotBlank()
            ) { Text("確定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CashAdjustDialog(
    currentCents: Long,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var target by remember { mutableStateOf("%.2f".format(currentCents / 100.0)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("平帳 · 現金") },
        text = {
            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                label = { Text("目標餘額（元）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(target) }) { Text("套用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun MarketChips(market: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = market == StockMarkets.TW,
            onClick = { onSelect(StockMarkets.TW) },
            label = { Text("台股") }
        )
        FilterChip(
            selected = market == StockMarkets.US,
            onClick = { onSelect(StockMarkets.US) },
            label = { Text("美股") }
        )
    }
}

// ---------- 共用格式 ----------

internal fun fmtPrice(cents: Long): String {
    val v = cents / 100.0
    return if (cents % 100 == 0L) "$${cents / 100}" else "$%.2f".format(v)
}

internal fun fmtPct(pct: Double): String =
    (if (pct >= 0) "+" else "") + "%.1f%%".format(pct)

internal fun pnlPctSuffix(pnl: Long, cost: Long): String =
    if (cost > 0) "（${fmtPct(pnl * 100.0 / cost)}）" else ""

@Composable
internal fun pnlColor(pnl: Long): androidx.compose.ui.graphics.Color = when {
    pnl > 0 -> MaterialTheme.colorScheme.error // 台股慣例：紅漲
    pnl < 0 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurface
}

internal fun rsiTag(rsi: Double): String = when {
    rsi >= 70 -> " 超買"
    rsi <= 30 -> " 超賣"
    else -> ""
}

internal fun macdTag(hist: Double): String = when {
    hist > 0 -> " 多方"
    hist < 0 -> " 空方"
    else -> ""
}
