package com.steamtrans.ledger

import com.steamtrans.ledger.data.EventStatus
import com.steamtrans.ledger.data.EventType
import com.steamtrans.ledger.data.EventView
import com.steamtrans.ledger.data.LineDirection

internal val BOOSTER_GEM_COSTS = listOf(400L, 429L, 462L, 500L, 545L, 600L, 667L, 705L, 857L, 1_000L, 1_200L)

/** 1 个宝石袋固定拆出 1000 个宝石；无效或溢出的输入不产生默认值。 */
internal fun gemQuantityForBags(bagQuantity: String): String {
    val bags = bagQuantity.toLongOrNull() ?: return ""
    return runCatching { Math.multiplyExact(bags, 1_000L).toString() }.getOrDefault("")
}

/** 根据 Steam 卡包的单包宝石成本与卡包数量计算宝石总消耗。 */
internal fun gemQuantityForBoosterPacks(gemsPerPack: Long, packQuantity: String): String {
    if (gemsPerPack !in BOOSTER_GEM_COSTS) return ""
    val packs = packQuantity.toLongOrNull()?.takeIf { it > 0 } ?: return ""
    return runCatching { Math.multiplyExact(gemsPerPack, packs).toString() }.getOrDefault("")
}

/** 从已有转换数量中还原单包宝石成本，仅接受 Steam 支持的预设档位。 */
internal fun boosterGemCostFor(gemQuantity: String, packQuantity: String): Long? {
    val gems = gemQuantity.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val packs = packQuantity.toLongOrNull()?.takeIf { it > 0 } ?: return null
    if (gems % packs != 0L) return null
    return (gems / packs).takeIf { it in BOOSTER_GEM_COSTS }
}

/**
 * 查找已有卡包对应的单包宝石成本。
 *
 * 实际发生且仍有效的转换最可信，因此优先使用最近一次历史转换；尚未转换过的
 * 卡包再回退到用户保存的转换配方。
 */
internal fun boosterGemCostForItem(
    boosterItemId: Long,
    gemItemIds: Set<Long>,
    events: List<EventView>,
    recipes: List<ConversionRecipe>
): Long? {
    if (boosterItemId <= 0 || gemItemIds.isEmpty()) return null

    val historicalCost = events.asSequence()
        .filter { it.event.type == EventType.CONVERT && it.event.status == EventStatus.ACTIVE }
        .sortedWith(compareByDescending<EventView> { it.event.timestamp }.thenByDescending { it.event.id })
        .mapNotNull { event ->
            val gemInput = event.lines.singleOrNull {
                it.direction == LineDirection.OUT && it.itemId in gemItemIds
            }
            val boosterOutput = event.lines.singleOrNull {
                it.direction == LineDirection.IN && it.itemId == boosterItemId
            }
            if (gemInput == null || boosterOutput == null) null
            else boosterGemCostFor(gemInput.quantity.toString(), boosterOutput.quantity.toString())
        }
        .firstOrNull()
    if (historicalCost != null) return historicalCost

    return recipes.asReversed().firstNotNullOfOrNull { recipe ->
        if (recipe.inputItemId !in gemItemIds || recipe.outputItemId != boosterItemId) null
        else boosterGemCostFor(recipe.inputQuantity.toString(), recipe.outputQuantity.toString())
    }
}
