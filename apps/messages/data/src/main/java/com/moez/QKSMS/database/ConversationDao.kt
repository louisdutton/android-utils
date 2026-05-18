package dev.octoshrimpy.quik.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.reactivex.Flowable

@Dao
interface ConversationDao {
    @Query(
        """
        SELECT * FROM conversations
        WHERE id != 0
          AND archived = :archived
          AND blocked = 0
          AND (last_message_id IS NOT NULL OR draft != '')
        ORDER BY pinned DESC, draft DESC, last_message_date DESC
        """
    )
    fun observeConversations(archived: Boolean): Flowable<List<ConversationEntity>>

    @Query(
        """
        SELECT * FROM conversations
        WHERE id != 0
          AND archived = :archived
          AND blocked = 0
          AND (last_message_id IS NOT NULL OR draft != '')
        ORDER BY pinned DESC, last_message_date DESC
        """
    )
    fun conversations(archived: Boolean): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    fun conversation(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    fun observeConversation(id: Long): Flowable<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id IN (:ids)")
    fun conversations(ids: Collection<Long>): List<ConversationEntity>

    @Query("SELECT * FROM conversations")
    fun allConversations(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE blocked = 1 ORDER BY last_message_date DESC")
    fun blockedConversations(): List<ConversationEntity>

    @Query(
        """
        SELECT * FROM conversations
        WHERE archived = 1
           OR blocked = 1
           OR pinned = 1
           OR name != ''
           OR blocking_client IS NOT NULL
           OR (block_reason IS NOT NULL AND block_reason != '')
           OR send_as_group = 0
        """
    )
    fun conversationsWithLocalState(): List<ConversationEntity>

    @Query(
        """
        SELECT * FROM conversations
        WHERE id != 0
          AND archived = :archived
          AND blocked = 0
          AND last_message_id IN (
              SELECT id FROM messages WHERE read = 0
          )
        ORDER BY last_message_date DESC
        """
    )
    fun unreadConversations(archived: Boolean): List<ConversationEntity>

    @Query(
        """
        SELECT * FROM conversations
        WHERE id != 0
          AND archived = :archived
          AND blocked = 0
          AND last_message_id IN (
              SELECT id FROM messages WHERE seen = 0
          )
        ORDER BY last_message_date DESC
        """
    )
    fun unseenConversations(archived: Boolean): List<ConversationEntity>

    @Query("UPDATE conversations SET name = :name WHERE id = :id")
    fun updateName(id: Long, name: String)

    @Query("UPDATE conversations SET draft = :draft, draft_date = :draftDate WHERE id = :id")
    fun updateDraft(id: Long, draft: String, draftDate: Long)

    @Query("UPDATE conversations SET archived = :archived WHERE id IN (:ids)")
    fun updateArchived(ids: Collection<Long>, archived: Boolean)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id IN (:ids)")
    fun updatePinned(ids: Collection<Long>, pinned: Boolean)

    @Query("UPDATE conversations SET blocked = :blocked, blocking_client = :client, block_reason = :reason WHERE id IN (:ids)")
    fun updateBlocked(ids: Collection<Long>, blocked: Boolean, client: Int?, reason: String?)

    @Query("UPDATE conversations SET send_as_group = :sendAsGroup WHERE id = :id")
    fun updateSendAsGroup(id: Long, sendAsGroup: Boolean)

    @Query(
        """
        UPDATE conversations
        SET last_message_id = (
            SELECT id FROM messages
            WHERE thread_id = :id
            ORDER BY date DESC
            LIMIT 1
        ),
        last_message_date = COALESCE((
            SELECT date FROM messages
            WHERE thread_id = :id
            ORDER BY date DESC
            LIMIT 1
        ), 0)
        WHERE id = :id
        """
    )
    fun refreshLastMessage(id: Long)

    @Upsert
    fun upsert(conversation: ConversationEntity)

    @Upsert
    fun upsert(conversations: Collection<ConversationEntity>)

    @Query("DELETE FROM conversations")
    fun deleteAll()

    @Query("DELETE FROM conversations WHERE id IN (:ids)")
    fun delete(ids: Collection<Long>)
}
