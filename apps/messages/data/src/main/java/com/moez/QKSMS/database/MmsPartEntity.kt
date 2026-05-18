package dev.octoshrimpy.quik.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "mms_parts",
    indices = [Index(value = ["message_id"])]
)
data class MmsPartEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "message_id") val messageId: Long,
    val type: String,
    val seq: Int,
    val name: String?,
    val text: String?
)

