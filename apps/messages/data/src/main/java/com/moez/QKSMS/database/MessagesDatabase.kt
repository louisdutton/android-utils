package dev.octoshrimpy.quik.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [RealmObjectRecord::class],
    version = 1,
    exportSchema = false
)
abstract class MessagesDatabase : RoomDatabase() {
    abstract fun realmObjects(): RealmObjectDao
}
