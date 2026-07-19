package com.steamtrans.ledger

import com.steamtrans.ledger.data.EventStatus
import com.steamtrans.ledger.data.EventType
import com.steamtrans.ledger.data.EventView
import com.steamtrans.ledger.data.LedgerEventEntity
import com.steamtrans.ledger.data.EventLineEntity
import com.steamtrans.ledger.data.LineDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversionDefaultsTest {
    @Test
    fun `gem bag quantity expands at one to one thousand`() {
        assertEquals("1000", gemQuantityForBags("1"))
        assertEquals("5000", gemQuantityForBags("5"))
    }

    @Test
    fun `blank invalid and overflowing quantities do not produce a default`() {
        assertEquals("", gemQuantityForBags(""))
        assertEquals("", gemQuantityForBags("not-a-number"))
        assertEquals("", gemQuantityForBags(Long.MAX_VALUE.toString()))
    }

    @Test
    fun `booster pack quantity multiplies every supported gem cost`() {
        BOOSTER_GEM_COSTS.forEach { cost ->
            assertEquals((cost * 3).toString(), gemQuantityForBoosterPacks(cost, "3"))
        }
    }

    @Test
    fun `booster gem total rejects unsupported invalid and overflowing input`() {
        assertEquals("", gemQuantityForBoosterPacks(401, "2"))
        assertEquals("", gemQuantityForBoosterPacks(400, ""))
        assertEquals("", gemQuantityForBoosterPacks(400, "0"))
        assertEquals("", gemQuantityForBoosterPacks(1_200, Long.MAX_VALUE.toString()))
    }

    @Test
    fun `supported booster gem cost is restored from existing quantities`() {
        assertEquals(429L, boosterGemCostFor("1287", "3"))
        assertEquals(null, boosterGemCostFor("1201", "1"))
        assertEquals(null, boosterGemCostFor("1000", "3"))
    }

    @Test
    fun `existing booster restores gem cost from latest active conversion`() {
        val older = conversion(id = 1, timestamp = 10, gemQuantity = 800, packQuantity = 2)
        val latest = conversion(id = 2, timestamp = 20, gemQuantity = 1_386, packQuantity = 3)
        val voided = conversion(id = 3, timestamp = 30, gemQuantity = 1_200, packQuantity = 1, status = EventStatus.VOIDED)

        assertEquals(
            462L,
            boosterGemCostForItem(
                boosterItemId = BOOSTER_ID,
                gemItemIds = setOf(GEM_ID),
                events = listOf(older, latest, voided),
                recipes = emptyList()
            )
        )
    }

    @Test
    fun `existing booster falls back to its saved recipe`() {
        assertEquals(
            545L,
            boosterGemCostForItem(
                boosterItemId = BOOSTER_ID,
                gemItemIds = setOf(GEM_ID),
                events = emptyList(),
                recipes = listOf(
                    ConversionRecipe("另一个卡包", GEM_ID, 400, 99, 1),
                    ConversionRecipe("目标卡包", GEM_ID, 1_090, BOOSTER_ID, 2)
                )
            )
        )
    }

    private fun conversion(
        id: Long,
        timestamp: Long,
        gemQuantity: Long,
        packQuantity: Long,
        status: EventStatus = EventStatus.ACTIVE
    ) = EventView(
        event = LedgerEventEntity(id = id, type = EventType.CONVERT, timestamp = timestamp, status = status),
        lines = listOf(
            EventLineEntity(id = id * 10, eventId = id, itemId = GEM_ID, direction = LineDirection.OUT, quantity = gemQuantity),
            EventLineEntity(id = id * 10 + 1, eventId = id, itemId = BOOSTER_ID, direction = LineDirection.IN, quantity = packQuantity)
        )
    )

    private companion object {
        const val GEM_ID = 1L
        const val BOOSTER_ID = 2L
    }
}
