package com.steamtrans.ledger.domain.backup

import android.content.Context
import com.steamtrans.ledger.data.EventLineEntity
import com.steamtrans.ledger.data.ItemEntity
import com.steamtrans.ledger.data.LedgerEventEntity
import com.steamtrans.ledger.data.LedgerExportData
import com.steamtrans.ledger.data.LedgerRepository
import com.steamtrans.ledger.data.LotAllocationEntity
import com.steamtrans.ledger.data.MarketQuoteEntity
import com.steamtrans.ledger.data.PlatformProfileEntity
import com.steamtrans.ledger.data.PortfolioSnapshotEntity
import com.steamtrans.ledger.formatDateTime
import com.steamtrans.ledger.formatMoney
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
data class LedgerBackup(
    val schemaVersion: Int = BackupService.SCHEMA_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val items: List<ItemEntity>,
    val events: List<LedgerEventEntity>,
    val lines: List<EventLineEntity>,
    val allocations: List<LotAllocationEntity>,
    val platformProfiles: List<PlatformProfileEntity>,
    val quotes: List<MarketQuoteEntity>,
    val portfolioSnapshots: List<PortfolioSnapshotEntity>
)

data class BackupPreview(
    val schemaVersion: Int,
    val exportedAt: Long,
    val itemCount: Int,
    val eventCount: Int,
    val quoteCount: Int,
    val firstEventAt: Long?,
    val lastEventAt: Long?
)

