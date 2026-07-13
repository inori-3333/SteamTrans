package com.steamtrans.ledger.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AddCard
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.steamtrans.ledger.data.AccountType
import com.steamtrans.ledger.data.EventType
import com.steamtrans.ledger.data.EventView
import com.steamtrans.ledger.data.ItemType
import com.steamtrans.ledger.data.LedgerSnapshot
import com.steamtrans.ledger.data.PortfolioSnapshotEntity
import com.steamtrans.ledger.formatDateTime
import com.steamtrans.ledger.formatMoney
import com.steamtrans.ledger.parseMoney
import com.steamtrans.ledger.ui.components.Amount
import com.steamtrans.ledger.ui.components.ScreenHeading
import com.steamtrans.ledger.ui.components.SectionHeading
import com.steamtrans.ledger.ui.components.StatusPill
import com.steamtrans.ledger.ui.theme.AdjustAmber
import com.steamtrans.ledger.ui.theme.FiatGold
import com.steamtrans.ledger.ui.theme.Gain
import com.steamtrans.ledger.ui.theme.Loss
import com.steamtrans.ledger.ui.theme.RaisedBlue
import com.steamtrans.ledger.ui.theme.SteamBlue
import com.steamtrans.ledger.ui.theme.TextSecondary
import com.steamtrans.ledger.ui.theme.Warning
import com.steamtrans.ledger.ui.theme.WalletBlue
import com.steamtrans.ledger.ui.theme.categoryColor
import kotlin.math.max

private enum class TrendRange(val label: String, val days: Int?) { DAYS_7("7 天", 7), DAYS_30("30 天", 30), ALL("全部", null) }

