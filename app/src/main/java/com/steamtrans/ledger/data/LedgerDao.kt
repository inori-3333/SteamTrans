package com.steamtrans.ledger.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query("SELECT * FROM items ORDER BY useCount DESC, name") fun observeItems(): Flow<List<ItemEntity>>
    @Query("SELECT (SELECT COUNT(*) FROM items) + (SELECT COUNT(*) FROM ledger_events) + (SELECT COUNT(*) FROM event_lines)")
    fun observeLedgerChanges(): Flow<Long>
    @Query("SELECT * FROM items") suspend fun getItems(): List<ItemEntity>
    @Transaction
    @Query("SELECT * FROM ledger_events ORDER BY timestamp DESC, id DESC")
    suspend fun getEventRecords(): List<LedgerEventRecord>
    @Query("SELECT * FROM items WHERE name = :name AND game = :game AND type = :type LIMIT 1") suspend fun findItem(name: String, game: String, type: ItemType): ItemEntity?
    @Insert suspend fun insertItem(item: ItemEntity): Long
    @Update suspend fun updateItem(item: ItemEntity)
    @Insert suspend fun insertEvent(event: LedgerEventEntity): Long
    @Insert suspend fun insertLines(lines: List<EventLineEntity>)
    @Query("DELETE FROM ledger_events WHERE id = :id") suspend fun deleteEvent(id: Long)
    @Query("DELETE FROM ledger_events") suspend fun deleteEvents()
    @Query("DELETE FROM items") suspend fun deleteItems()
    @Query("UPDATE items SET useCount = useCount + 1 WHERE id IN (:ids)") suspend fun touchItems(ids: List<Long>)
}
