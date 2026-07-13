package com.steamtrans.ledger.ui.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.steamtrans.ledger.data.AccountType
import com.steamtrans.ledger.formatMoney
import com.steamtrans.ledger.parseMoney
import com.steamtrans.ledger.ui.theme.TextSecondary
import java.math.BigDecimal

@Composable
fun MigrationSetupDialog(
    currentWalletBalance: Long,
    currentFiatBalance: Long,
    unknownPlatforms: List<String>,
    onComplete: (Map<String, AccountType>, Long, Long?) -> Unit
) {
    var wallet by remember { mutableStateOf(BigDecimal.valueOf(currentWalletBalance, 2).toPlainString()) }
    var fiat by remember { mutableStateOf("") }
    val mappings = remember(unknownPlatforms) {
        mutableStateMapOf<String, AccountType>().apply { unknownPlatforms.forEach { put(it, AccountType.FIAT_CNY) } }
    }
    val walletCents = parseMoney(wallet)
    val fiatCents = fiat.takeIf { it.isNotBlank() }?.let(::parseMoney)
    val valid = walletCents != null && (fiat.isBlank() || fiatCents != null)

    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Outlined.SyncAlt, null) },
        title = { Text("完成 v2 账本迁移") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("旧流水与移动平均成本已保留。请确认未知平台的结算账户，并用当前余额生成迁移校准记录。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                unknownPlatforms.forEach { platform ->
                    Column {
                        Text(platform, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AccountType.entries.forEach { account ->
                                FilterChip(
                                    selected = mappings[platform] == account,
                                    onClick = { mappings[platform] = account },
                                    label = { Text(if (account == AccountType.FIAT_CNY) "人民币" else "Steam 钱包") }
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    wallet,
                    { wallet = it },
                    label = { Text("当前 Steam 钱包余额") },
                    prefix = { Text("¥") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = wallet.isNotBlank() && walletCents == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    fiat,
                    { fiat = it },
                    label = { Text("当前人民币交易资金池（可选）") },
                    prefix = { Text("¥") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = fiat.isNotBlank() && fiatCents == null,
                    supportingText = { Text("留空则沿用旧流水推算的 ${formatMoney(currentFiatBalance)}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = { if (valid) onComplete(mappings.toMap(), walletCents!!, fiatCents) }, enabled = valid) { Text("校验并完成迁移") } }
    )
}
