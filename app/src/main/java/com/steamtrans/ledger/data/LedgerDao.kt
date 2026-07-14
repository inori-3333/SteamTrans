package com.steamtrans.ledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query("SELECT * FROM items ORDER BY useCount DESC, name")
    fun observeItems(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM platform_profiles WHERE enabled = 1 ORDER BY sortOrder, name")
    fun observePlatformProfiles(): Flow<List<PlatformProfileEntity>>

    @Query("SELECT * FROM portfolio_snapshots ORDER BY timestamp")
    fun observePortfolioSnapshots(): Flow<List<PortfolioSnapshotEntity>>

    @Query("SELECT * FROM market_quotes ORDER BY timestamp DESC")
    fun observeMarketQuotes(): Flow<List<MarketQuoteEntity>>

    @Query(
        "SELECT " +
            "(SELECT COUNT(*) FROM items) + " +
            "(SELECT COUNT(*) FROM ledger_events) + " +
            "(SELECT COUNT(*) FROM event_lines) + " +
            "(SELECT COUNT(*) FROM lot_allocations) + " +
            "(SELECT COUNT(*) FROM market_quotes) + " +
            "(SELECT COUNT(*) FROM portfolio_snapshots) + " +
            "(SELECT COUNT(*) FROM platform_profiles)"
    )
    fun observeLedgerChanges(): Flow<Long>

    @Query("SELECT * FROM items")
    suspend fun getItems(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE trackingReviewed = 0 ORDER BY name")
    suspend fun getItemsNeedingReview(): List<ItemEntity>

    @Transaction
    @Query("SELECT * FROM ledger_events ORDER BY timestamp DESC, id DESC")
    suspend fun getEventRecords(): List<LedgerEventRecord>

    @Query("SELECT * FROM lot_allocations")
    suspend fun getAllocations(): List<LotAllocationEntity>

    @Query("SELECT * FROM platform_profiles ORDER BY sortOrder, name")
    suspend fun getPlatformProfiles(): List<PlatformProfileEntity>

    @Query(
        "SELECT q.* FROM market_quotes q " +
            "INNER JOIN (SELECT itemId, MAX(timestamp) AS maxTimestamp FROM market_quotes GROUP BY itemId) latest " +
            "ON latest.itemId = q.itemId AND latest.maxTimestamp = q.timestamp"
    )
    suspend fun getLatestQuotes(): List<MarketQuoteEntity>

    @Query("SELECT * FROM market_quotes ORDER BY timestamp")
    suspend fun getAllQuotes(): List<MarketQuoteEntity>

    @Query("SELECT * FROM portfolio_snapshots ORDER BY timestamp")
    suspend fun getAllPortfolioSnapshots(): List<PortfolioSnapshotEntity>

    @Query("SELECT * FROM items WHERE name = :name AND game = :game AND type = :type LIMIT 1")
    suspend fun findItem(name: String, game: String, type: ItemType): ItemEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItem(item: ItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<ItemEntity>)

    @Update
    suspend fun updateItem(item: ItemEntity)

    @Insert
    suspend fun insertEvent(event: LedgerEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<LedgerEventEntity>)

    @Update
    suspend fun updateEvent(event: LedgerEventEntity)

    @Query("UPDATE ledger_events SET accountType = :account, updatedAt = :updatedAt WHERE legacy = 1 AND lower(platform) = lower(:platform)")
    suspend fun updateLegacyPlatformAccount(platform: String, account: AccountType, updatedAt: Long)

    @Insert
    suspend fun insertLines(lines: List<EventLineEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLinesForRestore(lines: List<EventLineEntity>)

    @Insert
    suspend fun insertAllocations(allocations: List<LotAllocationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllocationsForRestore(allocations: List<LotAllocationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlatformProfiles(profiles: List<PlatformProfileEntity>)

    @Update
    suspend fun updatePlatformProfile(profile: PlatformProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuote(quote: MarketQuoteEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuotes(quotes: List<MarketQuoteEntity>)

    @Insert
    suspend fun insertPortfolioSnapshot(snapshot: PortfolioSnapshotEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPortfolioSnapshots(snapshots: List<PortfolioSnapshotEntity>)

    @Query("DELETE FROM event_lines WHERE eventId = :eventId")
    suspend fun deleteLinesForEvent(eventId: Long)

    @Query("DELETE FROM ledger_events WHERE id = :id")
    suspend fun deleteEventPermanently(id: Long)

    @Query("DELETE FROM ledger_events")
    suspend fun deleteEvents()

    @Query("DELETE FROM items")
    suspend fun deleteItems()

    @Query("DELETE FROM market_quotes")
    suspend fun deleteQuotes()

    @Query("DELETE FROM portfolio_snapshots")
    suspend fun deletePortfolioSnapshots()

    @Query("DELETE FROM portfolio_snapshots WHERE id = :id")
    suspend fun deletePortfolioSnapshot(id: Long): Int

    @Query("DELETE FROM platform_profiles")
    suspend fun deletePlatformProfiles()

    @Query("UPDATE items SET useCount = useCount + 1, updatedAt = :updatedAt WHERE id IN (:ids)")
    suspend fun touchItems(ids: List<Long>, updatedAt: Long)
}
