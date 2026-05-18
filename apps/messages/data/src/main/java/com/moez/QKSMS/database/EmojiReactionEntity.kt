package dev.octoshrimpy.quik.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "emoji_reactions",
    indices = [
        Index(value = ["target_message_id"]),
        Index(value = ["reaction_message_id"]),
        Index(value = ["thread_id"])
    ]
)
data class EmojiReactionEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "reaction_message_id") val reactionMessageId: Long,
    @ColumnInfo(name = "target_message_id") val targetMessageId: Long,
    @ColumnInfo(name = "sender_address") val senderAddress: String,
    val emoji: String,
    @ColumnInfo(name = "original_message_text") val originalMessageText: String,
    @ColumnInfo(name = "thread_id") val threadId: Long
)
