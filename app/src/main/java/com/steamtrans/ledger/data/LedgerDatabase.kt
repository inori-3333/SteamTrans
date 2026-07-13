package com.steamtrans.ledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun itemType(value: ItemType) = value.name
    @TypeConverter fun toItemType(value: String) = ItemType.valueOf(value)
    @TypeConverter fun eventType(value: EventType) = value.name
    @TypeConverter fun toEventType(value: String) = EventType.valueOf(value)
    @TypeConverter fun direction(value: LineDirection) = value.name
    @TypeConverter fun toDirection(value: String) = LineDirection.valueOf(value)
}

@Database(entities = [ItemEntity::class, LedgerEventEntity::class, EventLineEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao
    companion object {
        @Volatile private var instance: LedgerDatabase? = null
        fun get(context: Context): LedgerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, "steam-ledger.db")
                .build().also { instance = it }
        }
    }
}
