package com.steamtrans.ledger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.steamtrans.ledger.data.AccountType
import com.steamtrans.ledger.data.LedgerDatabase
import com.steamtrans.ledger.data.LedgerRepository
import com.steamtrans.ledger.data.TrackingMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerMigrationTest {
    @Test
    fun v1DatabaseMigratesWithoutChangingFinancialBaseline() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v1-v2.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("PRAGMA foreign_keys=ON")
            old.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, game TEXT NOT NULL, type TEXT NOT NULL, marketPriceCents INTEGER NOT NULL, note TEXT NOT NULL, useCount INTEGER NOT NULL)")
            old.execSQL("CREATE UNIQUE INDEX index_items_name_game_type ON items(name, game, type)")
            old.execSQL("CREATE TABLE ledger_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL, timestamp INTEGER NOT NULL, platform TEXT NOT NULL, feeCents INTEGER NOT NULL, note TEXT NOT NULL)")
            old.execSQL("CREATE TABLE event_lines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, eventId INTEGER NOT NULL, itemId INTEGER NOT NULL, direction TEXT NOT NULL, quantity INTEGER NOT NULL, unitPriceCents INTEGER NOT NULL, allocatedCostCents INTEGER NOT NULL, FOREIGN KEY(eventId) REFERENCES ledger_events(id) ON DELETE CASCADE, FOREIGN KEY(itemId) REFERENCES items(id))")
            old.execSQL("CREATE INDEX index_event_lines_eventId ON event_lines(eventId)")
            old.execSQL("CREATE INDEX index_event_lines_itemId ON event_lines(itemId)")
            old.execSQL("INSERT INTO items VALUES(1,'旧版皮肤','CS2','SKIN',0,'旧备注',2)")
            old.execSQL("INSERT INTO ledger_events VALUES(1,'BUY',1000,'Steam',0,'旧买入')")
            old.execSQL("INSERT INTO ledger_events VALUES(2,'SELL',2000,'Steam',15,'旧卖出')")
            old.execSQL("INSERT INTO event_lines VALUES(1,1,1,'IN',2,100,0)")
            old.execSQL("INSERT INTO event_lines VALUES(2,2,1,'OUT',1,150,0)")
            old.version = 1
        }

        val db = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
            .addMigrations(LedgerDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val item = db.dao().getItems().single()
            val events = db.dao().getEventRecords()
            val profiles = db.dao().getPlatformProfiles()
            val snapshot = LedgerRepository(db).currentSnapshot()

            assertEquals("旧版皮肤", item.name)
            assertEquals("旧备注", item.note)
            assertEquals(TrackingMode.INDIVIDUAL, item.trackingMode)
            assertFalse(item.trackingReviewed)
            assertEquals(2, events.size)
            assertEquals(AccountType.STEAM_WALLET_CNY, events.first { it.event.id == 1L }.event.accountType)
            assertEquals(true, events.all { it.event.legacy })
            assertEquals(4, profiles.size)
            assertNull(snapshot.error)
            assertEquals(1, snapshot.holdings.single().quantity)
            assertEquals(100, snapshot.totalCostCents)
            assertEquals(35, snapshot.walletRealizedPnlCents)
            assertEquals(-65, snapshot.walletBalanceCents)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
