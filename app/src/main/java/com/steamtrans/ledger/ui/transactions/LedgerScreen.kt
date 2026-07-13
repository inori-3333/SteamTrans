package com.steamtrans.ledger.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.steamtrans.ledger.data.AccountType
import com.steamtrans.ledger.data.EventStatus
import com.steamtrans.ledger.data.EventType
import com.steamtrans.ledger.data.EventView
import com.steamtrans.ledger.data.ItemEntity
import com.steamtrans.ledger.data.LedgerSnapshot
import com.steamtrans.ledger.formatDateTime
import com.steamtrans.ledger.formatMoney
import com.steamtrans.ledger.ui.components.EmptyState
import com.steamtrans.ledger.ui.components.ScreenHeading
import com.steamtrans.ledger.ui.components.StatusPill
import com.steamtrans.ledger.ui.theme.AdjustAmber
import com.steamtrans.ledger.ui.theme.BuyCoral
import com.steamtrans.ledger.ui.theme.ConvertPurple
import com.steamtrans.ledger.ui.theme.FiatGold
import com.steamtrans.ledger.ui.theme.RaisedBlue
import com.steamtrans.ledger.ui.theme.SellMint
import com.steamtrans.ledger.ui.theme.TextSecondary
import com.steamtrans.ledger.ui.theme.WalletBlue

private enum class DateRange(val label: String, val days: Int?) { DAYS_7("7 天", 7), DAYS_30("30 天", 30), ALL("全部日期", null) }

