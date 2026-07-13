package com.steamtrans.ledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.steamtrans.ledger.data.*
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.absoluteValue

private val Ink = Color(0xFF09111F)
private val Surface = Color(0xFF101B2D)
private val SurfaceHigh = Color(0xFF17253A)
private val SteamBlue = Color(0xFF66C0F4)
private val Gain = Color(0xFF63D6A2)
private val Loss = Color(0xFFFF7A86)
private val Muted = Color(0xFF94A3B8)

private val scheme = darkColorScheme(
    primary = SteamBlue, onPrimary = Ink, background = Ink, onBackground = Color(0xFFF3F7FC),
    surface = Surface, onSurface = Color(0xFFF3F7FC), surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Color(0xFFB8C4D6), outline = Color(0xFF33445D), error = Loss,
    secondary = SteamBlue, onSecondary = Ink, secondaryContainer = SurfaceHigh, onSecondaryContainer = SteamBlue
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = scheme) { LedgerApp() } }
    }
}

private enum class Page(val label: String) { HOME("首页"), HOLDINGS("持仓"), LEDGER("流水"), SETTINGS("设置") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerApp(vm: LedgerViewModel = viewModel()) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val allItems by vm.items.collectAsStateWithLifecycle()
    val operationError by vm.operationError.collectAsStateWithLifecycle()
    val operationInProgress by vm.operationInProgress.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(Page.HOME) }
    var adding by remember { mutableStateOf(false) }
    var hideMoney by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(operationError, snapshot.error) {
        val message = operationError ?: snapshot.error
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            if (operationError != null) vm.clearOperationError()
        }
    }

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = Surface) {
                Page.entries.forEach { p ->
                    val icon = when (p) { Page.HOME -> Icons.Default.Home; Page.HOLDINGS -> Icons.Default.Inventory2; Page.LEDGER -> Icons.Default.ReceiptLong; Page.SETTINGS -> Icons.Default.Settings }
                    NavigationBarItem(selected = p == page, onClick = { page = p }, icon = { Icon(icon, p.label) }, label = { Text(p.label) })
                }
            }
        },
        floatingActionButton = {
            if (page != Page.SETTINGS) ExtendedFloatingActionButton(onClick = { adding = true }, containerColor = SteamBlue, contentColor = Ink, icon = { Icon(Icons.Default.Add, null) }, text = { Text("记一笔") })
        }
    ) { padding ->
        AnimatedContent(page, label = "page") { current ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (current) {
                    Page.HOME -> HomeScreen(snapshot, allItems, hideMoney, { hideMoney = !hideMoney })
                    Page.HOLDINGS -> HoldingsScreen(snapshot, onBuy = { adding = true })
                    Page.LEDGER -> LedgerScreen(snapshot, allItems, vm::deleteEvent)
                    Page.SETTINGS -> SettingsScreen(allItems.size, snapshot.events.size, hideMoney, { hideMoney = it }, vm::clearAll)
                }
            }
        }
    }
    if (adding) AddRecordSheet(
        allItems,
        snapshot,
        operationInProgress,
        onDismiss = { adding = false },
        onSave = { draft -> vm.addEvent(draft) { adding = false } },
        onCreateAndSave = { item, draft -> vm.addItemWithInitialBuy(item, draft) { adding = false } },
        onCreateOutputAndSave = { item, draft -> vm.addItemWithConversionOutput(item, draft) { adding = false } }
    )
}