@Composable
fun OverviewScreen(
    snapshot: LedgerSnapshot,
    portfolioSnapshots: List<PortfolioSnapshotEntity>,
    onOpenSettings: () -> Unit,
    onOpenLedger: (AccountType?) -> Unit,
    onAdjustAccount: (AccountType, Long, String) -> Unit
) {
    var adjusting by remember { mutableStateOf<AccountType?>(null) }
    var trendRange by remember { mutableStateOf(TrendRange.DAYS_30) }
    val staleCutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    val staleCount = snapshot.holdings.count { it.quote != null && it.quote.timestamp < staleCutoff }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 112.dp)
    ) {
        item {
            ScreenHeading("Steam 账本", "双账户交易工作台") {
                IconButton(onOpenSettings) { Icon(Icons.Outlined.Settings, "设置") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccountPanel(
                    label = "人民币资金池",
                    balance = snapshot.fiatBalanceCents,
                    color = FiatGold,
                    modifier = Modifier.weight(1f),
                    onLedger = { onOpenLedger(AccountType.FIAT_CNY) },
                    onAdjust = { adjusting = AccountType.FIAT_CNY }
                )
                AccountPanel(
                    label = "Steam 钱包",
                    balance = snapshot.walletBalanceCents,
                    color = WalletBlue,
                    modifier = Modifier.weight(1f),
                    onLedger = { onOpenLedger(AccountType.STEAM_WALLET_CNY) },
                    onAdjust = { adjusting = AccountType.STEAM_WALLET_CNY }
                )
            }
        }
        if (snapshot.fiatBalanceCents < 0 || snapshot.walletBalanceCents < 0) {
            item {
                Notice(
                    "账户需要校准",
                    "余额允许为负以兼容补录，但建议通过余额调整补齐期初值。",
                    Warning,
                    Icons.Outlined.ErrorOutline
                )
            }
        }
        item {
            SectionHeading("持仓与市场", "两类成本始终分开")
            MetricSurface {
                MetricLine("人民币成本", snapshot.fiatHoldingCostCents, FiatGold)
                MetricLine("钱包成本", snapshot.walletHoldingCostCents, WalletBlue)
                Spacer(Modifier.height(13.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SmallMetric("Steam 挂牌总值", formatMoney(snapshot.marketGrossValueCents), Modifier.weight(1f))
                    SmallMetric("预计到手钱包", formatMoney(snapshot.marketNetValueCents), Modifier.weight(1f))
                }
                if (snapshot.unpricedHoldingCount > 0) {
                    Spacer(Modifier.height(13.dp))
                    StatusPill("${snapshot.unpricedHoldingCount} 项未定价", Warning, Icons.Outlined.ErrorOutline)
                }
            }
        }
        item {
            SectionHeading("已实现结果", "仅同账户比较")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PnlPanel("人民币", snapshot.fiatRealizedPnlCents, FiatGold, Modifier.weight(1f))
                PnlPanel("Steam 钱包", snapshot.walletRealizedPnlCents, WalletBlue, Modifier.weight(1f))
            }
            if (snapshot.crossAccountOutcomes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Surface(color = RaisedBlue, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("跨账户成交", style = MaterialTheme.typography.titleMedium)
                        Text("这些成交只展示投入与收入，不合并为利润。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        snapshot.crossAccountOutcomes.takeLast(3).forEach { outcome ->
                            val cost = buildList {
                                if (outcome.cost.fiatCents != 0L) add("人民币 ${formatMoney(outcome.cost.fiatCents)}")
                                if (outcome.cost.walletCents != 0L) add("钱包 ${formatMoney(outcome.cost.walletCents)}")
                            }.joinToString(" + ")
                            Text("$cost → ${outcome.proceedsAccount.label} ${formatMoney(outcome.proceedsCents)}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        item {
            SectionHeading("余额与估值趋势")
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                TrendRange.entries.forEach { range ->
                    androidx.compose.material3.FilterChip(
                        selected = trendRange == range,
                        onClick = { trendRange = range },
                        label = { Text(range.label) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            val cutoff = trendRange.days?.let { System.currentTimeMillis() - it * 24L * 60 * 60 * 1000 }
            TrendPanel(portfolioSnapshots.filter { cutoff == null || it.timestamp >= cutoff })
        }
        item {
            SectionHeading("持仓构成")
            Composition(snapshot)
        }
        if (snapshot.unpricedHoldingCount > 0 || staleCount > 0) {
            item {
                SectionHeading("需要留意")
                if (snapshot.unpricedHoldingCount > 0) Notice("缺少行情", "${snapshot.unpricedHoldingCount} 项持仓还没有市场价格，可在持仓页绑定或手动定价。", Warning, Icons.Outlined.ErrorOutline)
                if (staleCount > 0) Notice("行情已过期", "$staleCount 项行情超过 24 小时未更新，当前仍显示最后成功价格。", AdjustAmber, Icons.Outlined.History)
            }
        }
        item {
            SectionHeading("最近流水", "查看全部")
            if (snapshot.events.isEmpty()) {
                Text("还没有流水。点击右下角“记一笔”开始。", color = TextSecondary, modifier = Modifier.padding(vertical = 18.dp))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    snapshot.events.take(5).forEach { RecentEvent(it) }
                }
            }
        }
    }

    adjusting?.let { account ->
        BalanceDialog(
            account = account,
            current = snapshot.balance(account),
            onDismiss = { adjusting = null },
            onSave = { balance, note ->
                onAdjustAccount(account, balance, note)
                adjusting = null
            }
        )
    }
}

@Composable
private fun AccountPanel(
    label: String,
    balance: Long,
    color: Color,
    modifier: Modifier,
    onLedger: () -> Unit,
    onAdjust: () -> Unit
) {
    Surface(modifier, color = color.copy(alpha = 0.11f), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccountBalanceWallet, null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = color)
            }
            Spacer(Modifier.height(11.dp))
            Amount(balance, if (balance < 0) Loss else MaterialTheme.colorScheme.onSurface, large = true)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onLedger, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("流水") }
                TextButton(onClick = onAdjust, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("调整") }
            }
        }
    }
}

@Composable
private fun MetricSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun MetricLine(label: String, value: Long, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(99.dp)))
        Spacer(Modifier.width(9.dp))
        Text(label, color = TextSecondary, modifier = Modifier.weight(1f))
        Amount(value)
    }
}

@Composable
private fun SmallMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PnlPanel(label: String, pnl: Long, accent: Color, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(15.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = accent)
            Spacer(Modifier.height(8.dp))
            Amount(pnl, if (pnl >= 0) Gain else Loss, signed = true, large = true)
        }
    }
}

