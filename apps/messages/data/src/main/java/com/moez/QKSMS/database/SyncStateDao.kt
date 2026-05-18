package dev.octoshrimpy.quik.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SyncStateDao {
    @Query("SELECT value FROM sync_state WHERE key = :key LIMIT 1")
    fun value(key: String): Long?

    @Upsert
    fun upsert(state: SyncStateEntity)
}