@Composable
private fun ScreenTitle(title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun HomeScreen(s: LedgerSnapshot, allItems: List<ItemEntity>, hide: Boolean, toggleHide: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 100.dp)) {
        item {
            ScreenTitle("Steam 账本") { IconButton(toggleHide) { Icon(if (hide) Icons.Default.VisibilityOff else Icons.Default.Visibility, "隐藏金额") } }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text("已实现盈亏", color = Muted, fontSize = 14.sp)
                val pnlColor = if (s.totalRealizedPnlCents >= 0) Gain else Loss
                Text(money(s.totalRealizedPnlCents, hide, true), color = pnlColor, fontSize = 42.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                Metric("持仓成本", money(s.totalCostCents, hide), Modifier.fillMaxWidth())
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth()) {
                    Metric("累计投入", money(s.totalBoughtCents, hide), Modifier.weight(1f))
                    Metric("总计收入", money(s.totalSoldCents, hide), Modifier.weight(1f))
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth()) {
                    Metric("持仓宝石", "${s.gemQuantity} 个", Modifier.weight(1f))
                    Metric("持仓物品", "${s.itemQuantity} 件", Modifier.weight(1f))
                }
            }
            HorizontalDivider(Modifier.padding(top = 24.dp), color = SurfaceHigh)
            Text("主要持仓", Modifier.padding(20.dp), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        }
        if (s.holdings.isEmpty()) item { EmptyHint("还没有持仓，点击“记一笔”开始记录") }
        items(s.holdings.take(5), key = { "home-holding-${it.item.id}" }) { HoldingRow(it) }
        if (s.events.isNotEmpty()) item { Text("最近流水", Modifier.padding(20.dp), fontWeight = FontWeight.SemiBold, fontSize = 18.sp) }
        items(s.events.take(4), key = { "home-event-${it.event.id}" }) { EventRow(it, allItems) }
    }
}

@Composable private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) { Text(label, color = Muted, fontSize = 13.sp); Text(value, fontSize = 19.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun HoldingsScreen(s: LedgerSnapshot, onBuy: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = s.holdings.filter { it.item.name.contains(query, true) || it.item.game.contains(query, true) }
    Column {
        ScreenTitle("持仓") { TextButton(onClick = onBuy) { Icon(Icons.Default.Add, null); Text("录入买入") } }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp), placeholder = { Text("搜索物品或游戏") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
        Spacer(Modifier.height(10.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 100.dp)) {
            if (filtered.isEmpty()) item { EmptyHint("暂无持仓，点击“录入买入”一次完成物品创建和入账") }
            items(filtered, key = { it.item.id }) { h -> HoldingRow(h) }
        }
    }
}

@Composable
private fun HoldingRow(h: Holding) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(SurfaceHigh, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text(h.item.type.label.take(1), color = SteamBlue, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(h.item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(listOf(h.item.game, h.item.type.label).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (h.item.type == ItemType.GEM) "${h.quantity} 个宝石" else "${h.quantity} 件", fontWeight = FontWeight.SemiBold)
            Text("持仓成本 ${money(h.costCents)} · 均价 ${money(h.averageCostCents)}", color = Muted, fontSize = 12.sp)
            Text(if (h.item.type == ItemType.GEM) "散装宝石不可直接出售" else "Steam 回本价 ${money(h.breakEvenPriceCents)}/件", color = Muted, fontSize = 11.sp)
        }
    }
    HorizontalDivider(Modifier.padding(start = 75.dp), color = SurfaceHigh)
}

@Composable
private fun LedgerScreen(s: LedgerSnapshot, allItems: List<ItemEntity>, onDelete: (Long) -> Unit) {
    var filter by remember { mutableStateOf<EventType?>(null) }
    var pendingDelete by remember { mutableStateOf<Long?>(null) }
    Column {
        ScreenTitle("流水")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filter == null, { filter = null }, { Text("全部") })
            EventType.entries.forEach { t -> FilterChip(filter == t, { filter = t }, { Text(t.label) }) }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 100.dp)) {
            val list = s.events.filter { filter == null || it.event.type == filter }
            if (list.isEmpty()) item { EmptyHint("暂无流水") }
            items(list, key = { it.event.id }) { EventRow(it, allItems) { pendingDelete = it } }
        }
    }
    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条流水？") },
            text = { Text("删除后会重新计算持仓和盈亏，此操作无法撤销。") },
            confirmButton = { TextButton({ onDelete(id); pendingDelete = null }) { Text("删除", color = Loss) } },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun EventRow(view: EventView, allItems: List<ItemEntity>, onDelete: ((Long) -> Unit)? = null) {
    val names = view.lines.mapNotNull { line -> allItems.firstOrNull { it.id == line.itemId }?.name }.distinct().joinToString(" → ").ifBlank { view.event.note.ifBlank { view.event.type.label } }
    val amount = view.lines.sumOf { it.quantity * it.unitPriceCents }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 13.dp, bottom = 13.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(view.event.type.label, color = when(view.event.type) { EventType.BUY -> Loss; EventType.SELL -> Gain; EventType.CONVERT -> SteamBlue }, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("  $names", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${date(view.event.timestamp)} · ${view.event.platform}", color = Muted, fontSize = 12.sp)
        }
        if (view.event.type != EventType.CONVERT) Text(money(amount), fontWeight = FontWeight.SemiBold)
        onDelete?.let { IconButton({ it(view.event.id) }) { Icon(Icons.Default.DeleteOutline, "删除", tint = Muted) } }
    }
    HorizontalDivider(Modifier.padding(start = 20.dp), color = SurfaceHigh)
}

