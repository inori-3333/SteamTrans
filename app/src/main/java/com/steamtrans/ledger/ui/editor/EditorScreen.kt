package com.steamtrans.ledger.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.steamtrans.ledger.BOOSTER_GEM_COSTS
import com.steamtrans.ledger.ConversionRecipe
import com.steamtrans.ledger.boosterGemCostFor
import com.steamtrans.ledger.data.AccountType
import com.steamtrans.ledger.data.DraftLine
import com.steamtrans.ledger.data.EventDraft
import com.steamtrans.ledger.data.EventLineEntity
import com.steamtrans.ledger.data.EventType
import com.steamtrans.ledger.data.EventView
import com.steamtrans.ledger.data.Holding
import com.steamtrans.ledger.data.ItemEntity
import com.steamtrans.ledger.data.ItemType
import com.steamtrans.ledger.data.LineDirection
import com.steamtrans.ledger.data.LotAllocationDraft
import com.steamtrans.ledger.data.PlatformProfileEntity
import com.steamtrans.ledger.data.TrackingMode
import com.steamtrans.ledger.formatMoney
import com.steamtrans.ledger.gemQuantityForBags
import com.steamtrans.ledger.gemQuantityForBoosterPacks
import com.steamtrans.ledger.parseMoney
import com.steamtrans.ledger.ui.theme.AdjustAmber
import com.steamtrans.ledger.ui.theme.BuyCoral
import com.steamtrans.ledger.ui.theme.ConvertPurple
import com.steamtrans.ledger.ui.theme.FiatGold
import com.steamtrans.ledger.ui.theme.PageBlue
import com.steamtrans.ledger.ui.theme.RaisedBlue
import com.steamtrans.ledger.ui.theme.SellMint
import com.steamtrans.ledger.ui.theme.TextSecondary
import com.steamtrans.ledger.ui.theme.WalletBlue
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AmountMode(val label: String) { UNIT("单价"), TOTAL("成交总额") }

private data class EditorLine(
    val key: Long,
    val itemId: Long = 0,
    val quantity: String = "1",
    val amount: String = "",
    val mode: AmountMode = AmountMode.UNIT,
    val selectedSourceLineId: Long? = null,
    val selectedUnitOrdinal: Int? = null
)

