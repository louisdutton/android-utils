package dev.octoshrimpy.quik.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface EmojiReactionDao {
    @Query("SELECT * FROM emoji_reactions WHERE target_message_id IN (:messageIds)")
    fun reactionsForTargets(messageIds: Collection<Long>): List<EmojiReactionEntity>

    @Query("SELECT * FROM emoji_reactions WHERE target_message_id = :messageId")
    fun reactionsForMessage(messageId: Long): List<EmojiReactionEntity>

    @Query(
        """
        SELECT m.* FROM messages m
        WHERE m.thread_id = :threadId
          AND m.is_emoji_reaction = 0
        ORDER BY m.date DESC
        """
    )
    fun reactionTargetCandidates(threadId: Long): List<MessageEntity>

    @Query("UPDATE messages SET is_emoji_reaction = 0")
    fun clearEmojiReactionFlags()

    @Query("UPDATE messages SET is_emoji_reaction = :isEmojiReaction WHERE id = :messageId")
    fun updateEmojiReactionFlag(messageId: Long, isEmojiReaction: Boolean)

    @Upsert
    fun upsert(reaction: EmojiReactionEntity)

    @Query(
        """
        DELETE FROM emoji_reactions
        WHERE target_message_id = :targetMessageId
          AND sender_address = :senderAddress
          AND emoji = :emoji
        """
    )
    fun deleteReaction(targetMessageId: Long, senderAddress: String, emoji: String)

    @Query("DELETE FROM emoji_reactions WHERE target_message_id = :targetMessageId AND sender_address = :senderAddress")
    fun deletePriorFromSender(targetMessageId: Long, senderAddress: String)

    @Query("DELETE FROM emoji_reactions")
    fun deleteAll()
}
