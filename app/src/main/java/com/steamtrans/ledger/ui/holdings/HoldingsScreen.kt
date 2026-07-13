package com.steamtrans.ledger.ui.holdings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.steamtrans.ledger.MarketRefreshState
import com.steamtrans.ledger.MarketSearchState
import com.steamtrans.ledger.data.AccountType
import com.steamtrans.ledger.data.EventView
import com.steamtrans.ledger.data.Holding
import com.steamtrans.ledger.data.ItemEntity
import com.steamtrans.ledger.data.ItemType
import com.steamtrans.ledger.data.LedgerSnapshot
import com.steamtrans.ledger.data.MarketQuoteEntity
import com.steamtrans.ledger.data.TrackingMode
import com.steamtrans.ledger.data.market.SteamMarketSearchResult
import com.steamtrans.ledger.formatDateTime
import com.steamtrans.ledger.formatMoney
import com.steamtrans.ledger.parseMoney
import com.steamtrans.ledger.ui.components.Amount
import com.steamtrans.ledger.ui.components.EmptyState
import com.steamtrans.ledger.ui.components.ItemArtwork
import com.steamtrans.ledger.ui.components.ScreenHeading
import com.steamtrans.ledger.ui.components.StatusPill
import com.steamtrans.ledger.ui.theme.FiatGold
import com.steamtrans.ledger.ui.theme.Gain
import com.steamtrans.ledger.ui.theme.Loss
import com.steamtrans.ledger.ui.theme.RaisedBlue
import com.steamtrans.ledger.ui.theme.Stale
import com.steamtrans.ledger.ui.theme.SteamBlue
import com.steamtrans.ledger.ui.theme.TextSecondary
import com.steamtrans.ledger.ui.theme.Warning
import com.steamtrans.ledger.ui.theme.WalletBlue
import java.math.BigDecimal

private enum class HoldingSort(val label: String) { VALUE("预计价值"), COST("持仓成本"), QUANTITY("数量"), NAME("名称") }

private data class HoldingRow(val item: ItemEntity, val holding: Holding?, val events: List<EventView>)

