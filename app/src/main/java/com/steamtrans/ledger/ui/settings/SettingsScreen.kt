package com.steamtrans.ledger.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.TableView
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.steamtrans.ledger.PendingRestore
import com.steamtrans.ledger.data.AccountType
import com.steamtrans.ledger.data.LedgerSnapshot
import com.steamtrans.ledger.data.PlatformProfileEntity
import com.steamtrans.ledger.formatDateTime
import com.steamtrans.ledger.formatMoney
import com.steamtrans.ledger.parseMoney
import com.steamtrans.ledger.ui.components.ScreenHeading
import com.steamtrans.ledger.ui.components.SectionHeading
import com.steamtrans.ledger.ui.theme.FiatGold
import com.steamtrans.ledger.ui.theme.Loss
import com.steamtrans.ledger.ui.theme.TextSecondary
import com.steamtrans.ledger.ui.theme.WalletBlue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    snapshot: LedgerSnapshot,
    itemCount: Int,
    quoteCount: Int,
    profiles: List<PlatformProfileEntity>,
    pendingRestore: PendingRestore?,
    onBack: () -> Unit,
    onUpdateProfile: (PlatformProfileEntity) -> Unit,
    onExportBackup: ((String) -> Unit) -> Unit,
    onExportCsv: ((ByteArray) -> Unit) -> Unit,
    onInspectBackup: (String) -> Unit,
    onCancelRestore: () -> Unit,
    onConfirmRestore: () -> Unit,
    onClearMarket: () -> Unit,
    onClearAll: () -> Unit
) {
    val context = LocalContext.current
    var pendingJson by remember { mutableStateOf<String?>(null) }
    var pendingCsv by remember { mutableStateOf<ByteArray?>(null) }
    var editingProfile by remember { mutableStateOf<PlatformProfileEntity?>(null) }
    var clearMarketConfirm by remember { mutableStateOf(false) }
    var clearAllStep by remember { mutableStateOf(0) }
    var clearText by remember { mutableStateOf("") }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val raw = pendingJson
        if (uri != null && raw != null) context.contentResolver.openOutputStream(uri)?.use { it.write(raw.toByteArray(Charsets.UTF_8)) }
        pendingJson = null
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = pendingCsv
        if (uri != null && bytes != null) context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        pendingCsv = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            if (raw != null) onInspectBackup(raw)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
        item {
            ScreenHeading("设置", "平台、数据和离线备份") {
                IconButton(onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
            }
        }
        item {
            SectionHeading("账户期初与校准")
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(17.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingBalance("人民币交易资金池", snapshot.fiatBalanceCents, FiatGold)
                    SettingBalance("Steam 钱包", snapshot.walletBalanceCents, WalletBlue)
                    Text("从总览账户卡点击“调整”设置期初值或校准余额。每次修改都会生成可追溯的调整流水。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }
        item {
            SectionHeading("平台档案", "买卖费用分别配置")
        }
        items(profiles, key = { it.id }) { profile ->
            SettingRow(
                icon = Icons.Outlined.AccountBalanceWallet,
                title = profile.name,
                body = "${profile.defaultAccountType.label} · 买入 ${feeDescription(profile.buyFeeRateBps, profile.buyFixedFeeCents)} · 卖出 ${feeDescription(profile.sellFeeRateBps, profile.sellFixedFeeCents)}",
                onClick = { editingProfile = profile }
            )
        }
        item {
            OutlinedButton(
                onClick = {
                    editingProfile = PlatformProfileEntity(
                        name = "",
                        defaultAccountType = AccountType.FIAT_CNY,
                        sortOrder = profiles.size + 10
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.size(7.dp)); Text("添加自定义平台") }
        }
        item {
            SectionHeading("备份与导出", "完全本地")
            SettingRow(Icons.Outlined.UploadFile, "导出完整 JSON 备份", "包含物品、流水、批次分配、平台、行情和趋势快照") {
                onExportBackup { raw ->
                    pendingJson = raw
                    backupLauncher.launch("steam-ledger-${fileDate()}.json")
                }
            }
            SettingRow(Icons.Outlined.TableView, "导出 CSV 压缩包", "UTF-8 BOM；包含流水、持仓、物品、账户和行情历史") {
                onExportCsv { bytes ->
                    pendingCsv = bytes
                    csvLauncher.launch("steam-ledger-csv-${fileDate()}.zip")
                }
            }
            SettingRow(Icons.Outlined.FileOpen, "从 JSON 恢复", "先预览数量和日期；替换前自动保留应急备份") {
                importLauncher.launch(arrayOf("application/json", "text/plain"))
            }
        }
        item {
            SectionHeading("行情数据", "$quoteCount 条缓存")
            SettingRow(Icons.Outlined.CloudOff, "清空行情与趋势快照", "不会删除流水、持仓或市场绑定") { clearMarketConfirm = true }
        }
        item {
            SectionHeading("危险操作")
            Surface(color = Loss.copy(alpha = .08f), shape = RoundedCornerShape(17.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("清空全部数据", style = MaterialTheme.typography.titleMedium, color = Loss)
                    Spacer(Modifier.height(5.dp))
                    Text("将删除 $itemCount 个物品、${snapshot.events.size} 条流水、全部批次、行情和趋势快照。平台默认档案会保留到下次启动重新初始化。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { clearAllStep = 1 }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.DeleteForever, null); Spacer(Modifier.size(7.dp)); Text("查看影响并清空") }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    editingProfile?.let { profile ->
        PlatformDialog(profile, onDismiss = { editingProfile = null }, onSave = { onUpdateProfile(it); editingProfile = null })
    }
    if (clearMarketConfirm) {
        AlertDialog(
            onDismissRequest = { clearMarketConfirm = false },
            title = { Text("清空行情数据？") },
            text = { Text("会删除 $quoteCount 条价格缓存和全部趋势快照。离线持仓与流水不受影响。") },
            confirmButton = { TextButton(onClick = { onClearMarket(); clearMarketConfirm = false }) { Text("清空") } },
            dismissButton = { TextButton(onClick = { clearMarketConfirm = false }) { Text("取消") } }
        )
    }
    if (clearAllStep > 0) {
        AlertDialog(
            onDismissRequest = { clearAllStep = 0; clearText = "" },
            icon = { Icon(Icons.Outlined.DeleteForever, null, tint = Loss) },
            title = { Text("永久清空本地账本？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("影响范围：$itemCount 个物品、${snapshot.events.size} 条流水、所有批次分配、行情及趋势。此操作无法撤销。")
                    OutlinedButton(onClick = {
                        onExportBackup { raw -> pendingJson = raw; backupLauncher.launch("steam-ledger-before-clear-${fileDate()}.json") }
                    }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Download, null); Spacer(Modifier.size(7.dp)); Text("立即导出备份") }
                    Text("输入“清空”继续", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    OutlinedTextField(clearText, { clearText = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { Button(onClick = { onClearAll(); clearAllStep = 0; clearText = "" }, enabled = clearText == "清空") { Text("永久清空") } },
            dismissButton = { TextButton(onClick = { clearAllStep = 0; clearText = "" }) { Text("取消") } }
        )
    }
    pendingRestore?.let { pending ->
        AlertDialog(
            onDismissRequest = onCancelRestore,
            icon = { Icon(Icons.Outlined.FileOpen, null) },
            title = { Text("恢复备份预览") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Schema v${pending.preview.schemaVersion} · 导出于 ${formatDateTime(pending.preview.exportedAt)}")
                    Text("${pending.preview.itemCount} 个物品 · ${pending.preview.eventCount} 条流水 · ${pending.preview.quoteCount} 条行情")
                    if (pending.preview.firstEventAt != null) Text("流水范围：${formatDateTime(pending.preview.firstEventAt)} 至 ${formatDateTime(pending.preview.lastEventAt ?: pending.preview.firstEventAt)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("校验通过后将整体替换当前数据；替换前会自动生成应急备份。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            },
            confirmButton = { Button(onClick = onConfirmRestore) { Text("确认恢复") } },
            dismissButton = { TextButton(onClick = onCancelRestore) { Text("取消") } }
        )
    }
}

@Composable
private fun SettingBalance(label: String, balance: Long, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = TextSecondary)
        Text(formatMoney(balance), color = color, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(15.dp)
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = TextSecondary)
        }
    }
}

@Composable
private fun PlatformDialog(profile: PlatformProfileEntity, onDismiss: () -> Unit, onSave: (PlatformProfileEntity) -> Unit) {
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var account by remember(profile.id) { mutableStateOf(profile.defaultAccountType) }
    var buyRate by remember(profile.id) { mutableStateOf((profile.buyFeeRateBps / 100.0).toString().trimEnd('0').trimEnd('.')) }
    var sellRate by remember(profile.id) { mutableStateOf((profile.sellFeeRateBps / 100.0).toString().trimEnd('0').trimEnd('.')) }
    var buyFixed by remember(profile.id) { mutableStateOf(java.math.BigDecimal.valueOf(profile.buyFixedFeeCents, 2).stripTrailingZeros().toPlainString()) }
    var sellFixed by remember(profile.id) { mutableStateOf(java.math.BigDecimal.valueOf(profile.sellFixedFeeCents, 2).stripTrailingZeros().toPlainString()) }
    val buyRateBps = (buyRate.toBigDecimalOrNull()?.multiply(java.math.BigDecimal(100)))?.toInt()
    val sellRateBps = (sellRate.toBigDecimalOrNull()?.multiply(java.math.BigDecimal(100)))?.toInt()
    val buyFixedCents = parseMoney(buyFixed)
    val sellFixedCents = parseMoney(sellFixed)
    val valid = name.trim().isNotBlank() && buyRateBps in 0..10_000 && sellRateBps in 0..10_000 && buyFixedCents != null && buyFixedCents >= 0 && sellFixedCents != null && sellFixedCents >= 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile.id == 0L) "添加平台" else "编辑 ${profile.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { if (!profile.builtIn) name = it }, label = { Text("平台名称") }, readOnly = profile.builtIn, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { AccountType.entries.forEach { value -> FilterChip(account == value, { account = value }, label = { Text(if (value == AccountType.FIAT_CNY) "人民币" else "Steam 钱包") }) } }
                Text("买入费用", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(buyRate, { buyRate = it }, label = { Text("百分比") }, suffix = { Text("%") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(buyFixed, { buyFixed = it }, label = { Text("固定费") }, prefix = { Text("¥") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f), singleLine = true)
                }
                Text("卖出费用", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(sellRate, { sellRate = it }, label = { Text("百分比") }, suffix = { Text("%") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(sellFixed, { sellFixed = it }, label = { Text("固定费") }, prefix = { Text("¥") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f), singleLine = true)
                }
            }
        },
        confirmButton = { Button(onClick = { if (valid) onSave(profile.copy(name = name.trim(), defaultAccountType = account, buyFeeRateBps = buyRateBps!!, buyFixedFeeCents = buyFixedCents!!, sellFeeRateBps = sellRateBps!!, sellFixedFeeCents = sellFixedCents!!)) }, enabled = valid) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun feeDescription(rateBps: Int, fixed: Long): String {
    val parts = buildList {
        if (rateBps > 0) add("${rateBps / 100.0}%")
        if (fixed > 0) add(formatMoney(fixed))
    }
    return parts.joinToString(" + ").ifBlank { "无" }
}

private fun fileDate(): String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