@Composable
private fun SettingsScreen(itemCount: Int, eventCount: Int, hide: Boolean, setHide: (Boolean) -> Unit, clear: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column {
        ScreenTitle("设置")
        SettingRow("隐藏金额", "在公共场合保护账本信息") { Switch(hide, setHide) }
        SettingRow("本地数据", "$itemCount 个物品 · $eventCount 条流水") { Icon(Icons.Default.PhoneAndroid, null, tint = Muted) }
        SettingRow("计价币种", "人民币 CNY") { Text("¥", color = SteamBlue, fontSize = 20.sp) }
        SettingRow("清空全部数据", "此操作无法撤销") { TextButton({ confirm = true }) { Text("清空", color = Loss) } }
        Text("数据仅保存在本机。卸载 App 会删除全部记录。", Modifier.padding(20.dp), color = Muted, fontSize = 12.sp)
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清空全部数据？") }, text = { Text("物品、持仓和流水都会永久删除。") }, confirmButton = { TextButton({ clear(); confirm = false }) { Text("确认清空", color = Loss) } }, dismissButton = { TextButton({ confirm = false }) { Text("取消") } })
}

@Composable private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); Text(subtitle, color = Muted, fontSize = 12.sp) }
        trailing()
    }
    HorizontalDivider(Modifier.padding(start = 20.dp), color = SurfaceHigh)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRecordSheet(
    items: List<ItemEntity>,
    snapshot: LedgerSnapshot,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (EventDraft) -> Unit,
    onCreateAndSave: (ItemEntity, EventDraft) -> Unit,
    onCreateOutputAndSave: (ItemEntity, EventDraft) -> Unit
) {
    var type by remember { mutableStateOf(EventType.BUY) }
    var first by remember { mutableStateOf<ItemEntity?>(items.firstOrNull()) }
    var newType by remember { mutableStateOf(ItemType.SKIN) }
    var newName by remember { mutableStateOf("") }
    var newGame by remember { mutableStateOf("") }
    var outputType by remember { mutableStateOf(ItemType.BOOSTER) }
    var outputName by remember { mutableStateOf("") }
    var outputGame by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var outputQuantity by remember { mutableStateOf("1") }
    var price by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("0") }
    var platform by remember { mutableStateOf("Steam") }
    var note by remember { mutableStateOf("") }
    val q = quantity.toLongOrNull() ?: 0
    val outQ = outputQuantity.toLongOrNull() ?: 0
    val parsedPriceCents = parseMoney(price)
    val priceCents = parsedPriceCents ?: 0
    val grossCentsOrNull = runCatching { Math.multiplyExact(q, priceCents) }.getOrNull()
    val grossCents = grossCentsOrNull ?: 0
    val isSteamSale = type == EventType.SELL && platform.trim().equals("Steam", ignoreCase = true)
    val parsedFeeCents = parseMoney(fee)
    val feeCents = if (isSteamSale) steamSaleFee(grossCents) else parsedFeeCents ?: 0
    val cashCents = if (grossCentsOrNull == null || (!isSteamSale && parsedFeeCents == null)) null else runCatching {
        if (type == EventType.BUY) Math.addExact(grossCents, feeCents) else Math.subtractExact(grossCents, feeCents)
    }.getOrNull()
    val available = snapshot.holdings.firstOrNull { it.item.id == first?.id }?.quantity ?: 0
    val standardName = when (newType) {
        ItemType.GEM_SACK -> "宝石袋"
        ItemType.GEM -> "宝石"
        ItemType.BOOSTER -> if (newGame.isBlank()) "" else "${newGame.trim()} 卡包"
        else -> newName.trim()
    }
    val newItemValid = standardName.isNotBlank() && (newType != ItemType.BOOSTER || newGame.isNotBlank())
    val outputStandardName = when (outputType) {
        ItemType.GEM_SACK -> "宝石袋"
        ItemType.GEM -> "宝石"
        ItemType.BOOSTER -> if (outputGame.isBlank()) "" else "${outputGame.trim()} 卡包"
        else -> outputName.trim()
    }
    val outputItemValid = outputStandardName.isNotBlank() && (outputType != ItemType.BOOSTER || outputGame.isNotBlank())
    val valid = q > 0 && !saving && when (type) {
        EventType.BUY -> parsedPriceCents != null && priceCents > 0 && cashCents != null && newItemValid
        EventType.SELL -> parsedPriceCents != null && priceCents > 0 && cashCents != null && cashCents >= 0 && first != null && q <= available
        EventType.CONVERT -> first != null && outputItemValid && q <= available && outQ > 0
    }
    val ownedItems = snapshot.holdings.map { it.item }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = sheetState,
        containerColor = Surface,
        contentWindowInsets = { WindowInsets.safeDrawing }
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("记录一笔", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EventType.entries.forEach { t ->
                    FilterChip(type == t, {
                        type = t
                        if (t != EventType.BUY) first = ownedItems.firstOrNull()
                    }, { Text(t.label) })
                }
            }

            if (type == EventType.BUY) {
                Text("买入物品", fontWeight = FontWeight.SemiBold)
                NewItemFields(newType, { newType = it }, newName, { newName = it }, newGame, { newGame = it })
                Text("若该物品已存在，本次交易会自动合并到原持仓", color = Muted, fontSize = 12.sp)
            } else {
                if (ownedItems.isEmpty()) Text("当前没有可${if (type == EventType.SELL) "卖出" else "转换"}的持仓", color = Loss)
                else ItemPicker(if (type == EventType.CONVERT) "转出物品" else "卖出物品", ownedItems, first) { first = it }
            }

            if (type == EventType.CONVERT && first != null) {
                if (type == EventType.CONVERT) {
                    val isBag = first?.type == ItemType.GEM_SACK
                    val isGem = first?.type == ItemType.GEM
                    if (isBag || isGem) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { quantity = if (isBag) "1" else "1000"; outputQuantity = if (isBag) "1000" else "1" }, label = { Text(if (isBag) "拆为 1000 宝石" else "封装宝石袋") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField("转出数量", quantity, { quantity = it }, Modifier.weight(1f))
                        NumberField("产出数量", outputQuantity, { outputQuantity = it }, Modifier.weight(1f))
                    }
                    Text("产出物品", fontWeight = FontWeight.SemiBold)
                    NewItemFields(outputType, { outputType = it }, outputName, { outputName = it }, outputGame, { outputGame = it })
                    Text("若产出物已存在，会自动加入原持仓", color = Muted, fontSize = 12.sp)
                    val holdingCost = snapshot.holdings.firstOrNull { it.item.id == first?.id }?.costCents ?: 0
                    Text("本次转移成本：${money(if (available > 0 && q <= available) proportionalCost(holdingCost, q, available) else 0)}", color = SteamBlue, fontSize = 13.sp)
                }
            } else if (type != EventType.CONVERT) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField("数量", quantity, { quantity = it }, Modifier.weight(1f))
                        MoneyField("单价", price, { price = it }, Modifier.weight(1f))
                    }
                    if (type == EventType.SELL) Text("可用库存：$available", color = if (q > available) Loss else Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(platform, { platform = it }, label = { Text("平台") }, singleLine = true, modifier = Modifier.weight(1f))
                        if (isSteamSale) OutlinedTextField(money(feeCents), {}, readOnly = true, label = { Text("Steam 手续费 15%") }, singleLine = true, modifier = Modifier.weight(1f))
                        else MoneyField("手续费", fee, { fee = it }, Modifier.weight(1f))
                    }
                    val cash = cashCents ?: 0
                    Text(if (type == EventType.BUY) "预计支出 ${money(cash)}" else "预计到账 ${money(cash)}", color = SteamBlue, fontWeight = FontWeight.SemiBold)
                    val targetType = newType
                    if (type == EventType.BUY && targetType != ItemType.GEM && q > 0) {
                        val current = snapshot.holdings.firstOrNull { it.item.name == standardName && it.item.game == newGame.trim() && it.item.type == newType }
                        val newCost = (current?.costCents ?: 0) + cash
                        val newQuantity = (current?.quantity ?: 0) + q
                        Text("加入持仓后的 Steam 回本价：${money(breakEvenPrice(newCost, newQuantity))}/件", color = Muted, fontSize = 13.sp)
                    }
            }
            OutlinedTextField(note, { note = it }, label = { Text("备注（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                enabled = valid,
                onClick = {
                    val lines = if (type == EventType.CONVERT) listOf(
                        DraftLine(first!!.id, LineDirection.OUT, q), DraftLine(0, LineDirection.IN, outQ)
                    ) else listOf(DraftLine(if (type == EventType.BUY) 0 else first!!.id, if (type == EventType.BUY) LineDirection.IN else LineDirection.OUT, q, priceCents))
                    val draft = EventDraft(type = type, platform = platform, feeCents = if (type == EventType.CONVERT) 0 else feeCents, note = note, lines = lines)
                    if (type == EventType.BUY) onCreateAndSave(ItemEntity(name = standardName, game = newGame.trim(), type = newType), draft)
                    else if (type == EventType.CONVERT) onCreateOutputAndSave(ItemEntity(name = outputStandardName, game = outputGame.trim(), type = outputType), draft)
                    else onSave(draft)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (saving) "保存中" else "保存${type.label}")
            }
            Spacer(Modifier.height(72.dp))
        }
    }
}