@Composable
fun HoldingsScreen(
    allItems: List<ItemEntity>,
    snapshot: LedgerSnapshot,
    marketQuotes: List<MarketQuoteEntity>,
    refreshState: MarketRefreshState,
    searchState: MarketSearchState,
    onRefresh: () -> Unit,
    onSearchMarket: (String, Int?) -> Unit,
    onClearSearch: () -> Unit,
    onBindMarket: (Long, SteamMarketSearchResult) -> Unit,
    onUnbindMarket: (Long) -> Unit,
    onManualQuote: (Long, Long) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showClosed by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf<ItemType?>(null) }
    var typeMenu by remember { mutableStateOf(false) }
    var selectedTracking by remember { mutableStateOf<TrackingMode?>(null) }
    var bindingFilter by remember { mutableStateOf<Boolean?>(null) }
    var sort by remember { mutableStateOf(HoldingSort.VALUE) }
    var sortMenu by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var bindingItem by remember { mutableStateOf<ItemEntity?>(null) }
    var pricingItem by remember { mutableStateOf<ItemEntity?>(null) }

    val holdingsByItem = snapshot.holdings.associateBy { it.item.id }
    val rows = allItems.mapNotNull { item ->
        val holding = holdingsByItem[item.id]
        val itemEvents = snapshot.events.filter { view -> view.lines.any { it.itemId == item.id } }
        if (holding == null && itemEvents.isEmpty()) null else HoldingRow(item, holding, itemEvents)
    }.filter { row ->
        (showClosed == (row.holding == null)) &&
            (query.isBlank() || row.item.name.contains(query, true) || row.item.game.contains(query, true)) &&
            (selectedType == null || row.item.type == selectedType) &&
            (selectedTracking == null || row.item.trackingMode == selectedTracking) &&
            (bindingFilter == null || !row.item.marketHashName.isNullOrBlank() == bindingFilter)
    }.let { filtered ->
        when (sort) {
            HoldingSort.VALUE -> filtered.sortedWith(compareByDescending<HoldingRow> { it.holding?.marketNetValueCents ?: -1L }.thenByDescending { it.holding?.costCents ?: 0L })
            HoldingSort.COST -> filtered.sortedByDescending { it.holding?.costCents ?: 0L }
            HoldingSort.QUANTITY -> filtered.sortedByDescending { it.holding?.quantity ?: 0L }
            HoldingSort.NAME -> filtered.sortedBy { it.item.name }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeading("持仓", "逐件饰品与按量商品分别追踪")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            placeholder = { Text("搜索物品或游戏") },
            singleLine = true,
            shape = RoundedCornerShape(15.dp)
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = !showClosed, onClick = { showClosed = false }, label = { Text("当前") })
            FilterChip(selected = showClosed, onClick = { showClosed = true }, label = { Text("已清仓") })
            FilterChip(selected = selectedTracking == TrackingMode.INDIVIDUAL, onClick = { selectedTracking = if (selectedTracking == TrackingMode.INDIVIDUAL) null else TrackingMode.INDIVIDUAL }, label = { Text("逐件") })
            FilterChip(selected = selectedTracking == TrackingMode.STACKABLE, onClick = { selectedTracking = if (selectedTracking == TrackingMode.STACKABLE) null else TrackingMode.STACKABLE }, label = { Text("按量") })
            FilterChip(
                selected = bindingFilter == true,
                onClick = { bindingFilter = if (bindingFilter == true) null else true },
                label = { Text("已绑定") },
                leadingIcon = { Icon(Icons.Outlined.Link, null, Modifier.size(17.dp)) }
            )
            FilterChip(
                selected = bindingFilter == false,
                onClick = { bindingFilter = if (bindingFilter == false) null else false },
                label = { Text("未绑定") },
                leadingIcon = { Icon(Icons.Outlined.LinkOff, null, Modifier.size(17.dp)) }
            )
            Box {
                FilterChip(
                    selected = selectedType != null,
                    onClick = { typeMenu = true },
                    label = { Text(selectedType?.label ?: "类别") }
                )
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("全部类别") },
                        onClick = { selectedType = null; typeMenu = false }
                    )
                    ItemType.entries.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice.label) },
                            onClick = { selectedType = choice; typeMenu = false }
                        )
                    }
                }
            }
            Box {
                OutlinedButton(onClick = { sortMenu = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Outlined.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text(sort.label)
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    HoldingSort.entries.forEach { choice -> DropdownMenuItem(text = { Text(choice.label) }, onClick = { sort = choice; sortMenu = false }) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val message = when {
                refreshState.running -> "正在更新 ${refreshState.completed}/${refreshState.total}"
                refreshState.lastUpdatedAt != null -> "上次 ${formatDateTime(refreshState.lastUpdatedAt)} · 成功 ${refreshState.succeeded} / 失败 ${refreshState.failed}"
                else -> "行情只在你点击时联网"
            }
            Text(message, style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onRefresh, enabled = !refreshState.running && !showClosed) {
                if (refreshState.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp)); Text("更新市价")
            }
        }
        if (refreshState.running) LinearProgressIndicator({ refreshState.completed.toFloat() / refreshState.total.coerceAtLeast(1) }, Modifier.fillMaxWidth().padding(top = 8.dp))

        if (rows.isEmpty()) {
            EmptyState(
                if (showClosed) Icons.Outlined.History else Icons.Outlined.Inventory2,
                if (showClosed) "没有已清仓物品" else "没有符合条件的持仓",
                if (query.isBlank()) "记一笔买入后，物品会出现在这里。" else "试试清除搜索或筛选。"
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(rows, key = { it.item.id }) { row ->
                    HoldingCard(
                        row = row,
                        quoteHistory = marketQuotes.filter { it.itemId == row.item.id }.take(5),
                        expanded = expandedId == row.item.id,
                        onToggle = { expandedId = if (expandedId == row.item.id) null else row.item.id },
                        onBind = { bindingItem = row.item; onClearSearch() },
                        onUnbind = { onUnbindMarket(row.item.id) },
                        onManual = { pricingItem = row.item }
                    )
                }
            }
        }
    }

    bindingItem?.let { item ->
        MarketBindingDialog(
            item = item,
            searchState = searchState,
            onSearch = onSearchMarket,
            onSelect = { result -> onBindMarket(item.id, result); bindingItem = null; onClearSearch() },
            onDismiss = { bindingItem = null; onClearSearch() }
        )
    }
    pricingItem?.let { item ->
        ManualQuoteDialog(item, onDismiss = { pricingItem = null }, onSave = { cents -> onManualQuote(item.id, cents); pricingItem = null })
    }
}

