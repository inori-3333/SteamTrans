package com.steamtrans.ledger

import com.steamtrans.ledger.data.ItemEntity
import com.steamtrans.ledger.data.ItemType
import java.util.Locale

private const val SuggestionLimit = 5

/**
 * 物品名历史按“前缀 > 同游戏 > 使用次数 > 短名称”排序。
 * 类型隔离可以避免在皮肤名称中推荐卡牌或背景等无关记录。
 */
internal fun itemNameSuggestions(
    query: String,
    history: List<ItemEntity>,
    type: ItemType,
    game: String = "",
    limit: Int = SuggestionLimit
): List<ItemEntity> {
    val term = query.trim()
    if (term.isEmpty() || limit <= 0) return emptyList()
    val normalizedGame = game.trim()

    return history.asSequence()
        .filter { it.type == type }
        .filter { !it.name.equals(term, ignoreCase = true) && it.name.contains(term, ignoreCase = true) }
        .sortedWith(
            compareBy<ItemEntity> { if (it.name.startsWith(term, ignoreCase = true)) 0 else 1 }
                .thenBy { if (normalizedGame.isNotEmpty() && it.game.equals(normalizedGame, ignoreCase = true)) 0 else 1 }
                .thenByDescending { it.useCount }
                .thenBy { it.name.length }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
        .distinctBy { it.name.lowercase(Locale.ROOT) }
        .take(limit)
        .toList()
}

/** 游戏输入框只使用历史游戏名，不会混入物品名称。 */
internal fun gameSuggestions(
    query: String,
    history: List<ItemEntity>,
    limit: Int = SuggestionLimit
): List<String> {
    val term = query.trim()
    if (term.isEmpty() || limit <= 0) return emptyList()

    return history.asSequence()
        .filter { it.game.isNotBlank() }
        .filter { !it.game.equals(term, ignoreCase = true) && it.game.contains(term, ignoreCase = true) }
        .sortedWith(
            compareBy<ItemEntity> { if (it.game.startsWith(term, ignoreCase = true)) 0 else 1 }
                .thenByDescending { it.useCount }
                .thenBy { it.game.length }
                .thenBy { it.game.lowercase(Locale.ROOT) }
        )
        .distinctBy { it.game.lowercase(Locale.ROOT) }
        .take(limit)
        .map { it.game }
        .toList()
}