@Composable
private fun NewItemFields(
    type: ItemType, setType: (ItemType) -> Unit,
    name: String, setName: (String) -> Unit,
    game: String, setGame: (String) -> Unit
) {
    ItemTypePicker(type, setType)
    val fixed = type == ItemType.GEM || type == ItemType.GEM_SACK
    if (!fixed) {
        OutlinedTextField(game, setGame, label = { Text(if (type == ItemType.BOOSTER) "所属游戏（必填）" else "所属游戏（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    if (!fixed && type != ItemType.BOOSTER) {
        OutlinedTextField(name, setName, label = { Text("物品名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    if (fixed) Text("名称将自动设为“${if (type == ItemType.GEM) "宝石" else "宝石袋"}”", color = Muted, fontSize = 12.sp)
    if (type == ItemType.BOOSTER && game.isNotBlank()) Text("名称将自动设为“${game.trim()} 卡包”", color = Muted, fontSize = 12.sp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemPicker(label: String, items: List<ItemEntity>, selected: ItemEntity?, set: (ItemEntity) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = !open }) {
        OutlinedTextField(
            value = selected?.let { "${it.name}${if (it.game.isBlank()) "" else " · ${it.game}"}" } ?: "请选择",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text("${item.name} · ${item.type.label}") }, onClick = { set(item); open = false })
            }
        }
    }
}

@Composable private fun NumberField(label: String, value: String, set: (String) -> Unit, modifier: Modifier = Modifier) = OutlinedTextField(value, { if (it.all(Char::isDigit)) set(it) }, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = modifier)
@Composable private fun MoneyField(label: String, value: String, set: (String) -> Unit, modifier: Modifier = Modifier) = OutlinedTextField(value, { if (it.matches(Regex("\\d*(\\.\\d{0,2})?"))) set(it) }, label = { Text(label) }, prefix = { Text("¥") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = modifier)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemTypePicker(selected: ItemType, set: (ItemType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = !open }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("物品类型") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ItemType.entries.forEach { type ->
                DropdownMenuItem(text = { Text(type.label) }, onClick = { set(type); open = false })
            }
        }
    }
}

@Composable private fun EmptyHint(text: String) { Text(text, Modifier.fillMaxWidth().padding(28.dp), color = Muted) }
internal fun parseMoney(value: String): Long? {
    if (value.isBlank()) return 0
    return runCatching { BigDecimal(value).movePointRight(2).longValueExact() }.getOrNull()
}
internal fun steamSaleFee(grossCents: Long): Long {
    if (grossCents <= 0) return 0
    val wholeYuanPart = Math.multiplyExact(grossCents / 100, 15)
    val remainderPart = ((grossCents % 100) * 15 + 99) / 100
    return Math.addExact(wholeYuanPart, remainderPart)
}
private fun money(cents: Long, hidden: Boolean = false, signed: Boolean = false): String {
    if (hidden) return "¥ ••••"
    val sign = when { signed && cents > 0 -> "+"; cents < 0 -> "-"; else -> "" }
    return "$sign¥${"%,.2f".format(cents.absoluteValue / 100.0)}"
}
private fun date(time: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(time))
