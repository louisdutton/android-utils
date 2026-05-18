package dev.octoshrimpy.quik.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface RecipientDao {
    @Query("SELECT * FROM recipients")
    fun recipients(): List<RecipientEntity>

    @Query("SELECT * FROM recipients WHERE id = :id LIMIT 1")
    fun recipient(id: Long): RecipientEntity?

    @Query("SELECT * FROM recipients WHERE id IN (:ids)")
    fun recipients(ids: Collection<Long>): List<RecipientEntity>

    @Query(
        """
        SELECT r.* FROM recipients r
        INNER JOIN conversation_recipients cr ON cr.recipient_id = r.id
        WHERE cr.conversation_id = :conversationId
        ORDER BY r.id ASC
        """
    )
    fun recipientsForConversation(conversationId: Long): List<RecipientEntity>

    @Query("SELECT recipient_id FROM conversation_recipients WHERE conversation_id = :conversationId")
    fun recipientIdsForConversation(conversationId: Long): List<Long>

    @Query("DELETE FROM conversation_recipients WHERE conversation_id = :conversationId")
    fun deleteConversationRecipients(conversationId: Long)

    @Upsert
    fun upsert(recipients: Collection<RecipientEntity>)

    @Upsert
    fun upsert(recipients: RecipientEntity)

    @Upsert
    fun upsertConversationRecipients(recipients: Collection<ConversationRecipientEntity>)

    @Query("DELETE FROM recipients")
    fun deleteAll()

    @Query("DELETE FROM conversation_recipients")
    fun deleteAllConversationRecipients()
}

