package dev.octoshrimpy.quik.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface MmsPartDao {
    @Query("SELECT * FROM mms_parts WHERE message_id = :messageId ORDER BY seq ASC")
    fun partsForMessage(messageId: Long): List<MmsPartEntity>

    @Query("SELECT * FROM mms_parts WHERE message_id IN (:messageIds) ORDER BY message_id ASC, seq ASC")
    fun partsForMessages(messageIds: Collection<Long>): List<MmsPartEntity>

    @Query("SELECT * FROM mms_parts WHERE id = :id LIMIT 1")
    fun part(id: Long): MmsPartEntity?

    @Query(
        """
        SELECT m.* FROM messages m
        INNER JOIN mms_parts p ON p.message_id = m.content_id
        WHERE p.id = :partId
        LIMIT 1
        """
    )
    fun messageForPart(partId: Long): MessageEntity?

    @Query(
        """
        SELECT DISTINCT p.* FROM mms_parts p
        INNER JOIN messages m ON m.content_id = p.message_id
        WHERE m.thread_id = :threadId
          AND (p.type LIKE 'image/%' OR p.type LIKE 'video/%')
        ORDER BY p.id DESC
        """
    )
    fun mediaPartsForConversation(threadId: Long): List<MmsPartEntity>

    @Upsert
    fun upsert(part: MmsPartEntity)

    @Upsert
    fun upsert(parts: Collection<MmsPartEntity>)

    @Query("DELETE FROM mms_parts")
    fun deleteAll()

    @Query("DELETE FROM mms_parts WHERE message_id IN (:messageIds)")
    fun deleteForMessageContentIds(messageIds: Collection<Long>)
}