@Composable
fun LedgerScreen(
    snapshot: LedgerSnapshot,
    itemsById: Map<Long, ItemEntity>,
    initialAccount: AccountType? = null,
    onEdit: (Long) -> Unit,
    onSetVoided: (Long, Boolean) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<EventType?>(null) }
    var status by remember { mutableStateOf<EventStatus?>(EventStatus.ACTIVE) }
    var account by remember(initialAccount) { mutableStateOf(initialAccount) }
    var platform by remember { mutableStateOf<String?>(null) }
    var dateRange by remember { mutableStateOf(DateRange.ALL) }
    var typeMenu by remember { mutableStateOf(false) }
    var platformMenu by remember { mutableStateOf(false) }
    var dateMenu by remember { mutableStateOf(false) }
    var pendingVoid by remember { mutableStateOf<EventView?>(null) }

    val platforms = snapshot.events.map { it.event.platform }.filter { it.isNotBlank() }.distinct().sorted()
    val cutoff = dateRange.days?.let { System.currentTimeMillis() - it * 24L * 60 * 60 * 1000 }
    val filtered = snapshot.events.filter { view ->
        val event = view.event
        val itemNames = view.lines.mapNotNull { itemsById[it.itemId]?.name }
        (query.isBlank() || event.note.contains(query, true) || event.platform.contains(query, true) || itemNames.any { it.contains(query, true) }) &&
            (type == null || event.type == type) &&
            (status == null || event.status == status) &&
            (account == null || event.accountType == account) &&
            (platform == null || event.platform == platform) &&
            (cutoff == null || event.timestamp >= cutoff)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeading("流水", "任何修改都会重放后续账务")
        OutlinedTextField(
            query,
            { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            placeholder = { Text("搜索物品、平台或备注") },
            singleLine = true,
            shape = RoundedCornerShape(15.dp)
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box {
                FilterChip(selected = type != null, onClick = { typeMenu = true }, label = { Text(type?.label ?: "类型") }, leadingIcon = { Icon(Icons.Outlined.FilterAlt, null, Modifier.size(17.dp)) })
                DropdownMenu(typeMenu, { typeMenu = false }) {
                    DropdownMenuItem({ Text("全部类型") }, onClick = { type = null; typeMenu = false })
                    EventType.entries.forEach { value -> DropdownMenuItem({ Text(value.label) }, onClick = { type = value; typeMenu = false }) }
                }
            }
            FilterChip(selected = status == EventStatus.ACTIVE, onClick = { status = if (status == EventStatus.ACTIVE) null else EventStatus.ACTIVE }, label = { Text("有效") })
            FilterChip(selected = status == EventStatus.VOIDED, onClick = { status = if (status == EventStatus.VOIDED) null else EventStatus.VOIDED }, label = { Text("已作废") })
            FilterChip(selected = account == AccountType.FIAT_CNY, onClick = { account = if (account == AccountType.FIAT_CNY) null else AccountType.FIAT_CNY }, label = { Text("人民币") })
            FilterChip(selected = account == AccountType.STEAM_WALLET_CNY, onClick = { account = if (account == AccountType.STEAM_WALLET_CNY) null else AccountType.STEAM_WALLET_CNY }, label = { Text("Steam 钱包") })
            Box {
                FilterChip(selected = platform != null, onClick = { platformMenu = true }, label = { Text(platform ?: "平台") })
                DropdownMenu(platformMenu, { platformMenu = false }) {
                    DropdownMenuItem({ Text("全部平台") }, onClick = { platform = null; platformMenu = false })
                    platforms.forEach { value -> DropdownMenuItem({ Text(value) }, onClick = { platform = value; platformMenu = false }) }
                }
            }
            Box {
                FilterChip(selected = dateRange != DateRange.ALL, onClick = { dateMenu = true }, label = { Text(dateRange.label) })
                DropdownMenu(dateMenu, { dateMenu = false }) {
                    DateRange.entries.forEach { value -> DropdownMenuItem({ Text(value.label) }, onClick = { dateRange = value; dateMenu = false }) }
                }
            }
        }

        if (filtered.isEmpty()) {
            EmptyState(Icons.Outlined.ReceiptLong, "没有符合条件的流水", "调整筛选，或点击右下角记录第一笔交易。")
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(filtered, key = { it.event.id }) { view ->
                    LedgerRow(view, itemsById, snapshot, onEdit = { onEdit(view.event.id) }, onVoid = { pendingVoid = view })
                }
            }
        }
    }

    pendingVoid?.let { view ->
        val restoring = view.event.status == EventStatus.VOIDED
        AlertDialog(
            onDismissRequest = { pendingVoid = null },
            icon = { Icon(if (restoring) Icons.Outlined.Restore else Icons.Outlined.Block, null) },
            title = { Text(if (restoring) "恢复这条流水？" else "作废这条流水？") },
            text = { Text(if (restoring) "恢复后会重新参与余额、持仓和盈亏计算；若导致负库存将被阻止。" else "原始内容会永久保留，但不再参与任何账务计算。") },
            confirmButton = { TextButton(onClick = { onSetVoided(view.event.id, !restoring); pendingVoid = null }) { Text(if (restoring) "恢复" else "作废") } },
            dismissButton = { TextButton(onClick = { pendingVoid = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun LedgerRow(
    view: EventView,
    itemsById: Map<Long, ItemEntity>,
    snapshot: LedgerSnapshot,
    onEdit: () -> Unit,
    onVoid: () -> Unit
) {
    val event = view.event
    val color = eventColor(event.type)
    val names = view.lines.mapNotNull { itemsById[it.itemId]?.name }.distinct()
    val summary = when {
        event.type == EventType.ACCOUNT_ADJUSTMENT -> event.note.ifBlank { "余额调整" }
        names.isEmpty() -> "未命名物品"
        names.size == 1 -> names.single()
        else -> "${names.first()} 等 ${names.size} 项"
    }
    val gross = if (event.type == EventType.ACCOUNT_ADJUSTMENT) event.accountDeltaCents else view.lines.sumOf { it.quantity * it.unitPriceCents }
    val cross = snapshot.crossAccountOutcomes.firstOrNull { it.eventId == event.id }
    val voided = event.status == EventStatus.VOIDED

    Surface(
        Modifier.fillMaxWidth().clickable(enabled = !voided, onClick = onEdit),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(color.copy(alpha = .14f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Text(event.type.label.take(1), color = color, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (voided) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f)
                    )
                    if (voided) StatusPill("已作废", TextSecondary)
                }
                Spacer(Modifier.padding(top = 2.dp))
                Text("${event.type.label} · ${event.platform} · ${event.accountType.label}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text(formatDateTime(event.timestamp), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                if (event.feeCents > 0) Text("费用 ${formatMoney(event.feeCents)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                if (cross != null) {
                    val cost = if (cross.cost.fiatCents > 0) "人民币 ${formatMoney(cross.cost.fiatCents)}" else "钱包 ${formatMoney(cross.cost.walletCents)}"
                    Text("$cost → ${cross.proceedsAccount.label} ${formatMoney(cross.proceedsCents)}", style = MaterialTheme.typography.bodySmall, color = AdjustAmber)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (event.type != EventType.CONVERT) Text(formatMoney(gross, true), style = MaterialTheme.typography.titleMedium, color = if (event.accountType == AccountType.FIAT_CNY) FiatGold else WalletBlue)
                Row {
                    if (!voided) IconButton(onEdit, Modifier.size(40.dp)) { Icon(Icons.Outlined.Edit, "编辑", Modifier.size(18.dp)) }
                    IconButton(onVoid, Modifier.size(40.dp)) { Icon(if (voided) Icons.Outlined.Restore else Icons.Outlined.Block, if (voided) "恢复" else "作废", Modifier.size(18.dp)) }
                }
            }
        }
    }
}

private fun eventColor(type: EventType): Color = when (type) {
    EventType.BUY -> BuyCoral
    EventType.SELL -> SellMint
    EventType.CONVERT -> ConvertPurple
    EventType.ACCOUNT_ADJUSTMENT -> AdjustAmber
}
