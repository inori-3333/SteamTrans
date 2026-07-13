package com.steamtrans.ledger.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import kotlinx.serialization.Serializable
import java.math.BigInteger

@Serializable
enum class AccountType(val label: String) {
    FIAT_CNY("人民币资金池"),
    STEAM_WALLET_CNY("Steam 钱包")
}

@Serializable
enum class TrackingMode(val label: String) { INDIVIDUAL("逐件"), STACKABLE("按量") }

@Serializable
enum class ItemType(val label: String, val defaultTracking: TrackingMode) {
    SKIN("饰品", TrackingMode.INDIVIDUAL),
    CASE_CONTAINER("武器箱 / 容器", TrackingMode.STACKABLE),
    CAPSULE("胶囊", TrackingMode.STACKABLE),
    SOUVENIR_PACKAGE("纪念包", TrackingMode.STACKABLE),
    GEM_SACK("宝石袋", TrackingMode.STACKABLE),
    GEM("宝石", TrackingMode.STACKABLE),
    CARD("普通卡", TrackingMode.STACKABLE),
    FOIL_CARD("闪卡", TrackingMode.STACKABLE),
    BOOSTER("卡包", TrackingMode.STACKABLE),
    BACKGROUND("个人资料背景", TrackingMode.STACKABLE),
    EMOTICON("表情", TrackingMode.STACKABLE),
    OTHER("其他", TrackingMode.STACKABLE)
}

@Serializable
enum class EventType(val label: String) {
    BUY("买入"), SELL("卖出"), CONVERT("转换"), ACCOUNT_ADJUSTMENT("余额调整")
}

@Serializable
enum class EventStatus(val label: String) { ACTIVE("有效"), VOIDED("已作废") }
@Serializable
enum class PriceSource(val label: String) { STEAM_MARKET("Steam 市场"), MANUAL("手动") }
@Serializable
enum class LineDirection { IN, OUT }

@Serializable
@Entity(tableName = "items", indices = [Index(value = ["name", "game", "type"], unique = true)])
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val game: String = "",
    val type: ItemType,
    // 保留旧列，确保 Room v1 数据可无损迁移。
    val marketPriceCents: Long = 0,
    val note: String = "",
    val useCount: Int = 0,
    @ColumnInfo(defaultValue = "'STACKABLE'") val trackingMode: TrackingMode = type.defaultTracking,
    @ColumnInfo(defaultValue = "0") val tradeable: Boolean = false,
    val marketAppId: Int? = null,
    val marketHashName: String? = null,
    val imageUrl: String? = null,
    @ColumnInfo(defaultValue = "1") val trackingReviewed: Boolean = true,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0
)

@Serializable
@Entity(tableName = "ledger_events")
data class LedgerEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: EventType,
    val timestamp: Long,
    val platform: String = "Steam",
    val feeCents: Long = 0,
    val note: String = "",
    @ColumnInfo(defaultValue = "'ACTIVE'") val status: EventStatus = EventStatus.ACTIVE,
    @ColumnInfo(defaultValue = "'STEAM_WALLET_CNY'") val accountType: AccountType = AccountType.STEAM_WALLET_CNY,
    @ColumnInfo(defaultValue = "0") val accountDeltaCents: Long = 0,
    @ColumnInfo(defaultValue = "0") val feeRateBps: Int = 0,
    @ColumnInfo(defaultValue = "0") val fixedFeeCents: Long = 0,
    // Kotlin 默认 true 保持旧领域测试语义；v2 仓储新增事件时会显式写 false。
    @ColumnInfo(defaultValue = "0") val legacy: Boolean = true,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0
)

