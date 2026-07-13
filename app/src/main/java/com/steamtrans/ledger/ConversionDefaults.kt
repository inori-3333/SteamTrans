package com.steamtrans.ledger

/** 1 个宝石袋固定拆出 1000 个宝石；无效或溢出的输入不产生默认值。 */
internal fun gemQuantityForBags(bagQuantity: String): String {
    val bags = bagQuantity.toLongOrNull() ?: return ""
    return runCatching { Math.multiplyExact(bags, 1_000L).toString() }.getOrDefault("")
}
