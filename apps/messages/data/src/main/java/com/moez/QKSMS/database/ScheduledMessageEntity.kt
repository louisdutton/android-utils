package dev.octoshrimpy.quik.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scheduled_messages",
    indices = [
        Index(value = ["date"]),
        Index(value = ["conversation_id", "date"])
    ]
)
data class ScheduledMessageEntity(
    @PrimaryKey val id: Long,
    val date: Long,
    @ColumnInfo(name = "sub_id") val subId: Int,
    val recipients: String,
    @ColumnInfo(name = "send_as_group") val sendAsGroup: Boolean,
    val body: String,
    val attachments: String,
    @ColumnInfo(name = "conversation_id") val conversationId: Long
)

