package com.steamtrans.ledger

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.steamtrans.ledger.data.AccountType
import com.steamtrans.ledger.data.DraftLine
import com.steamtrans.ledger.data.EventDraft
import com.steamtrans.ledger.data.EventType
import com.steamtrans.ledger.data.ItemEntity
import com.steamtrans.ledger.data.ItemType
import com.steamtrans.ledger.data.LedgerDatabase
import com.steamtrans.ledger.data.LedgerRepository
import com.steamtrans.ledger.data.LineDirection
import com.steamtrans.ledger.domain.backup.BackupService
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupServiceTest {
    @Test
    fun jsonRoundTripAndExcelCompatibleCsvZip() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = LedgerRepository(LedgerDatabase.get(context))
        repository.clearAll()
        repository.addItemWithInitialBuy(
            ItemEntity(name = "中文测试武器箱", type = ItemType.CASE_CONTAINER),
            EventDraft(
                type = EventType.BUY,
                platform = "BUFF",
                accountType = AccountType.FIAT_CNY,
                lines = listOf(DraftLine(0, LineDirection.IN, 3, 1234))
            )
        )
        val service = BackupService(context, repository)

        val json = service.exportJson()
        val preview = service.preview(json)
        assertEquals(1, preview.itemCount)
        assertEquals(1, preview.eventCount)

        val zipBytes = service.exportCsvZip()
        val csvFiles = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                csvFiles[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        assertEquals(setOf("流水.csv", "当前持仓.csv", "物品.csv", "账户.csv", "行情历史.csv"), csvFiles.keys)
        csvFiles.values.forEach { bytes ->
            assertTrue(bytes.size >= 3)
            assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.copyOfRange(0, 3))
        }
        assertTrue(csvFiles.getValue("物品.csv").toString(Charsets.UTF_8).contains("中文测试武器箱"))

        repository.clearAll()
        val emergency = service.restoreJson(json)
        assertTrue(emergency.exists())
        val restored = repository.currentSnapshot()
        assertEquals(1, restored.holdings.size)
        assertEquals(3, restored.holdings.single().quantity)
        assertEquals(3702, restored.holdings.single().cost.fiatCents)
    }

    @Test
    fun damagedBackupIsRejectedBeforeReplacement() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = LedgerRepository(LedgerDatabase.get(context))
        val service = BackupService(context, repository)
        try {
            service.preview("{ this is not a backup }")
            fail("损坏备份必须被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertNotNull(expected.message)
        }
    }
}
