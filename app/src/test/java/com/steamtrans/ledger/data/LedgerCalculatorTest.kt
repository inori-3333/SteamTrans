package com.steamtrans.ledger.data

import com.steamtrans.ledger.steamSaleFee
import com.steamtrans.ledger.parseMoney
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LedgerCalculatorTest {
    private val gemBag = ItemEntity(1, "宝石袋", "Steam", ItemType.GEM_SACK, 1200)
    private val gems = ItemEntity(2, "宝石", "Steam", ItemType.GEM, 1)

    @Test fun `weighted aggregate cost survives partial sale`() {
        val events = listOf(
            event(1, EventType.BUY, EventLineEntity(1, 1, 1, LineDirection.IN, 10, 100)),
            event(2, EventType.BUY, EventLineEntity(2, 2, 1, LineDirection.IN, 10, 200)),
            event(3, EventType.SELL, EventLineEntity(3, 3, 1, LineDirection.OUT, 5, 300))
        )
        val result = LedgerCalculator.calculate(listOf(gemBag), events)
        assertNull(result.error)
        assertEquals(15, result.holdings.single().quantity)
        assertEquals(2250, result.holdings.single().costCents)
        assertEquals(3000, result.totalBoughtCents)
        assertEquals(1500, result.totalSoldCents)
        assertEquals(750, result.totalRealizedPnlCents)
    }

    @Test fun `conversion preserves total cost`() {
        val events = listOf(
            event(1, EventType.BUY, EventLineEntity(1, 1, 1, LineDirection.IN, 1, 1000)),
            EventView(LedgerEventEntity(2, EventType.CONVERT, 2), listOf(
                EventLineEntity(2, 2, 1, LineDirection.OUT, 1),
                EventLineEntity(3, 2, 2, LineDirection.IN, 1000)
            ))
        )
        val result = LedgerCalculator.calculate(listOf(gemBag, gems), events)
        assertEquals(1000, result.totalCostCents)
        assertEquals(1000, result.holdings.single().quantity)
    }

    @Test fun `gem bag can be unpacked and gems converted to booster`() {
        val booster = ItemEntity(3, "测试游戏 卡包", "测试游戏", ItemType.BOOSTER)
        val events = listOf(
            event(1, EventType.BUY, EventLineEntity(1, 1, 1, LineDirection.IN, 1, 4380)),
            EventView(LedgerEventEntity(2, EventType.CONVERT, 2), listOf(
                EventLineEntity(2, 2, 1, LineDirection.OUT, 1),
                EventLineEntity(3, 2, 2, LineDirection.IN, 10_000)
            )),
            EventView(LedgerEventEntity(3, EventType.CONVERT, 3), listOf(
                EventLineEntity(4, 3, 2, LineDirection.OUT, 1_000),
                EventLineEntity(5, 3, 3, LineDirection.IN, 1)
            ))
        )

        val result = LedgerCalculator.calculate(listOf(gemBag, gems, booster), events)

        assertNull(result.error)
        assertEquals(4380, result.totalCostCents)
        assertEquals(9_000, result.holdings.first { it.item == gems }.quantity)
        assertEquals(438, result.holdings.first { it.item == booster }.costCents)
    }

    @Test fun `oversell reports inventory error`() {
        val result = LedgerCalculator.calculate(listOf(gemBag), listOf(event(1, EventType.SELL, EventLineEntity(1, 1, 1, LineDirection.OUT, 1, 100))))
        assert(result.error != null)
        assertEquals(0, result.totalSoldCents)
    }

    @Test fun `invalid multi item sale does not partially mutate holdings or totals`() {
        val events = listOf(
            event(1, EventType.BUY, EventLineEntity(1, 1, 1, LineDirection.IN, 2, 100)),
            event(2, EventType.BUY, EventLineEntity(2, 2, 2, LineDirection.IN, 1, 100)),
            EventView(
                LedgerEventEntity(3, EventType.SELL, 3),
                listOf(
                    EventLineEntity(3, 3, 1, LineDirection.OUT, 1, 300),
                    EventLineEntity(4, 3, 2, LineDirection.OUT, 2, 300)
                )
            )
        )

        val result = LedgerCalculator.calculate(listOf(gemBag, gems), events)

        assert(result.error != null)
        assertEquals(2, result.holdings.first { it.item.id == 1L }.quantity)
        assertEquals(1, result.holdings.first { it.item.id == 2L }.quantity)
        assertEquals(0, result.totalSoldCents)
        assertEquals(0, result.totalRealizedPnlCents)
    }

    @Test fun `break even price includes 15 percent Steam fee and rounds up`() {
        assertEquals(118, breakEvenPrice(100, 1))
        assertEquals(177, breakEvenPrice(300, 2))
        assertEquals(0, breakEvenPrice(0, 1))
    }

    @Test fun `Steam sale fee is 15 percent rounded up to cents`() {
        assertEquals(15, steamSaleFee(100))
        assertEquals(1, steamSaleFee(1))
        assertEquals(0, steamSaleFee(0))
    }

    @Test fun `money parser preserves cents exactly`() {
        assertEquals(29L, parseMoney("0.29"))
        assertEquals(1L, parseMoney("0.01"))
        assertEquals(1234L, parseMoney("12.34"))
        assertEquals(null, parseMoney("999999999999999999999.99"))
    }

    @Test fun `break even calculation does not overflow for large values`() {
        assertEquals(2L, breakEvenPrice(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test fun `gem holding divides sack quote by one thousand`() {
        val quote = MarketQuoteEntity(
            itemId = gems.id,
            grossPriceCents = 300,
            estimatedNetPriceCents = 255,
            source = PriceSource.STEAM_MARKET,
            timestamp = 1
        )
        val result = LedgerCalculator.calculate(
            listOf(gems),
            listOf(event(1, EventType.BUY, EventLineEntity(1, 1, gems.id, LineDirection.IN, 2_000, 1))),
            mapOf(gems.id to quote)
        )

        assertEquals(600L, result.marketGrossValueCents)
        assertEquals(510L, result.marketNetValueCents)
    }

    @Test fun `non gem holding keeps per item quote`() {
        val quote = MarketQuoteEntity(
            itemId = gemBag.id,
            grossPriceCents = 300,
            estimatedNetPriceCents = 255,
            source = PriceSource.STEAM_MARKET,
            timestamp = 1
        )
        val result = LedgerCalculator.calculate(
            listOf(gemBag),
            listOf(event(1, EventType.BUY, EventLineEntity(1, 1, gemBag.id, LineDirection.IN, 2, 100))),
            mapOf(gemBag.id to quote)
        )

        assertEquals(600L, result.marketGrossValueCents)
        assertEquals(510L, result.marketNetValueCents)
    }

    private fun event(id: Long, type: EventType, line: EventLineEntity) = EventView(LedgerEventEntity(id, type, id), listOf(line))
}
