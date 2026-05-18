package dev.octoshrimpy.quik.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["archived", "blocked", "pinned", "last_message_date"]),
        Index(value = ["last_message_id"])
    ]
)
data class ConversationEntity(
    @PrimaryKey val id: Long,
    val archived: Boolean,
    val blocked: Boolean,
    val pinned: Boolean,
    @ColumnInfo(name = "last_message_id") val lastMessageId: Long?,
    @ColumnInfo(name = "last_message_date") val lastMessageDate: Long,
    val draft: String,
    @ColumnInfo(name = "draft_date") val draftDate: Long,
    @ColumnInfo(name = "blocking_client") val blockingClient: Int?,
    @ColumnInfo(name = "block_reason") val blockReason: String?,
    val name: String,
    @ColumnInfo(name = "send_as_group") val sendAsGroup: Boolean
)

@Entity(
    tableName = "conversation_recipients",
    primaryKeys = ["conversation_id", "recipient_id"],
    indices = [Index(value = ["recipient_id"])]
)
data class ConversationRecipientEntity(
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "recipient_id") val recipientId: Long
)

