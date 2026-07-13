package com.steamtrans.ledger.ui.components

import com.steamtrans.ledger.data.ItemType
import org.junit.Assert.assertEquals
import org.junit.Test

class ItemArtworkTest {
    @Test
    fun everyItemCategoryHasItsOwnFallbackIcon() {
        val iconNames = ItemType.entries.map { defaultArtworkIcon(it).name }

        assertEquals(ItemType.entries.size, iconNames.distinct().size)
    }
}