@Serializable
@Entity(
    tableName = "event_lines",
    foreignKeys = [
        ForeignKey(
            entity = LedgerEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(entity = ItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"])
    ],
    indices = [Index("eventId"), Index("itemId")]
)
data class EventLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val itemId: Long,
    val direction: LineDirection,
    val quantity: Long,
    val unitPriceCents: Long = 0,
    val allocatedCostCents: Long = 0
)

@Serializable
@Entity(
    tableName = "lot_allocations",
    foreignKeys = [
        ForeignKey(
            entity = EventLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventLineId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = EventLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceLineId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("eventLineId"), Index("sourceLineId")]
)
data class LotAllocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventLineId: Long,
    val sourceLineId: Long,
    val quantity: Long,
    val unitOrdinal: Int? = null
)

@Serializable
@Entity(tableName = "platform_profiles", indices = [Index(value = ["name"], unique = true)])
data class PlatformProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val defaultAccountType: AccountType,
    val buyFeeRateBps: Int = 0,
    val buyFixedFeeCents: Long = 0,
    val sellFeeRateBps: Int = 0,
    val sellFixedFeeCents: Long = 0,
    val builtIn: Boolean = false,
    val enabled: Boolean = true,
    val sortOrder: Int = 0
)

@Serializable
@Entity(
    tableName = "market_quotes",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("itemId"), Index(value = ["itemId", "timestamp"], unique = true)]
)
data class MarketQuoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val grossPriceCents: Long,
    val estimatedNetPriceCents: Long,
    val source: PriceSource,
    val timestamp: Long,
    val volume: String = ""
)

@Serializable
@Entity(tableName = "portfolio_snapshots", indices = [Index("timestamp")])
data class PortfolioSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val trigger: String,
    val fiatBalanceCents: Long,
    val walletBalanceCents: Long,
    val fiatHoldingCostCents: Long,
    val walletHoldingCostCents: Long,
    val marketGrossCents: Long,
    val marketNetCents: Long
)

data class CostVector(
    val fiatCents: Long = 0,
    val walletCents: Long = 0
) {
    val totalCents: Long get() = Math.addExact(fiatCents, walletCents)
    val isZero: Boolean get() = fiatCents == 0L && walletCents == 0L
    fun only(account: AccountType): Boolean = when (account) {
        AccountType.FIAT_CNY -> walletCents == 0L
        AccountType.STEAM_WALLET_CNY -> fiatCents == 0L
    }
    fun amount(account: AccountType): Long = when (account) {
        AccountType.FIAT_CNY -> fiatCents
        AccountType.STEAM_WALLET_CNY -> walletCents
    }
    operator fun plus(other: CostVector) = CostVector(
        Math.addExact(fiatCents, other.fiatCents),
        Math.addExact(walletCents, other.walletCents)
    )
    operator fun minus(other: CostVector) = CostVector(
        Math.subtractExact(fiatCents, other.fiatCents),
        Math.subtractExact(walletCents, other.walletCents)
    )

    companion object {
        fun of(account: AccountType, cents: Long) = when (account) {
            AccountType.FIAT_CNY -> CostVector(fiatCents = cents)
            AccountType.STEAM_WALLET_CNY -> CostVector(walletCents = cents)
        }
    }
}

data class HoldingLot(
    val sourceLineId: Long,
    val sourceEventId: Long,
    val acquiredAt: Long,
    val remainingQuantity: Long,
    val cost: CostVector,
    val unitOrdinal: Int? = null,
    val legacyCarry: Boolean = false
)

data class Holding(
    val item: ItemEntity,
    val quantity: Long,
    val cost: CostVector,
    val lots: List<HoldingLot> = emptyList(),
    val quote: MarketQuoteEntity? = null
) {
    val costCents: Long get() = cost.totalCents
    val averageCostCents: Long get() = if (quantity == 0L) 0 else costCents / quantity
    val breakEvenPriceCents: Long get() = breakEvenPrice(costCents, quantity)
    val marketGrossValueCents: Long? get() = quote?.let { marketValueCents(it.grossPriceCents) }
    val marketNetValueCents: Long? get() = quote?.let { marketValueCents(it.estimatedNetPriceCents) }

    private fun marketValueCents(quotePriceCents: Long): Long {
        val quotedQuantity = if (item.type == ItemType.GEM) 1_000L else 1L
        return BigInteger.valueOf(quotePriceCents)
            .multiply(BigInteger.valueOf(quantity))
            .divide(BigInteger.valueOf(quotedQuantity))
            .longValueExact()
    }
}