@Composable
private fun TrendPanel(points: List<PortfolioSnapshotEntity>) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            if (points.size < 2) {
                Column(Modifier.fillMaxWidth().height(150.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Outlined.ShowChart, null, tint = TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Text("至少两次账务或行情更新后生成趋势", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val values = points.flatMap { listOf(it.fiatBalanceCents, it.walletBalanceCents, it.marketNetCents) }
                val min = values.minOrNull() ?: 0
                val max = values.maxOrNull() ?: 1
                Canvas(Modifier.fillMaxWidth().height(154.dp)) {
                    fun drawSeries(color: Color, selector: (PortfolioSnapshotEntity) -> Long) {
                        val path = Path()
                        points.forEachIndexed { index, point ->
                            val x = if (points.size == 1) 0f else size.width * index / (points.size - 1)
                            val ratio = if (max == min) .5f else (selector(point) - min).toFloat() / (max - min).toFloat()
                            val y = size.height - ratio * size.height
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                    }
                    drawLine(color = RaisedBlue, start = Offset(0f, size.height / 2), end = Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx())
                    drawSeries(FiatGold) { it.fiatBalanceCents }
                    drawSeries(WalletBlue) { it.walletBalanceCents }
                    drawSeries(Gain) { it.marketNetCents }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Legend("人民币余额", FiatGold)
                    Legend("钱包余额", WalletBlue)
                    Legend("预计到手", Gain)
                }
            }
        }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, RoundedCornerShape(99.dp)))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

@Composable
private fun Composition(snapshot: LedgerSnapshot) {
    val groups = snapshot.holdings.groupBy { holding ->
        if (holding.quote == null) null else holding.item.type
    }.mapValues { (_, holdings) -> holdings.sumOf { it.marketNetValueCents ?: it.costCents } }
        .entries.sortedByDescending { it.value }
    val total = max(1L, groups.sumOf { it.value })
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (groups.isEmpty()) Text("暂无持仓", color = TextSecondary)
            groups.forEach { (type, value) ->
                val label = type?.label ?: "未绑定 / 无价格"
                val color = type?.let(::categoryColor) ?: Warning
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(formatMoney(value), style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(5.dp).background(RaisedBlue, RoundedCornerShape(99.dp))) {
                        Box(Modifier.fillMaxWidth(value.toFloat() / total).height(5.dp).background(color, RoundedCornerShape(99.dp)))
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(title: String, body: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        color = color.copy(alpha = .10f),
        shape = RoundedCornerShape(15.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = color)
                Spacer(Modifier.height(2.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun RecentEvent(view: EventView) {
    val event = view.event
    val color = when (event.type) {
        EventType.BUY -> com.steamtrans.ledger.ui.theme.BuyCoral
        EventType.SELL -> com.steamtrans.ledger.ui.theme.SellMint
        EventType.CONVERT -> com.steamtrans.ledger.ui.theme.ConvertPurple
        EventType.ACCOUNT_ADJUSTMENT -> AdjustAmber
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(color.copy(.14f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Icon(if (event.type == EventType.ACCOUNT_ADJUSTMENT) Icons.Outlined.AddCard else Icons.Outlined.ReceiptLong, null, tint = color, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("${event.type.label} · ${event.platform}", style = MaterialTheme.typography.titleMedium)
                Text("${event.accountType.label} · ${formatDateTime(event.timestamp)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            val gross = if (event.type == EventType.ACCOUNT_ADJUSTMENT) event.accountDeltaCents else view.lines.sumOf { it.quantity * it.unitPriceCents }
            Text(formatMoney(gross, event.type != EventType.CONVERT), color = if (gross < 0) Loss else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun BalanceDialog(account: AccountType, current: Long, onDismiss: () -> Unit, onSave: (Long, String) -> Unit) {
    var amount by remember(account, current) { mutableStateOf(java.math.BigDecimal.valueOf(current, 2).toPlainString()) }
    var note by remember(account) { mutableStateOf("余额校准") }
    val parsed = parseMoney(amount)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.AccountBalanceWallet, null) },
        title = { Text("调整${account.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("直接修改余额会永久生成一条调整流水。当前为 ${formatMoney(current)}。", color = TextSecondary)
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("调整后的余额") },
                    prefix = { Text("¥") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = amount.isNotBlank() && parsed == null,
                    supportingText = { if (amount.isNotBlank() && parsed == null) Text("请输入最多两位小数的金额") },
                    singleLine = true
                )
                OutlinedTextField(note, { note = it }, label = { Text("说明") }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { parsed?.let { onSave(it, note) } }, enabled = parsed != null && parsed != current) { Text("生成调整流水") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
