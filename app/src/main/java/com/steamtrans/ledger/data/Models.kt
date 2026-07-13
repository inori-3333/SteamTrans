package com.steamtrans.ledger.data

import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import java.math.BigInteger

enum class ItemType(val label: String) {
    SKIN("皮肤"), GEM_SACK("宝石袋"), GEM("宝石"), CARD("普通卡"), FOIL_CARD("闪卡"),
    BOOSTER("卡包"), BACKGROUND("个人资料背景"), EMOTICON("表情")
}
enum class EventType(val label: String) { BUY("买入"), SELL("卖出"), CONVERT("转换") }
enum class LineDirection { IN, OUT }

@Entity(tableName = "items", indices = [Index(value = ["name", "game", "type"], unique = true)])
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val game: String = "",
    val type: ItemType,
    // 保留旧列以兼容已安装版本的 Room 数据库；估值功能已移除。
    val marketPriceCents: Long = 0,
    val note: String = "",
    val useCount: Int = 0
)

@Entity(tableName = "ledger_events")
data class LedgerEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: EventType,
    val timestamp: Long,
    val platform: String = "Steam",
    val feeCents: Long = 0,
    val note: String = ""
)

@Entity(
    tableName = "event_lines",
    foreignKeys = [
        ForeignKey(entity = LedgerEventEntity::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE),
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

data class Holding(
    val item: ItemEntity,
    val quantity: Long,
    val costCents: Long
) {
    val averageCostCents: Long get() = if (quantity == 0L) 0 else costCents / quantity
    /** Steam 收取售价的 15%，结果向上取整到分，确保实际到账不少于持仓成本。 */
    val breakEvenPriceCents: Long get() = breakEvenPrice(costCents, quantity)
}

fun breakEvenPrice(totalCostCents: Long, quantity: Long): Long {
    if (totalCostCents <= 0 || quantity <= 0) return 0
    val numerator = BigInteger.valueOf(totalCostCents).multiply(BigInteger.valueOf(100))
    val denominator = BigInteger.valueOf(quantity).multiply(BigInteger.valueOf(85))
    val (quotient, remainder) = numerator.divideAndRemainder(denominator)
    val result = if (remainder == BigInteger.ZERO) quotient else quotient + BigInteger.ONE
    return result.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
}

/** 按移动平均成本移出部分持仓，使用大整数避免 Long 乘法溢出。 */
internal fun proportionalCost(totalCostCents: Long, removedQuantity: Long, totalQuantity: Long): Long {
    require(totalCostCents >= 0 && removedQuantity >= 0 && totalQuantity > 0 && removedQuantity <= totalQuantity)
    if (removedQuantity == totalQuantity) return totalCostCents
    return BigInteger.valueOf(totalCostCents)
        .multiply(BigInteger.valueOf(removedQuantity))
        .divide(BigInteger.valueOf(totalQuantity))
        .toLong()
}

data class EventView(val event: LedgerEventEntity, val lines: List<EventLineEntity>)

data class LedgerEventRecord(
    @Embedded val event: LedgerEventEntity,
    @Relation(parentColumn = "id", entityColumn = "eventId") val lines: List<EventLineEntity>
) {
    fun asView() = EventView(event, lines)
}

data class LedgerSnapshot(
    val holdings: List<Holding> = emptyList(),
    val events: List<EventView> = emptyList(),
    val totalBoughtCents: Long = 0,
    val totalSoldCents: Long = 0,
    val totalRealizedPnlCents: Long = 0,
    val totalCostCents: Long = 0,
    val error: String? = null
) {
    val gemQuantity: Long get() = holdings.filter { it.item.type == ItemType.GEM }.sumOf { it.quantity }
    val itemQuantity: Long get() = holdings.filter { it.item.type != ItemType.GEM }.sumOf { it.quantity }
}

data class EventDraft(
    val type: EventType,
    val timestamp: Long = System.currentTimeMillis(),
    val platform: String = "Steam",
    val feeCents: Long = 0,
    val note: String = "",
    val lines: List<DraftLine>
)
data class DraftLine(
    val itemId: Long,
    val direction: LineDirection,
    val quantity: Long,
    val unitPriceCents: Long = 0
)
