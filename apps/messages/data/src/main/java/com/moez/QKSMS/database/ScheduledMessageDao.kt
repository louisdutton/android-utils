package dev.octoshrimpy.quik.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ScheduledMessageDao {
    @Query("SELECT * FROM scheduled_messages ORDER BY date ASC")
    fun scheduledMessages(): List<ScheduledMessageEntity>

    @Query("SELECT * FROM scheduled_messages WHERE conversation_id = :conversationId ORDER BY date ASC")
    fun scheduledMessagesForConversation(conversationId: Long): List<ScheduledMessageEntity>

    @Query("SELECT * FROM scheduled_messages WHERE id = :id LIMIT 1")
    fun scheduledMessage(id: Long): ScheduledMessageEntity?

    @Query("SELECT MAX(id) FROM scheduled_messages")
    fun maxId(): Long?

    @Query("SELECT id FROM scheduled_messages ORDER BY date ASC")
    fun ids(): List<Long>

    @Upsert
    fun upsert(message: ScheduledMessageEntity)

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    fun delete(id: Long)

    @Query("DELETE FROM scheduled_messages WHERE id IN (:ids)")
    fun delete(ids: Collection<Long>)
}