@Composable
private fun HoldingCard(
    row: HoldingRow,
    quoteHistory: List<MarketQuoteEntity>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onBind: () -> Unit,
    onUnbind: () -> Unit,
    onManual: () -> Unit
) {
    val holding = row.holding
    val item = row.item
    val stale = holding?.quote?.timestamp?.let { it < System.currentTimeMillis() - 24 * 60 * 60 * 1000L } == true
    Surface(
        Modifier.fillMaxWidth().animateContentSize().clickable(onClick = onToggle),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(17.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ItemArtwork(item, Modifier.size(50.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text("${item.type.label} · ${item.trackingMode.label}${item.game.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (holding != null) {
                        Text("× ${holding.quantity}", style = MaterialTheme.typography.labelLarge, color = SteamBlue)
                        Spacer(Modifier.height(4.dp))
                        Amount(holding.marketNetValueCents ?: holding.costCents)
                    } else StatusPill("已清仓", TextSecondary)
                }
                Spacer(Modifier.width(3.dp))
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = TextSecondary)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 16.dp)) {
                    if (holding != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            CostColumn("人民币成本", holding.cost.fiatCents, FiatGold, Modifier.weight(1f))
                            CostColumn("钱包成本", holding.cost.walletCents, WalletBlue, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(13.dp))
                        if (holding.quote != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                CostColumn("单件挂牌", holding.quote.grossPriceCents, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                                CostColumn("单件预计到手", holding.quote.estimatedNetPriceCents, Gain, Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${holding.quote.source.label} · ${formatDateTime(holding.quote.timestamp)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.weight(1f))
                                if (stale) StatusPill("已过期", Stale, Icons.Outlined.History)
                            }
                            if (holding.cost.fiatCents == 0L) {
                                val pnl = (holding.marketNetValueCents ?: 0L) - holding.cost.walletCents
                                Spacer(Modifier.height(8.dp))
                                Text("可比较未实现盈亏 ${formatMoney(pnl, true)}", color = if (pnl >= 0) Gain else Loss, style = MaterialTheme.typography.titleMedium)
                            } else {
                                Spacer(Modifier.height(8.dp))
                                Text("人民币成本 → 预计钱包收入，不合并计算利润", color = Warning, style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            StatusPill("未定价", Warning, Icons.Outlined.CloudOff)
                        }
                        if (quoteHistory.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text("价格历史", style = MaterialTheme.typography.titleMedium)
                            quoteHistory.forEach { quote ->
                                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(formatDateTime(quote.timestamp), style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.weight(1f))
                                    Text("挂牌 ${formatMoney(quote.grossPriceCents)}", style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.width(9.dp))
                                    Text("到手 ${formatMoney(quote.estimatedNetPriceCents)}", style = MaterialTheme.typography.bodySmall, color = Gain)
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(if (item.trackingMode == TrackingMode.INDIVIDUAL) "独立件" else "买入批次", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(7.dp))
                        holding.lots.forEachIndexed { index, lot ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (item.trackingMode == TrackingMode.INDIVIDUAL) "#${lot.unitOrdinal ?: index + 1}" else "批次 ${index + 1}", color = TextSecondary, modifier = Modifier.width(62.dp))
                                Text(if (lot.legacyCarry) "旧账承接" else formatDateTime(lot.acquiredAt), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text("${lot.remainingQuantity} 件 · ${formatMoney(lot.cost.totalCents)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (row.events.isNotEmpty()) {
                        Spacer(Modifier.height(13.dp))
                        Text("最近流水", style = MaterialTheme.typography.titleMedium)
                        row.events.take(3).forEach { view ->
                            Text("${view.event.type.label} · ${formatDateTime(view.event.timestamp)} · ${view.event.platform}", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                    Spacer(Modifier.height(15.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onManual, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Edit, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("手动行情") }
                        if (item.marketHashName.isNullOrBlank()) {
                            Button(onClick = onBind, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.ManageSearch, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("绑定 Steam") }
                        } else {
                            OutlinedButton(onClick = onUnbind, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.LinkOff, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("解除绑定") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CostColumn(label: String, cents: Long, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(formatMoney(cents), style = MaterialTheme.typography.titleMedium, color = color)
    }
}

@Composable
private fun MarketBindingDialog(
    item: ItemEntity,
    searchState: MarketSearchState,
    onSearch: (String, Int?) -> Unit,
    onSelect: (SteamMarketSearchResult) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember(item.id) { mutableStateOf(item.name) }
    var appId by remember(item.id) { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("绑定 Steam 市场") },
        text = {
            Column {
                Text("默认搜索全部游戏；选择结果后会保存对应游戏、市场名称和图片。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(appId == null, { appId = null }, label = { Text("全部游戏") })
                    FilterChip(appId == 730, { appId = 730 }, label = { Text("CS2") })
                    FilterChip(appId == 570, { appId = 570 }, label = { Text("DOTA 2") })
                }
                OutlinedTextField(query, { query = it }, label = { Text("市场搜索词") }, singleLine = true, modifier = Modifier.fillMaxWidth(), trailingIcon = { IconButton({ onSearch(query, appId) }, enabled = query.trim().length >= 2 && !searchState.running) { Icon(Icons.Outlined.Search, "搜索") } })
                Spacer(Modifier.height(10.dp))
                if (searchState.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (searchState.error != null) Text(searchState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Column(Modifier.height(250.dp).verticalScroll(rememberScrollState())) {
                    searchState.results.forEach { result ->
                        Surface(
                            Modifier.fillMaxWidth().clickable { onSelect(result) },
                            color = RaisedBlue,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(Modifier.padding(11.dp)) {
                                Text(result.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(result.gameName.ifBlank { "App ${result.appId}" }, style = MaterialTheme.typography.bodySmall, color = SteamBlue)
                                Text(result.marketHashName, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 2)
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSearch(query, appId) }, enabled = query.trim().length >= 2 && !searchState.running) { Text("搜索") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun ManualQuoteDialog(item: ItemEntity, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var amount by remember(item.id) { mutableStateOf("") }
    val parsed = parseMoney(amount)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${item.name} · 手动行情") },
        text = {
            Column {
                Text("填写 Steam 市场单件挂牌价；预计到手价按 Steam 平台档案计算。", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(amount, { amount = it }, label = { Text("单件挂牌价") }, prefix = { Text("¥") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, isError = amount.isNotBlank() && (parsed == null || parsed <= 0))
            }
        },
        confirmButton = { Button(onClick = { parsed?.let(onSave) }, enabled = parsed != null && parsed > 0) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
