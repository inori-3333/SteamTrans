package com.steamtrans.ledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun itemType(value: ItemType) = value.name
    @TypeConverter fun toItemType(value: String) = ItemType.valueOf(value)
    @TypeConverter fun eventType(value: EventType) = value.name
    @TypeConverter fun toEventType(value: String) = EventType.valueOf(value)
    @TypeConverter fun direction(value: LineDirection) = value.name
    @TypeConverter fun toDirection(value: String) = LineDirection.valueOf(value)
    @TypeConverter fun accountType(value: AccountType) = value.name
    @TypeConverter fun toAccountType(value: String) = AccountType.valueOf(value)
    @TypeConverter fun trackingMode(value: TrackingMode) = value.name
    @TypeConverter fun toTrackingMode(value: String) = TrackingMode.valueOf(value)
    @TypeConverter fun eventStatus(value: EventStatus) = value.name
    @TypeConverter fun toEventStatus(value: String) = EventStatus.valueOf(value)
    @TypeConverter fun priceSource(value: PriceSource) = value.name
    @TypeConverter fun toPriceSource(value: String) = PriceSource.valueOf(value)
}

@Database(
    entities = [
        ItemEntity::class,
        LedgerEventEntity::class,
        EventLineEntity::class,
        LotAllocationEntity::class,
        PlatformProfileEntity::class,
        MarketQuoteEntity::class,
        PortfolioSnapshotEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        @Volatile private var instance: LedgerDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN trackingMode TEXT NOT NULL DEFAULT 'STACKABLE'")
                db.execSQL("ALTER TABLE items ADD COLUMN tradeable INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN marketAppId INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN marketHashName TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN imageUrl TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN trackingReviewed INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE items ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE items SET trackingMode = 'INDIVIDUAL', trackingReviewed = 0 WHERE type = 'SKIN'")

                db.execSQL("ALTER TABLE ledger_events ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE'")
                db.execSQL("ALTER TABLE ledger_events ADD COLUMN accountType TEXT NOT NULL DEFAULT 'STEAM_WALLET_CNY'")
                db.execSQL("ALTER TABLE ledger_events ADD COLUMN accountDeltaCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ledger_events ADD COLUMN feeRateBps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ledger_events ADD COLUMN fixedFeeCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ledger_events ADD COLUMN legacy INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ledger_events ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE ledger_events SET accountType = CASE WHEN lower(platform) = 'steam' THEN 'STEAM_WALLET_CNY' ELSE 'FIAT_CNY' END")
                db.execSQL("UPDATE ledger_events SET legacy = 1")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS lot_allocations (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "eventLineId INTEGER NOT NULL, sourceLineId INTEGER NOT NULL, quantity INTEGER NOT NULL, " +
                        "unitOrdinal INTEGER, " +
                        "FOREIGN KEY(eventLineId) REFERENCES event_lines(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(sourceLineId) REFERENCES event_lines(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lot_allocations_eventLineId ON lot_allocations(eventLineId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lot_allocations_sourceLineId ON lot_allocations(sourceLineId)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS platform_profiles (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, defaultAccountType TEXT NOT NULL, " +
                        "buyFeeRateBps INTEGER NOT NULL, buyFixedFeeCents INTEGER NOT NULL, " +
                        "sellFeeRateBps INTEGER NOT NULL, sellFixedFeeCents INTEGER NOT NULL, " +
                        "builtIn INTEGER NOT NULL, enabled INTEGER NOT NULL, sortOrder INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_platform_profiles_name ON platform_profiles(name)")
                seedProfiles(db)

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS market_quotes (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, itemId INTEGER NOT NULL, " +
                        "grossPriceCents INTEGER NOT NULL, estimatedNetPriceCents INTEGER NOT NULL, " +
                        "source TEXT NOT NULL, timestamp INTEGER NOT NULL, volume TEXT NOT NULL, " +
                        "FOREIGN KEY(itemId) REFERENCES items(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_market_quotes_itemId ON market_quotes(itemId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_market_quotes_itemId_timestamp ON market_quotes(itemId, timestamp)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS portfolio_snapshots (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, trigger TEXT NOT NULL, " +
                        "fiatBalanceCents INTEGER NOT NULL, walletBalanceCents INTEGER NOT NULL, " +
                        "fiatHoldingCostCents INTEGER NOT NULL, walletHoldingCostCents INTEGER NOT NULL, " +
                        "marketGrossCents INTEGER NOT NULL, marketNetCents INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_portfolio_snapshots_timestamp ON portfolio_snapshots(timestamp)")
            }
        }

        fun get(context: Context): LedgerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LedgerDatabase::class.java,
                "steam-ledger.db"
            )
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        seedProfiles(db)
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        seedProfiles(db)
                    }
                })
                .build()
                .also { instance = it }
        }

        private fun seedProfiles(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT OR IGNORE INTO platform_profiles " +
                    "(id,name,defaultAccountType,buyFeeRateBps,buyFixedFeeCents,sellFeeRateBps,sellFixedFeeCents,builtIn,enabled,sortOrder) VALUES " +
                    "(1,'Steam','STEAM_WALLET_CNY',0,0,1500,0,1,1,0)," +
                    "(2,'BUFF','FIAT_CNY',0,0,0,0,1,1,10)," +
                    "(3,'UUYP','FIAT_CNY',0,0,0,0,1,1,20)," +
                    "(4,'淘宝','FIAT_CNY',0,0,0,0,1,1,30)"
            )
        }
    }
}
