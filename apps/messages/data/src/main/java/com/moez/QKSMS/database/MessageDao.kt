package dev.octoshrimpy.quik.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.reactivex.Flowable

@Dao
interface MessageDao {
    @Query(
        """
        SELECT * FROM messages
        WHERE thread_id = :threadId
          AND is_emoji_reaction = 0
          AND (:query = '' OR body LIKE '%' || :query || '%')
        ORDER BY date ASC
        """
    )
    fun observeMessages(threadId: Long, query: String = ""): Flowable<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE thread_id = :threadId
          AND is_emoji_reaction = 0
          AND (:query = '' OR body LIKE '%' || :query || '%')
        ORDER BY date ASC
        """
    )
    fun messages(threadId: Long, query: String = ""): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    fun message(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE id IN (:ids) ORDER BY date ASC")
    fun messages(ids: Collection<Long>): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE body LIKE '%' || :query || '%' ORDER BY date DESC")
    fun searchMessages(query: String): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE thread_id = :threadId
          AND seen = 0
        ORDER BY date ASC
        """
    )
    fun unreadUnseenMessages(threadId: Long): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE thread_id = :threadId
          AND read = 0
        ORDER BY date ASC
        """
    )
    fun unreadMessages(threadId: Long): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE thread_id = :threadId
          AND ((type = 'sms' AND box_id IN (:smsBoxes))
            OR (type = 'mms' AND box_id IN (:mmsBoxes)))
        ORDER BY date DESC
        """
    )
    fun lastIncomingMessages(threadId: Long, smsBoxes: IntArray, mmsBoxes: IntArray): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE content_id = :contentId AND type = :type LIMIT 1")
    fun messageByContentId(type: String, contentId: Long): MessageEntity?

    @Query("SELECT MAX(id) FROM messages")
    fun maxMessageId(): Long?

    @Query("SELECT COUNT(*) FROM messages WHERE seen = 0")
    fun unseenCount(): Long

    @Query("SELECT * FROM messages WHERE date < :cutoff ORDER BY date ASC")
    fun messagesOlderThan(cutoff: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE thread_id = :threadId AND is_emoji_reaction = 0 ORDER BY date DESC")
    fun messagesForThreadNewest(threadId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY date ASC")
    fun messagesOldestFirst(): List<MessageEntity>

    @Query("UPDATE messages SET seen = :seen WHERE thread_id IN (:threadIds)")
    fun updateSeen(threadIds: Collection<Long>, seen: Boolean)

    @Query("UPDATE messages SET seen = :seen, read = :read WHERE thread_id IN (:threadIds)")
    fun updateSeenRead(threadIds: Collection<Long>, seen: Boolean, read: Boolean)

    @Query("UPDATE messages SET read = :read WHERE thread_id IN (:threadIds)")
    fun updateRead(threadIds: Collection<Long>, read: Boolean)

    @Query("UPDATE messages SET box_id = :boxId WHERE id = :messageId")
    fun updateBoxId(messageId: Long, boxId: Int)

    @Query("UPDATE messages SET box_id = :boxId, error_code = :errorCode WHERE id = :messageId")
    fun updateBoxIdError(messageId: Long, boxId: Int, errorCode: Int)

    @Query("UPDATE messages SET delivery_status = :deliveryStatus, date_sent = :dateSent, read = :read WHERE id = :messageId")
    fun updateDelivery(messageId: Long, deliveryStatus: Int, dateSent: Long, read: Boolean)

    @Query("UPDATE messages SET delivery_status = :deliveryStatus, date_sent = :dateSent, read = :read, error_code = :errorCode WHERE id = :messageId")
    fun updateDeliveryError(messageId: Long, deliveryStatus: Int, dateSent: Long, read: Boolean, errorCode: Int)

    @Query("UPDATE messages SET date = :date WHERE id = :messageId")
    fun updateDate(messageId: Long, date: Long)

    @Query("UPDATE messages SET is_emoji_reaction = :isEmojiReaction WHERE id = :messageId")
    fun updateEmojiReaction(messageId: Long, isEmojiReaction: Boolean)

    @Query("UPDATE messages SET is_emoji_reaction = 0")
    fun clearEmojiReactionFlags()

    @Upsert
    fun upsert(message: MessageEntity)

    @Upsert
    fun upsert(messages: Collection<MessageEntity>)

    @Query("DELETE FROM messages")
    fun deleteAll()

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    fun delete(ids: Collection<Long>)

    @Query("DELETE FROM messages WHERE thread_id IN (:threadIds)")
    fun deleteByThreadIds(threadIds: Collection<Long>)
}
