package com.steamtrans.ledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LedgerV2CalculatorTest {
    private val stack = ItemEntity(1, "武器箱", "CS2", ItemType.CASE_CONTAINER, trackingMode = TrackingMode.STACKABLE)

    @Test fun `same-account sale reports comparable fiat pnl`() {
        val result = LedgerCalculator.calculate(
            listOf(stack),
            listOf(
                event(1, EventType.BUY, AccountType.FIAT_CNY, EventLineEntity(1, 1, 1, LineDirection.IN, 10, 100)),
                event(2, EventType.SELL, AccountType.FIAT_CNY, EventLineEntity(2, 2, 1, LineDirection.OUT, 2, 200))
            )
        )

        assertNull(result.error)
        assertEquals(-600, result.fiatBalanceCents)
        assertEquals(200, result.fiatRealizedPnlCents)
        assertEquals(0, result.walletRealizedPnlCents)
        assertEquals(0, result.crossAccountOutcomes.size)
    }

    @Test fun `cross-account sale never creates merged profit`() {
        val result = LedgerCalculator.calculate(
            listOf(stack),
            listOf(
                event(1, EventType.BUY, AccountType.FIAT_CNY, EventLineEntity(1, 1, 1, LineDirection.IN, 1, 100)),
                event(2, EventType.SELL, AccountType.STEAM_WALLET_CNY, EventLineEntity(2, 2, 1, LineDirection.OUT, 1, 200))
            )
        )

        assertNull(result.error)
        assertEquals(-100, result.fiatBalanceCents)
        assertEquals(200, result.walletBalanceCents)
        assertEquals(0, result.totalRealizedPnlCents)
        assertEquals(CostVector(fiatCents = 100), result.crossAccountOutcomes.single().cost)
        assertEquals(200, result.crossAccountOutcomes.single().proceedsCents)
    }

    @Test fun `v2 stackable lots use FIFO by default`() {
        val result = LedgerCalculator.calculate(
            listOf(stack),
            listOf(
                event(1, EventType.BUY, AccountType.FIAT_CNY, EventLineEntity(11, 1, 1, LineDirection.IN, 1, 100)),
                event(2, EventType.BUY, AccountType.FIAT_CNY, EventLineEntity(12, 2, 1, LineDirection.IN, 1, 200)),
                event(3, EventType.SELL, AccountType.FIAT_CNY, EventLineEntity(13, 3, 1, LineDirection.OUT, 1, 300))
            )
        )

        assertNull(result.error)
        assertEquals(200, result.holdings.single().costCents)
        assertEquals(200, result.fiatRealizedPnlCents)
    }

    @Test fun `manual allocation can select a later batch`() {
        val saleLine = EventLineEntity(13, 3, 1, LineDirection.OUT, 1, 300)
        val result = LedgerCalculator.calculate(
            listOf(stack),
            listOf(
                event(1, EventType.BUY, AccountType.FIAT_CNY, EventLineEntity(11, 1, 1, LineDirection.IN, 1, 100)),
                event(2, EventType.BUY, AccountType.FIAT_CNY, EventLineEntity(12, 2, 1, LineDirection.IN, 1, 200)),
                EventView(
                    LedgerEventEntity(3, EventType.SELL, 3, accountType = AccountType.FIAT_CNY, legacy = false),
                    listOf(saleLine),
                    listOf(LotAllocationEntity(1, saleLine.id, 12, 1))
                )
            )
        )

        assertNull(result.error)
        assertEquals(100, result.holdings.single().costCents)
        assertEquals(100, result.fiatRealizedPnlCents)
    }

    @Test fun `multi-line buy fee is proportional and last line owns rounding`() {
        val second = stack.copy(id = 2, name = "胶囊", type = ItemType.CAPSULE)
        val result = LedgerCalculator.calculate(
            listOf(stack, second),
            listOf(
                EventView(
                    LedgerEventEntity(1, EventType.BUY, 1, feeCents = 10, accountType = AccountType.FIAT_CNY, legacy = false),
                    listOf(
                        EventLineEntity(11, 1, 1, LineDirection.IN, 1, 100),
                        EventLineEntity(12, 1, 2, LineDirection.IN, 1, 300)
                    )
                )
            )
        )

        assertNull(result.error)
        assertEquals(102, result.holdings.first { it.item.id == 1L }.costCents)
        assertEquals(308, result.holdings.first { it.item.id == 2L }.costCents)
    }

    @Test fun `conversion conserves both cost-vector accounts`() {
        val gem = stack.copy(id = 2, name = "宝石", type = ItemType.GEM)
        val result = LedgerCalculator.calculate(
            listOf(stack, gem),
            listOf(
                event(1, EventType.BUY, AccountType.FIAT_CNY, EventLineEntity(11, 1, 1, LineDirection.IN, 1, 100)),
                EventView(
                    LedgerEventEntity(2, EventType.CONVERT, 2, legacy = false),
                    listOf(
                        EventLineEntity(12, 2, 1, LineDirection.OUT, 1),
                        EventLineEntity(13, 2, 2, LineDirection.IN, 1_000)
                    )
                )
            )
        )

        assertNull(result.error)
        assertEquals(CostVector(fiatCents = 100), result.holdings.single().cost)
    }

    @Test fun `booster conversion distributes cost across three card outputs`() {
        val booster = stack.copy(name = "测试卡包", type = ItemType.BOOSTER)
        val firstCard = stack.copy(id = 2, name = "卡牌 A", type = ItemType.CARD)
        val secondCard = stack.copy(id = 3, name = "卡牌 B", type = ItemType.CARD)
        val foilCard = stack.copy(id = 4, name = "闪卡 C", type = ItemType.FOIL_CARD)
        val result = LedgerCalculator.calculate(
            listOf(booster, firstCard, secondCard, foilCard),
            listOf(
                event(1, EventType.BUY, AccountType.STEAM_WALLET_CNY, EventLineEntity(11, 1, booster.id, LineDirection.IN, 1, 300)),
                EventView(
                    LedgerEventEntity(2, EventType.CONVERT, 2, accountType = AccountType.STEAM_WALLET_CNY, legacy = false),
                    listOf(
                        EventLineEntity(12, 2, booster.id, LineDirection.OUT, 1),
                        EventLineEntity(13, 2, firstCard.id, LineDirection.IN, 1),
                        EventLineEntity(14, 2, secondCard.id, LineDirection.IN, 1),
                        EventLineEntity(15, 2, foilCard.id, LineDirection.IN, 1)
                    )
                )
            )
        )

        assertNull(result.error)
        assertEquals(3, result.holdings.size)
        assertEquals(100, result.holdings.first { it.item.id == firstCard.id }.costCents)
        assertEquals(100, result.holdings.first { it.item.id == secondCard.id }.costCents)
        assertEquals(100, result.holdings.first { it.item.id == foilCard.id }.costCents)
    }

    @Test fun `voided event is retained but excluded`() {
        val result = LedgerCalculator.calculate(
            listOf(stack),
            listOf(
                EventView(
                    LedgerEventEntity(1, EventType.BUY, 1, status = EventStatus.VOIDED, accountType = AccountType.FIAT_CNY, legacy = false),
                    listOf(EventLineEntity(11, 1, 1, LineDirection.IN, 1, 100))
                )
            )
        )

        assertNull(result.error)
        assertEquals(0, result.holdings.size)
        assertEquals(1, result.events.size)
    }

    @Test fun `negative inventory is rejected`() {
        val result = LedgerCalculator.calculate(
            listOf(stack),
            listOf(event(1, EventType.SELL, AccountType.FIAT_CNY, EventLineEntity(11, 1, 1, LineDirection.OUT, 1, 100)))
        )
        assertNotNull(result.error)
    }

    private fun event(id: Long, type: EventType, account: AccountType, line: EventLineEntity) = EventView(
        LedgerEventEntity(id, type, id, accountType = account, legacy = false),
        listOf(line)
    )
}
