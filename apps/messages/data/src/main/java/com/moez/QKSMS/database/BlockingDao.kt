package dev.octoshrimpy.quik.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface BlockingDao {
    @Query("SELECT * FROM blocked_numbers ORDER BY address ASC")
    fun blockedNumbers(): List<BlockedNumberEntity>

    @Query("SELECT * FROM blocked_numbers WHERE id = :id LIMIT 1")
    fun blockedNumber(id: Long): BlockedNumberEntity?

    @Query("SELECT MAX(id) FROM blocked_numbers")
    fun maxId(): Long?

    @Upsert
    fun upsert(numbers: Collection<BlockedNumberEntity>)

    @Query("DELETE FROM blocked_numbers WHERE id = :id")
    fun delete(id: Long)

    @Query("DELETE FROM blocked_numbers WHERE id IN (:ids)")
    fun delete(ids: Collection<Long>)
}