class BackupService(
    private val context: Context,
    private val repository: LedgerRepository
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        json.encodeToString(repository.exportData().toBackup())
    }

    fun preview(raw: String): BackupPreview {
        val backup = parseAndValidate(raw)
        val dates = backup.events.map { it.timestamp }
        return BackupPreview(
            schemaVersion = backup.schemaVersion,
            exportedAt = backup.exportedAt,
            itemCount = backup.items.size,
            eventCount = backup.events.size,
            quoteCount = backup.quotes.size,
            firstEventAt = dates.minOrNull(),
            lastEventAt = dates.maxOrNull()
        )
    }

    suspend fun restoreJson(raw: String): File = withContext(Dispatchers.IO) {
        val incoming = parseAndValidate(raw)
        val directory = File(context.filesDir, "backups").apply { mkdirs() }
        val emergency = File(directory, "emergency-before-restore-${Instant.now().toEpochMilli()}.json")
        emergency.writeText(exportJson(), Charsets.UTF_8)
        repository.restoreData(incoming.toExportData())
        emergency
    }

    suspend fun exportCsvZip(): ByteArray = withContext(Dispatchers.IO) {
        val data = repository.exportData()
        val items = data.items.associateBy { it.id }
        val snapshot = repository.currentSnapshot()
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putCsv(
                    "流水.csv",
                    listOf(listOf("流水ID", "状态", "类型", "日期", "平台", "结算账户", "手续费", "备注", "物品ID", "物品", "方向", "数量", "单价", "成交额")) +
                        data.events.sortedWith(compareBy<LedgerEventEntity> { it.timestamp }.thenBy { it.id }).flatMap { event ->
                            val lines = data.lines.filter { it.eventId == event.id }
                            if (lines.isEmpty()) {
                                listOf(listOf(event.id, event.status.label, event.type.label, formatDateTime(event.timestamp), event.platform, event.accountType.label, formatMoney(event.feeCents), event.note, "", "", "", "", "", formatMoney(event.accountDeltaCents)))
                            } else lines.map { line ->
                                listOf(event.id, event.status.label, event.type.label, formatDateTime(event.timestamp), event.platform, event.accountType.label, formatMoney(event.feeCents), event.note, line.itemId, items[line.itemId]?.name.orEmpty(), line.direction.name, line.quantity, formatMoney(line.unitPriceCents), formatMoney(line.quantity * line.unitPriceCents))
                            }
                        }
                )
                zip.putCsv(
                    "当前持仓.csv",
                    listOf(listOf("物品ID", "物品", "游戏", "类别", "追踪方式", "数量", "人民币成本", "钱包成本", "挂牌总值", "预计到手钱包价值", "行情时间")) +
                        snapshot.holdings.map { holding ->
                            listOf(holding.item.id, holding.item.name, holding.item.game, holding.item.type.label, holding.item.trackingMode.label, holding.quantity, formatMoney(holding.cost.fiatCents), formatMoney(holding.cost.walletCents), holding.marketGrossValueCents?.let { formatMoney(it) }.orEmpty(), holding.marketNetValueCents?.let { formatMoney(it) }.orEmpty(), holding.quote?.timestamp?.let(::formatDateTime).orEmpty())
                        }
                )
                zip.putCsv(
                    "物品.csv",
                    listOf(listOf("ID", "名称", "游戏", "类别", "追踪方式", "可交易", "Steam AppID", "市场名称", "备注")) +
                        data.items.map { listOf(it.id, it.name, it.game, it.type.label, it.trackingMode.label, it.tradeable, it.marketAppId ?: "", it.marketHashName.orEmpty(), it.note) }
                )
                zip.putCsv(
                    "账户.csv",
                    listOf(listOf("账户", "当前余额", "持仓成本", "已实现盈亏")) + listOf(
                        listOf("人民币资金池", formatMoney(snapshot.fiatBalanceCents), formatMoney(snapshot.fiatHoldingCostCents), formatMoney(snapshot.fiatRealizedPnlCents)),
                        listOf("Steam 钱包", formatMoney(snapshot.walletBalanceCents), formatMoney(snapshot.walletHoldingCostCents), formatMoney(snapshot.walletRealizedPnlCents))
                    )
                )
                zip.putCsv(
                    "行情历史.csv",
                    listOf(listOf("物品ID", "物品", "来源", "挂牌价", "预计到手价", "成交量", "更新时间")) +
                        data.quotes.sortedBy { it.timestamp }.map { listOf(it.itemId, items[it.itemId]?.name.orEmpty(), it.source.label, formatMoney(it.grossPriceCents), formatMoney(it.estimatedNetPriceCents), it.volume, formatDateTime(it.timestamp)) }
                )
            }
            bytes.toByteArray()
        }
    }

    private fun parseAndValidate(raw: String): LedgerBackup {
        val backup = runCatching { json.decodeFromString<LedgerBackup>(raw) }
            .getOrElse { throw IllegalArgumentException("备份文件无法解析：${it.message}") }
        require(backup.schemaVersion in 2..SCHEMA_VERSION) { "不支持的备份版本 ${backup.schemaVersion}" }
        require(backup.items.map { it.id }.distinct().size == backup.items.size) { "备份包含重复物品 ID" }
        require(backup.events.map { it.id }.distinct().size == backup.events.size) { "备份包含重复流水 ID" }
        require(backup.lines.map { it.id }.distinct().size == backup.lines.size) { "备份包含重复流水行 ID" }
        val itemIds = backup.items.mapTo(hashSetOf()) { it.id }
        val eventIds = backup.events.mapTo(hashSetOf()) { it.id }
        val lineIds = backup.lines.mapTo(hashSetOf()) { it.id }
        require(backup.lines.all { it.itemId in itemIds && it.eventId in eventIds }) { "备份中的流水引用了缺失物品或事件" }
        require(backup.allocations.all { it.eventLineId in lineIds && it.sourceLineId in lineIds }) { "备份中的批次引用不完整" }
        require(backup.quotes.all { it.itemId in itemIds }) { "备份中的行情引用了缺失物品" }
        return backup
    }

    private fun ZipOutputStream.putCsv(name: String, rows: List<List<Any?>>) {
        putNextEntry(ZipEntry(name))
        write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        rows.forEach { row ->
            write(row.joinToString(",") { csvCell(it?.toString().orEmpty()) }.plus("\r\n").toByteArray(Charsets.UTF_8))
        }
        closeEntry()
    }

    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"${value.replace("\"", "\"\"")}\"" else value

    companion object { const val SCHEMA_VERSION = 2 }
}

private fun LedgerExportData.toBackup() = LedgerBackup(
    items = items,
    events = events,
    lines = lines,
    allocations = allocations,
    platformProfiles = platformProfiles,
    quotes = quotes,
    portfolioSnapshots = portfolioSnapshots
)

private fun LedgerBackup.toExportData() = LedgerExportData(
    items = items,
    events = events,
    lines = lines,
    allocations = allocations,
    platformProfiles = platformProfiles,
    quotes = quotes,
    portfolioSnapshots = portfolioSnapshots
)