private val EditorLinesSaver = listSaver<SnapshotStateList<EditorLine>, Any>(
    save = { lines ->
        lines.flatMap { line ->
            listOf(
                line.key,
                line.itemId,
                line.quantity,
                line.amount,
                line.mode.name,
                line.selectedSourceLineId ?: Long.MIN_VALUE,
                line.selectedUnitOrdinal ?: Int.MIN_VALUE
            )
        }
    },
    restore = { values ->
        mutableStateListOf<EditorLine>().apply {
            values.chunked(7).forEach { fields ->
                add(
                    EditorLine(
                        key = fields[0] as Long,
                        itemId = fields[1] as Long,
                        quantity = fields[2] as String,
                        amount = fields[3] as String,
                        mode = AmountMode.valueOf(fields[4] as String),
                        selectedSourceLineId = (fields[5] as Long).takeUnless { it == Long.MIN_VALUE },
                        selectedUnitOrdinal = (fields[6] as Int).takeUnless { it == Int.MIN_VALUE }
                    )
                )
            }
        }
    }
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    eventId: Long?,
    initialType: EventType?,
    allItems: List<ItemEntity>,
    holdings: List<Holding>,
    events: List<EventView>,
    profiles: List<PlatformProfileEntity>,
    recipes: List<ConversionRecipe>,
    saving: Boolean,
    onClose: () -> Unit,
    onSave: (EventDraft) -> Unit,
    onUpdate: (Long, EventDraft) -> Unit,
    onCreateAndBuy: (ItemEntity, EventDraft) -> Unit,
    onCreateConversionOutput: (ItemEntity, EventDraft) -> Unit,
    onSaveRecipe: (ConversionRecipe) -> Unit
) {
    val existing = events.firstOrNull { it.event.id == eventId }
    var type by rememberSaveable(eventId) { mutableStateOf(existing?.event?.type ?: initialType ?: EventType.BUY) }
    var platform by rememberSaveable(eventId) { mutableStateOf(existing?.event?.platform ?: profiles.firstOrNull()?.name.orEmpty().ifBlank { "Steam" }) }
    var account by rememberSaveable(eventId) { mutableStateOf(existing?.event?.accountType ?: profiles.firstOrNull { it.name == platform }?.defaultAccountType ?: AccountType.STEAM_WALLET_CNY) }
    var dateText by rememberSaveable(eventId) { mutableStateOf(formatEditorDate(existing?.event?.timestamp ?: System.currentTimeMillis())) }
    var feeText by rememberSaveable(eventId) { mutableStateOf(existing?.event?.feeCents?.let(::centsInput).orEmpty()) }
    var note by rememberSaveable(eventId) { mutableStateOf(existing?.event?.note.orEmpty()) }
    var dirty by rememberSaveable(eventId) { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var newItem by rememberSaveable(eventId) { mutableStateOf(false) }
    var newName by rememberSaveable(eventId) { mutableStateOf("") }
    var newGame by rememberSaveable(eventId) { mutableStateOf("") }
    var newType by rememberSaveable(eventId) { mutableStateOf(ItemType.OTHER) }
    var newTracking by rememberSaveable(eventId) { mutableStateOf(TrackingMode.STACKABLE) }
    var newItemTypeMenu by remember { mutableStateOf(false) }
    var platformMenu by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var showRecipeDialog by remember { mutableStateOf(false) }
    var recipeName by rememberSaveable { mutableStateOf("") }
    val lines = rememberSaveable(eventId, existing?.event?.updatedAt, saver = EditorLinesSaver) {
        mutableStateListOf<EditorLine>().apply {
            if (existing != null && existing.lines.isNotEmpty()) {
                existing.lines.forEachIndexed { index, line ->
                    val allocation = existing.allocations.firstOrNull { it.eventLineId == line.id }
                    add(EditorLine(index.toLong(), line.itemId, line.quantity.toString(), centsInput(line.unitPriceCents), AmountMode.UNIT, allocation?.sourceLineId, allocation?.unitOrdinal))
                }
            } else if (type != EventType.ACCOUNT_ADJUSTMENT) {
                add(EditorLine(0, holdings.firstOrNull()?.item?.id ?: allItems.firstOrNull()?.id ?: 0))
                if (type == EventType.CONVERT) add(EditorLine(1, 0, "1", ""))
            }
        }
    }
    var boosterGemCost by rememberSaveable(eventId, existing?.event?.updatedAt) {
        mutableStateOf(
            boosterGemCostFor(
                lines.getOrNull(0)?.quantity.orEmpty(),
                lines.getOrNull(1)?.quantity.orEmpty()
            ) ?: 1_000L
        )
    }

    fun isGemToBoosterConversion(): Boolean {
        if (type != EventType.CONVERT || lines.size < 2) return false
        val inputType = allItems.firstOrNull { it.id == lines[0].itemId }?.type
        val outputType = if (newItem) newType else allItems.firstOrNull { it.id == lines[1].itemId }?.type
        return inputType == ItemType.GEM && outputType == ItemType.BOOSTER
    }

    fun updateGemTotal(packQuantity: String = lines.getOrNull(1)?.quantity.orEmpty()) {
        if (!isGemToBoosterConversion()) return
        lines[0] = lines[0].copy(quantity = gemQuantityForBoosterPacks(boosterGemCost, packQuantity))
    }

    fun requestClose() { if (dirty) showDiscard = true else onClose() }
    BackHandler(onBack = ::requestClose)

    val selectedProfile = profiles.firstOrNull { it.name == platform }
    val timestamp = parseEditorDate(dateText)
    val parsedFee = parseMoney(feeText.ifBlank { "0" })
    val eventColor = when (type) {
        EventType.BUY -> BuyCoral
        EventType.SELL -> SellMint
        EventType.CONVERT -> ConvertPurple
        EventType.ACCOUNT_ADJUSTMENT -> AdjustAmber
    }

    Scaffold(
        containerColor = PageBlue,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (eventId == null) "记录${type.label}" else "编辑${type.label}")
                        Text("保存前会验证并重放后续账务", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                },
                navigationIcon = { IconButton(::requestClose) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PageBlue)
            )
        },
        bottomBar = {
            Surface(color = PageBlue, tonalElevation = 8.dp) {
                Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    validationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 7.dp)) }
                    Button(
                        onClick = {
                            validationError = null
                            val result = buildDraft(
                                type = type,
                                dateText = dateText,
                                platform = platform,
                                account = account,
                                feeText = feeText,
                                note = note,
                                lines = lines,
                                allItems = allItems,
                                holdings = holdings,
                                existing = existing
                            )
                            result.onSuccess { draft ->
                                runCatching {
                                    when {
                                        eventId != null -> onUpdate(eventId, draft)
                                        newItem && type == EventType.BUY -> onCreateAndBuy(newItemEntity(newName, newGame, newType, newTracking), draft.copy(lines = listOf(draft.lines.single().copy(itemId = 0))))
                                        newItem && type == EventType.CONVERT -> onCreateConversionOutput(newItemEntity(newName, newGame, newType, newTracking), draft)
                                        else -> onSave(draft)
                                    }
                                }.onFailure { validationError = it.message ?: "请检查输入" }
                            }.onFailure { validationError = it.message ?: "请检查输入" }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = !saving,
                        shape = RoundedCornerShape(15.dp)
                    ) { Text(if (saving) "正在校验…" else if (eventId == null) "保存${type.label}" else "保存修改") }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (eventId == null) {
                SectionLabel("类型")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EventType.entries.forEach { choice ->
                        FilterChip(
                            selected = type == choice,
                            onClick = {
                                type = choice; dirty = true; validationError = null
                                if (choice == EventType.ACCOUNT_ADJUSTMENT) lines.clear()
                                else if (lines.isEmpty()) lines += EditorLine(System.nanoTime(), holdings.firstOrNull()?.item?.id ?: allItems.firstOrNull()?.id ?: 0)
                                if (choice == EventType.CONVERT && lines.size == 1) lines += EditorLine(System.nanoTime())
                                if (choice != EventType.CONVERT && lines.size > 1) while (lines.size > 1) lines.removeAt(lines.lastIndex)
                                newItem = false
                            },
                            label = { Text(choice.label) }
                        )
                    }
                }
            }

            if (type != EventType.ACCOUNT_ADJUSTMENT) {
                SectionLabel("结算")
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(17.dp)) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box {
                            OutlinedButton(onClick = { platformMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(platform.ifBlank { "选择平台" }) }
                            DropdownMenu(platformMenu, { platformMenu = false }) {
                                profiles.forEach { profile ->
                                    DropdownMenuItem({ Text(profile.name) }, onClick = {
                                        platform = profile.name
                                        account = profile.defaultAccountType
                                        val rate = if (type == EventType.BUY) profile.buyFeeRateBps else profile.sellFeeRateBps
                                        val fixed = if (type == EventType.BUY) profile.buyFixedFeeCents else profile.sellFixedFeeCents
                                        feeText = calculateDefaultFee(lines, rate, fixed)
                                        platformMenu = false; dirty = true
                                    })
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AccountType.entries.forEach { choice ->
                                FilterChip(
                                    selected = account == choice,
                                    onClick = { account = choice; dirty = true },
                                    label = { Text(if (choice == AccountType.FIAT_CNY) "人民币资金池" else "Steam 钱包") }
                                )
                            }
                        }
                        Text("平台默认结算账户可在设置中配置；本笔可覆盖。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
            }

            if (type == EventType.CONVERT) {
                SectionLabel("快捷配方")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val bag = allItems.firstOrNull { it.type == ItemType.GEM_SACK }
                        val gem = allItems.firstOrNull { it.type == ItemType.GEM }
                        if (bag != null && gem != null) {
                            lines.clear(); lines += EditorLine(System.nanoTime(), bag.id, "1"); lines += EditorLine(System.nanoTime(), gem.id, "1000"); newItem = false; dirty = true
                        } else validationError = "请先创建宝石袋和宝石物品"
                    }) { Text("宝石袋 → 1000 宝石") }
                    OutlinedButton(onClick = {
                        val bag = allItems.firstOrNull { it.type == ItemType.GEM_SACK }
                        val gem = allItems.firstOrNull { it.type == ItemType.GEM }
                        if (bag != null && gem != null) {
                            lines.clear(); lines += EditorLine(System.nanoTime(), gem.id, "1000"); lines += EditorLine(System.nanoTime(), bag.id, "1"); newItem = false; dirty = true
                        } else validationError = "请先创建宝石袋和宝石物品"
                    }) { Text("1000 宝石 → 宝石袋") }
                    OutlinedButton(onClick = {
                        val gem = allItems.firstOrNull { it.type == ItemType.GEM }
                        if (gem != null) {
                            boosterGemCost = 1_000L
                            lines.clear(); lines += EditorLine(System.nanoTime(), gem.id, "1000"); lines += EditorLine(System.nanoTime(), 0, "1"); newItem = true; newType = ItemType.BOOSTER; newTracking = TrackingMode.STACKABLE; dirty = true
                        } else validationError = "请先创建宝石物品"
                    }) { Text("宝石 → 新卡包") }
                    recipes.forEach { recipe ->
                        OutlinedButton(onClick = {
                            lines.clear()
                            lines += EditorLine(System.nanoTime(), recipe.inputItemId, recipe.inputQuantity.toString())
                            lines += EditorLine(System.nanoTime(), recipe.outputItemId, recipe.outputQuantity.toString())
                            newItem = false
                            dirty = true
                        }) { Text(recipe.name, maxLines = 1) }
                    }
                    OutlinedButton(onClick = {
                        val input = lines.getOrNull(0)
                        val output = lines.getOrNull(1)
                        val inputItem = input?.let { line -> allItems.firstOrNull { it.id == line.itemId } }
                        val outputItem = output?.let { line -> allItems.firstOrNull { it.id == line.itemId } }
                        if (input == null || output == null || inputItem == null || outputItem == null ||
                            input.quantity.toLongOrNull()?.let { it > 0 } != true || output.quantity.toLongOrNull()?.let { it > 0 } != true
                        ) {
                            validationError = "请先选择有效的转入、转出物品与数量"
                        } else {
                            recipeName = "${inputItem.name} → ${outputItem.name}"
                            showRecipeDialog = true
                        }
                    }) { Text("保存当前配方") }
                }
            }

            if (type == EventType.ACCOUNT_ADJUSTMENT) {
                SectionLabel("账户余额变动")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountType.entries.forEach { choice -> FilterChip(account == choice, { account = choice; dirty = true }, label = { Text(choice.label) }) }
                }
                OutlinedTextField(
                    value = feeText,
                    onValueChange = { feeText = it; dirty = true },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("余额变动额（可为负数）") },
                    prefix = { Text("¥") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            } else {
                SectionLabel(if (type == EventType.CONVERT) "物品与数量" else "成交物品")
                if (eventId == null && (type == EventType.BUY || type == EventType.CONVERT) && lines.size == 1 + if (type == EventType.CONVERT) 1 else 0) {
                    FilterChip(
                        selected = newItem,
                        onClick = {
                            newItem = !newItem
                            updateGemTotal()
                            dirty = true
                        },
                        label = { Text(if (type == EventType.BUY) "买入新物品" else "产出新物品") },
                        leadingIcon = { Icon(Icons.Outlined.Inventory2, null, Modifier.size(18.dp)) }
                    )
                }
                lines.forEachIndexed { index, line ->
                    val isNewLine = newItem && ((type == EventType.BUY && index == 0) || (type == EventType.CONVERT && index == lines.lastIndex))
                    if (isNewLine) {
                        NewItemEditor(
                            name = newName,
                            onName = { newName = it; dirty = true },
                            game = newGame,
                            onGame = { newGame = it; dirty = true },
                            type = newType,
                            tracking = newTracking,
                            typeMenu = newItemTypeMenu,
                            onOpenType = { newItemTypeMenu = true },
                            onCloseType = { newItemTypeMenu = false },
                            onType = {
                                newType = it
                                newTracking = it.defaultTracking
                                newItemTypeMenu = false
                                updateGemTotal()
                                dirty = true
                            }
                        )
                    }
                    TransactionLineEditor(
                        index = index,
                        type = type,
                        line = line,
                        isNewItem = isNewLine,
                        candidates = when (type) {
                            EventType.BUY -> allItems
                            EventType.SELL -> holdings.map { it.item }
                            EventType.CONVERT -> if (index == 0) holdings.map { it.item } else allItems
                            EventType.ACCOUNT_ADJUSTMENT -> emptyList()
                        },
                        holding = holdings.firstOrNull { it.item.id == line.itemId },
                        canDelete = lines.size > if (type == EventType.CONVERT) 2 else 1,
                        showQuantity = !isGemToBoosterConversion(),
                        onChange = { updated ->
                            lines[index] = updated
                            if (type == EventType.CONVERT && index == 0 && lines.size > 1) {
                                val input = allItems.firstOrNull { it.id == updated.itemId }
                                val output = allItems.firstOrNull { it.id == lines[1].itemId }
                                if (input?.type == ItemType.GEM_SACK && output?.type == ItemType.GEM) {
                                    lines[1] = lines[1].copy(quantity = gemQuantityForBags(updated.quantity))
                                }
                            }
                            if (isGemToBoosterConversion()) updateGemTotal(lines[1].quantity)
                            dirty = true
                        },
                        onDelete = { lines.removeAt(index); dirty = true }
                    )
                }
                if (isGemToBoosterConversion()) {
                    GemToBoosterControls(
                        selectedGemCost = boosterGemCost,
                        packQuantity = lines[1].quantity,
                        onGemCostChange = { selected ->
                            boosterGemCost = selected
                            updateGemTotal()
                            dirty = true
                        },
                        onPackQuantityChange = { quantity ->
                            lines[1] = lines[1].copy(quantity = quantity)
                            updateGemTotal(quantity)
                            dirty = true
                        }
                    )
                }
                if (type == EventType.BUY || type == EventType.SELL) {
                    OutlinedButton(onClick = { lines += EditorLine(System.nanoTime(), if (type == EventType.SELL) holdings.firstOrNull()?.item?.id ?: 0 else allItems.firstOrNull()?.id ?: 0); dirty = true }, enabled = !newItem, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(7.dp)); Text("追加物品行")
                    }
                }
            }

            SectionLabel("日期与费用")
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(17.dp)) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(dateText, { dateText = it; dirty = true }, label = { Text("日期（yyyy-MM-dd HH:mm）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = timestamp == null)
                    if (type != EventType.CONVERT && type != EventType.ACCOUNT_ADJUSTMENT) {
                        OutlinedTextField(
                            feeText,
                            { feeText = it; dirty = true },
                            label = { Text("整单实际费用") },
                            prefix = { Text("¥") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            isError = parsedFee == null || parsedFee < 0,
                            trailingIcon = { Icon(Icons.Outlined.Calculate, null) }
                        )
                        Text("按每行成交额比例分摊，最后一行承接分差。买入费用进入成本，卖出费用从收入扣除。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    OutlinedTextField(note, { note = it; dirty = true }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                }
            }

            val preview = previewTotal(type, lines, feeText)
            Surface(color = eventColor.copy(alpha = .10f), shape = RoundedCornerShape(17.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (type == EventType.BUY) "预计支出" else if (type == EventType.SELL) "预计到账" else if (type == EventType.CONVERT) "转换成本" else "账户变动", color = eventColor, style = MaterialTheme.typography.labelMedium)
                        Text(preview, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Text(account.label, style = MaterialTheme.typography.bodySmall, color = if (account == AccountType.FIAT_CNY) FiatGold else WalletBlue)
                }
            }
            Spacer(Modifier.height(96.dp))
        }
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("放弃未保存修改？") },
            text = { Text("这次输入不会被保存。") },
            confirmButton = { TextButton(onClick = onClose) { Text("放弃") } },
            dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("继续编辑") } }
        )
    }
    if (showRecipeDialog) {
        AlertDialog(
            onDismissRequest = { showRecipeDialog = false },
            title = { Text("保存转换配方") },
            text = {
                OutlinedTextField(
                    value = recipeName,
                    onValueChange = { recipeName = it },
                    label = { Text("配方名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val input = lines[0]
                        val output = lines[1]
                        onSaveRecipe(
                            ConversionRecipe(
                                name = recipeName,
                                inputItemId = input.itemId,
                                inputQuantity = input.quantity.toLong(),
                                outputItemId = output.itemId,
                                outputQuantity = output.quantity.toLong()
                            )
                        )
                        showRecipeDialog = false
                    },
                    enabled = recipeName.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showRecipeDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionLabel(text: String) { Text(text, style = MaterialTheme.typography.titleLarge) }

@Composable
private fun NewItemEditor(
    name: String,
    onName: (String) -> Unit,
    game: String,
    onGame: (String) -> Unit,
    type: ItemType,
    tracking: TrackingMode,
    typeMenu: Boolean,
    onOpenType: () -> Unit,
    onCloseType: () -> Unit,
    onType: (ItemType) -> Unit
) {
    Surface(color = RaisedBlue, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("新物品定义", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, onName, label = { Text("物品名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(game, onGame, label = { Text("游戏（可选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Box {
                OutlinedButton(onOpenType, modifier = Modifier.fillMaxWidth()) { Text(type.label) }
                DropdownMenu(typeMenu, onCloseType) { ItemType.entries.forEach { choice -> DropdownMenuItem({ Text(choice.label) }, onClick = { onType(choice) }) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(17.dp), tint = TextSecondary); Spacer(Modifier.width(6.dp))
                Text("默认${tracking.label}追踪，保存后可在持仓中修改定义。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GemToBoosterControls(
    selectedGemCost: Long,
    packQuantity: String,
    onGemCostChange: (Long) -> Unit,
    onPackQuantityChange: (String) -> Unit
) {
    val totalGems = gemQuantityForBoosterPacks(selectedGemCost, packQuantity)
    Surface(color = RaisedBlue, shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("每个卡包所需宝石", style = MaterialTheme.typography.titleMedium)
                Text("选择 Steam 卡包对应的宝石价格", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                BOOSTER_GEM_COSTS.forEach { cost ->
                    FilterChip(
                        selected = selectedGemCost == cost,
                        onClick = { onGemCostChange(cost) },
                        label = { Text(cost.toString()) }
                    )
                }
            }
            OutlinedTextField(
                value = packQuantity,
                onValueChange = onPackQuantityChange,
                label = { Text("转换卡包数量") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = packQuantity.toLongOrNull()?.let { it > 0 } != true
            )
            Surface(color = ConvertPurple.copy(alpha = .10f), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("消耗宝石总数", style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.weight(1f))
                    Text(totalGems.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ConvertPurple)
                }
            }
        }
    }
}

@Composable
private fun TransactionLineEditor(
    index: Int,
    type: EventType,
    line: EditorLine,
    isNewItem: Boolean,
    candidates: List<ItemEntity>,
    holding: Holding?,
    canDelete: Boolean,
    showQuantity: Boolean = true,
    onChange: (EditorLine) -> Unit,
    onDelete: () -> Unit
) {
    var itemMenu by remember(line.key) { mutableStateOf(false) }
    var lotMenu by remember(line.key) { mutableStateOf(false) }
    val item = candidates.firstOrNull { it.id == line.itemId } ?: holding?.item
    val quantity = line.quantity.toLongOrNull()
    val perUnit = unitPrice(line)
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (type == EventType.CONVERT) if (index == 0) "转出" else "产出" else "物品 ${index + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (canDelete) IconButton(onDelete, Modifier.size(38.dp)) { Icon(Icons.Outlined.DeleteOutline, "删除此行") }
            }
            if (!isNewItem) {
                Box {
                    OutlinedButton(onClick = { itemMenu = true }, Modifier.fillMaxWidth()) {
                        Text(item?.name ?: "选择物品", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    DropdownMenu(itemMenu, { itemMenu = false }) {
                        candidates.forEach { candidate -> DropdownMenuItem({ Text(candidate.name, maxLines = 1) }, onClick = { onChange(line.copy(itemId = candidate.id, selectedSourceLineId = null, selectedUnitOrdinal = null)); itemMenu = false }) }
                    }
                }
            }
            if (showQuantity || type != EventType.CONVERT) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (showQuantity) {
                        OutlinedTextField(
                            line.quantity,
                            { value ->
                                val updated = line.copy(quantity = value)
                                onChange(if (type == EventType.CONVERT && index == 0 && item?.type == ItemType.GEM_SACK && value.toLongOrNull() != null) updated else updated)
                            },
                            label = { Text("数量") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(.72f),
                            isError = quantity == null || quantity <= 0
                        )
                    }
                    if (type != EventType.CONVERT) {
                        OutlinedTextField(
                            line.amount,
                            { onChange(line.copy(amount = it)) },
                            label = { Text(line.mode.label) },
                            prefix = { Text("¥") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1.28f),
                            isError = perUnit == null || perUnit <= 0
                        )
                    }
                }
            }
            if (type != EventType.CONVERT) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(line.mode == AmountMode.UNIT, { onChange(line.copy(mode = AmountMode.UNIT, amount = convertedAmount(line, AmountMode.UNIT))) }, label = { Text("单价") })
                    FilterChip(line.mode == AmountMode.TOTAL, { onChange(line.copy(mode = AmountMode.TOTAL, amount = convertedAmount(line, AmountMode.TOTAL))) }, label = { Text("总额") })
                    if (quantity != null && perUnit != null) Text("单价 ${formatMoney(perUnit)} · 小计 ${formatMoney(perUnit * quantity)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
            if ((type == EventType.SELL || type == EventType.CONVERT && index == 0) && holding != null) {
                Text("可用 ${holding.quantity} 件", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                val requiresIndividual = holding.item.trackingMode == TrackingMode.INDIVIDUAL
                Box {
                    OutlinedButton(onClick = { lotMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (line.selectedSourceLineId == null) if (requiresIndividual) "选择具体独立件" else "FIFO（默认）"
                            else holding.lots.firstOrNull { it.sourceLineId == line.selectedSourceLineId && (line.selectedUnitOrdinal == null || it.unitOrdinal == line.selectedUnitOrdinal) }?.let { "${if (requiresIndividual) "#${it.unitOrdinal}" else "指定批次"} · ${formatMoney(it.cost.totalCents)}" } ?: "批次已失效"
                        )
                    }
                    DropdownMenu(lotMenu, { lotMenu = false }) {
                        if (!requiresIndividual) DropdownMenuItem({ Text("FIFO（默认）") }, onClick = { onChange(line.copy(selectedSourceLineId = null, selectedUnitOrdinal = null)); lotMenu = false })
                        holding.lots.filter { lot -> quantity == null || lot.remainingQuantity >= quantity }.forEachIndexed { lotIndex, lot ->
                            DropdownMenuItem(
                                { Text("${if (requiresIndividual) "独立件 #${lot.unitOrdinal ?: lotIndex + 1}" else "批次 ${lotIndex + 1}"} · 剩余 ${lot.remainingQuantity} · ${formatMoney(lot.cost.totalCents)}") },
                                onClick = { onChange(line.copy(selectedSourceLineId = lot.sourceLineId, selectedUnitOrdinal = lot.unitOrdinal)); lotMenu = false }
                            )
                        }
                    }
                }
                if (requiresIndividual && line.selectedSourceLineId == null) Text("逐件饰品卖出时必须指定具体独立件。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun buildDraft(
    type: EventType,
    dateText: String,
    platform: String,
    account: AccountType,
    feeText: String,
    note: String,
    lines: List<EditorLine>,
    allItems: List<ItemEntity>,
    holdings: List<Holding>,
    existing: EventView?
): Result<EventDraft> = runCatching {
    val timestamp = parseEditorDate(dateText) ?: error("日期格式应为 yyyy-MM-dd HH:mm")
    if (type == EventType.ACCOUNT_ADJUSTMENT) {
        val delta = parseMoney(feeText) ?: error("请输入有效的余额变动额")
        require(delta != 0L) { "余额变动不能为 0" }
        return@runCatching EventDraft(type, timestamp, platform.ifBlank { account.label }, 0, note, emptyList(), account, delta)
    }
    require(lines.isNotEmpty()) { "至少添加一个物品" }
    if (type == EventType.CONVERT) require(lines.size == 2) { "当前版本的转换必须单进单出" }
    val draftLines = lines.mapIndexed { index, line ->
        val quantity = line.quantity.toLongOrNull() ?: error("第 ${index + 1} 行数量无效")
        require(quantity > 0) { "数量必须大于 0" }
        val price = if (type == EventType.CONVERT) 0 else unitPrice(line) ?: error("第 ${index + 1} 行金额无效；总额必须能按数量精确分摊到分")
        require(type == EventType.CONVERT || price > 0) { "金额必须大于 0" }
        val direction = when (type) {
            EventType.BUY -> LineDirection.IN
            EventType.SELL -> LineDirection.OUT
            EventType.CONVERT -> if (index == 0) LineDirection.OUT else LineDirection.IN
            EventType.ACCOUNT_ADJUSTMENT -> error("无物品行")
        }
        DraftLine(line.itemId, direction, quantity, price)
    }
    val fee = if (type == EventType.CONVERT) 0 else parseMoney(feeText.ifBlank { "0" }) ?: error("手续费无效")
    require(fee >= 0) { "手续费不能为负数" }
    val allocations = lines.mapIndexedNotNull { index, line ->
        val itemId = line.itemId
        val holding = holdings.firstOrNull { it.item.id == itemId }
        if (type == EventType.SELL && holding?.item?.trackingMode == TrackingMode.INDIVIDUAL) require(line.selectedSourceLineId != null) { "第 ${index + 1} 行必须选择具体独立件" }
        line.selectedSourceLineId?.let { LotAllocationDraft(index, it, line.quantity.toLong(), line.selectedUnitOrdinal) }
    }
    EventDraft(type, timestamp, platform, fee, note, draftLines, account, allocations = allocations)
}

private fun newItemEntity(name: String, game: String, type: ItemType, tracking: TrackingMode): ItemEntity {
    require(name.trim().isNotBlank()) { "新物品名称不能为空" }
    return ItemEntity(name = name.trim(), game = game.trim(), type = type, trackingMode = tracking, trackingReviewed = true)
}

private fun unitPrice(line: EditorLine): Long? {
    val cents = parseMoney(line.amount) ?: return null
    val quantity = line.quantity.toLongOrNull() ?: return null
    if (quantity <= 0) return null
    return if (line.mode == AmountMode.UNIT) cents else if (cents % quantity == 0L) cents / quantity else null
}

private fun convertedAmount(line: EditorLine, target: AmountMode): String {
    if (line.mode == target) return line.amount
    val quantity = line.quantity.toLongOrNull() ?: return line.amount
    val cents = parseMoney(line.amount) ?: return line.amount
    val converted = if (target == AmountMode.TOTAL) runCatching { Math.multiplyExact(cents, quantity) }.getOrNull()
    else if (quantity > 0 && cents % quantity == 0L) cents / quantity else null
    return converted?.let(::centsInput) ?: line.amount
}

private fun calculateDefaultFee(lines: List<EditorLine>, rateBps: Int, fixed: Long): String {
    val gross = lines.mapNotNull { line -> unitPrice(line)?.let { price -> line.quantity.toLongOrNull()?.let { price * it } } }.sum()
    val rateFee = BigDecimal.valueOf(gross).multiply(BigDecimal.valueOf(rateBps.toLong())).divide(BigDecimal.valueOf(10_000), 0, RoundingMode.CEILING).longValueExact()
    return centsInput(rateFee + fixed)
}

private fun previewTotal(type: EventType, lines: List<EditorLine>, feeText: String): String {
    if (type == EventType.CONVERT) return "成本向量完整转移"
    if (type == EventType.ACCOUNT_ADJUSTMENT) return parseMoney(feeText)?.let { formatMoney(it, true) } ?: "—"
    val gross = lines.mapNotNull { line -> unitPrice(line)?.let { price -> line.quantity.toLongOrNull()?.let { price * it } } }.sum()
    val fee = parseMoney(feeText.ifBlank { "0" }) ?: return "—"
    return formatMoney(if (type == EventType.BUY) gross + fee else gross - fee)
}

private fun centsInput(cents: Long): String = BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString()

private fun formatEditorDate(timestamp: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))

private fun parseEditorDate(value: String): Long? = runCatching {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply { isLenient = false }.parse(value)?.time
}.getOrNull()
