package dev.octoshrimpy.quik.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["thread_id", "date"]),
        Index(value = ["thread_id", "is_emoji_reaction", "date"]),
        Index(value = ["content_id", "type"])
    ]
)
data class MessageEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "content_id") val contentId: Long,
    val address: String,
    @ColumnInfo(name = "box_id") val boxId: Int,
    val type: String,
    val date: Long,
    @ColumnInfo(name = "date_sent") val dateSent: Long,
    val seen: Boolean,
    val read: Boolean,
    val locked: Boolean,
    @ColumnInfo(name = "sub_id") val subId: Int,
    val body: String,
    @ColumnInfo(name = "error_code") val errorCode: Int,
    @ColumnInfo(name = "delivery_status") val deliveryStatus: Int,
    @ColumnInfo(name = "attachment_type") val attachmentTypeString: String,
    @ColumnInfo(name = "mms_delivery_status") val mmsDeliveryStatusString: String,
    @ColumnInfo(name = "read_report") val readReportString: String,
    @ColumnInfo(name = "error_type") val errorType: Int,
    @ColumnInfo(name = "message_size") val messageSize: Int,
    @ColumnInfo(name = "message_type") val messageType: Int,
    @ColumnInfo(name = "mms_status") val mmsStatus: Int,
    val subject: String,
    @ColumnInfo(name = "text_content_type") val textContentType: String,
    @ColumnInfo(name = "is_emoji_reaction") val isEmojiReaction: Boolean,
    @ColumnInfo(name = "send_as_group") val sendAsGroup: Boolean
)

