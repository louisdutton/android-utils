package dev.octoshrimpy.quik.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface MessageContentFilterDao {
    @Query("SELECT * FROM message_content_filters ORDER BY id ASC")
    fun filters(): List<MessageContentFilterEntity>

    @Query("SELECT * FROM message_content_filters WHERE id = :id LIMIT 1")
    fun filter(id: Long): MessageContentFilterEntity?

    @Query("SELECT MAX(id) FROM message_content_filters")
    fun maxId(): Long?

    @Upsert
    fun upsert(filter: MessageContentFilterEntity)

    @Query("DELETE FROM message_content_filters WHERE id = :id")
    fun delete(id: Long)
}