fun breakEvenPrice(totalCostCents: Long, quantity: Long): Long {
    if (totalCostCents <= 0 || quantity <= 0) return 0
    val numerator = BigInteger.valueOf(totalCostCents).multiply(BigInteger.valueOf(100))
    val denominator = BigInteger.valueOf(quantity).multiply(BigInteger.valueOf(85))
    val (quotient, remainder) = numerator.divideAndRemainder(denominator)
    val result = if (remainder == BigInteger.ZERO) quotient else quotient + BigInteger.ONE
    return result.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
}

internal fun proportionalCost(totalCostCents: Long, removedQuantity: Long, totalQuantity: Long): Long {
    require(totalCostCents >= 0 && removedQuantity >= 0 && totalQuantity > 0 && removedQuantity <= totalQuantity)
    if (removedQuantity == totalQuantity) return totalCostCents
    return BigInteger.valueOf(totalCostCents)
        .multiply(BigInteger.valueOf(removedQuantity))
        .divide(BigInteger.valueOf(totalQuantity))
        .toLong()
}

internal fun proportionalCost(cost: CostVector, removedQuantity: Long, totalQuantity: Long): CostVector {
    if (removedQuantity == totalQuantity) return cost
    return CostVector(
        proportionalCost(cost.fiatCents, removedQuantity, totalQuantity),
        proportionalCost(cost.walletCents, removedQuantity, totalQuantity)
    )
}

data class EventView(
    val event: LedgerEventEntity,
    val lines: List<EventLineEntity>,
    val allocations: List<LotAllocationEntity> = emptyList()
)

data class LedgerEventRecord(
    @Embedded val event: LedgerEventEntity,
    @Relation(parentColumn = "id", entityColumn = "eventId") val lines: List<EventLineEntity>
) {
    fun asView(allocations: List<LotAllocationEntity> = emptyList()) = EventView(event, lines, allocations)
}

data class CrossAccountOutcome(
    val eventId: Long,
    val cost: CostVector,
    val proceedsAccount: AccountType,
    val proceedsCents: Long
)

data class LedgerSnapshot(
    val holdings: List<Holding> = emptyList(),
    val events: List<EventView> = emptyList(),
    val totalBoughtCents: Long = 0,
    val totalSoldCents: Long = 0,
    val totalRealizedPnlCents: Long = 0,
    val totalCostCents: Long = 0,
    val fiatBalanceCents: Long = 0,
    val walletBalanceCents: Long = 0,
    val fiatRealizedPnlCents: Long = 0,
    val walletRealizedPnlCents: Long = 0,
    val fiatHoldingCostCents: Long = 0,
    val walletHoldingCostCents: Long = 0,
    val marketGrossValueCents: Long = 0,
    val marketNetValueCents: Long = 0,
    val unpricedHoldingCount: Int = 0,
    val crossAccountOutcomes: List<CrossAccountOutcome> = emptyList(),
    val error: String? = null
) {
    val gemQuantity: Long get() = holdings.filter { it.item.type == ItemType.GEM }.sumOf { it.quantity }
    val itemQuantity: Long get() = holdings.filter { it.item.type != ItemType.GEM }.sumOf { it.quantity }
    fun balance(account: AccountType): Long = when (account) {
        AccountType.FIAT_CNY -> fiatBalanceCents
        AccountType.STEAM_WALLET_CNY -> walletBalanceCents
    }
}

data class EventDraft(
    val type: EventType,
    val timestamp: Long = System.currentTimeMillis(),
    val platform: String = "Steam",
    val feeCents: Long = 0,
    val note: String = "",
    val lines: List<DraftLine>,
    val accountType: AccountType = AccountType.STEAM_WALLET_CNY,
    val accountDeltaCents: Long = 0,
    val feeRateBps: Int = 0,
    val fixedFeeCents: Long = 0,
    val allocations: List<LotAllocationDraft> = emptyList()
)

data class DraftLine(
    val itemId: Long,
    val direction: LineDirection,
    val quantity: Long,
    val unitPriceCents: Long = 0
)

data class LotAllocationDraft(
    val lineIndex: Int,
    val sourceLineId: Long,
    val quantity: Long,
    val unitOrdinal: Int? = null
)

data class MarketBindingDraft(
    val appId: Int,
    val marketHashName: String,
    val imageUrl: String? = null,
    val tradeable: Boolean = true
)
