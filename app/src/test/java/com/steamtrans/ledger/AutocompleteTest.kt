package com.steamtrans.ledger

import com.steamtrans.ledger.data.ItemEntity
import com.steamtrans.ledger.data.ItemType
import org.junit.Assert.assertEquals
import org.junit.Test

class AutocompleteTest {
    private val history = listOf(
        ItemEntity(1, "AK-47 | 红线", "Counter-Strike 2", ItemType.SKIN, useCount = 2),
        ItemEntity(2, "AK-47 | 二西莫夫", "Counter-Strike 2", ItemType.SKIN, useCount = 8),
        ItemEntity(3, "纪念品 AK-47", "Counter-Strike 2", ItemType.SKIN, useCount = 20),
        ItemEntity(4, "AK-47", "另一款游戏", ItemType.CARD, useCount = 99),
        ItemEntity(5, "测试卡牌", "Counter-Strike 2", ItemType.CARD, useCount = 1),
        ItemEntity(6, "另一张卡牌", "Counter-Strike", ItemType.CARD, useCount = 5)
    )

    @Test
    fun `item suggestions prefer prefixes and stay within the selected type`() {
        val suggestions = itemNameSuggestions("AK", history, ItemType.SKIN)

        assertEquals(listOf("AK-47 | 二西莫夫", "AK-47 | 红线", "纪念品 AK-47"), suggestions.map { it.name })
    }

    @Test
    fun `exact item name is not suggested after completion`() {
        assertEquals(emptyList<ItemEntity>(), itemNameSuggestions("ak-47 | 红线", history, ItemType.SKIN))
    }

    @Test
    fun `game suggestions are prefix first case insensitive and deduplicated`() {
        val suggestions = gameSuggestions("counter", history)

        assertEquals(listOf("Counter-Strike 2", "Counter-Strike"), suggestions)
    }
}
