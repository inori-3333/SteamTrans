package com.steamtrans.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.steamtrans.ledger.data.DraftLine
import com.steamtrans.ledger.data.EventDraft
import com.steamtrans.ledger.data.EventType
import com.steamtrans.ledger.data.ItemEntity
import com.steamtrans.ledger.data.ItemType
import com.steamtrans.ledger.data.LedgerDatabase
import com.steamtrans.ledger.data.LedgerRepository
import com.steamtrans.ledger.data.LineDirection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversionPersistenceTest {
    @Test
    fun gemBagConversionSurvivesActivityRestart() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val db = LedgerDatabase.get(context)
            db.clearAllTables()
            val repository = LedgerRepository(db)

            repository.addItemWithInitialBuy(
            ItemEntity(name = "宝石袋", type = ItemType.GEM_SACK),
            EventDraft(
                type = EventType.BUY,
                lines = listOf(DraftLine(0, LineDirection.IN, 1, 4380))
            )
        )
            val bag = repository.items.first().single()
            repository.addItemWithConversionOutput(
            ItemEntity(name = "宝石", type = ItemType.GEM),
            EventDraft(
                type = EventType.CONVERT,
                lines = listOf(
                    DraftLine(bag.id, LineDirection.OUT, 1),
                    DraftLine(0, LineDirection.IN, 10_000)
                )
            )
        )

            val snapshot = repository.snapshot.first()
            assertNull(snapshot.error)
            assertEquals(10_000, snapshot.holdings.single().quantity)
            assertEquals(4380, snapshot.holdings.single().costCents)

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.recreate()
            }
        }
    }

    @Test
    fun oversellIsRejectedWithoutPersistingInvalidEvent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = LedgerDatabase.get(context)
        db.clearAllTables()
        val repository = LedgerRepository(db)
        repository.addItemWithInitialBuy(
            ItemEntity(name = "测试物品", type = ItemType.SKIN),
            EventDraft(
                type = EventType.BUY,
                lines = listOf(DraftLine(0, LineDirection.IN, 1, 100))
            )
        )
        val item = repository.items.first().single()

        try {
            repository.addEvent(
                EventDraft(
                    type = EventType.SELL,
                    lines = listOf(DraftLine(item.id, LineDirection.OUT, 2, 200))
                )
            )
            fail("超卖流水不应保存")
        } catch (_: IllegalArgumentException) {
            // 预期：库存校验与写入处于同一数据库事务。
        }

        val snapshot = repository.snapshot.first()
        assertNull(snapshot.error)
        assertEquals(1, snapshot.events.size)
        assertEquals(1, snapshot.holdings.single().quantity)
    }

    @Test
    fun voidingRequiredBuyIsRejectedWithoutChangingLedger() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = LedgerDatabase.get(context)
        db.clearAllTables()
        val repository = LedgerRepository(db)
        repository.addItemWithInitialBuy(
            ItemEntity(name = "测试物品", type = ItemType.SKIN),
            EventDraft(
                type = EventType.BUY,
                lines = listOf(DraftLine(0, LineDirection.IN, 1, 100))
            )
        )
        val item = repository.items.first().single()
        repository.addEvent(
            EventDraft(
                type = EventType.SELL,
                lines = listOf(DraftLine(item.id, LineDirection.OUT, 1, 200))
            )
        )
        val buyId = repository.snapshot.first().events.single { it.event.type == EventType.BUY }.event.id

        try {
            repository.setEventVoided(buyId, true)
            fail("仍被卖出流水依赖的买入记录不应作废")
        } catch (_: IllegalArgumentException) {
            // 预期：删除校验失败时事务回滚。
        }

        val snapshot = repository.snapshot.first()
        assertNull(snapshot.error)
        assertEquals(2, snapshot.events.size)
        assertEquals(100, snapshot.totalRealizedPnlCents)
    }
}
