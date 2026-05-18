package dev.octoshrimpy.quik.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "message_content_filters")
data class MessageContentFilterEntity(
    @PrimaryKey val id: Long,
    val value: String,
    @ColumnInfo(name = "case_sensitive") val caseSensitive: Boolean,
    @ColumnInfo(name = "is_regex") val isRegex: Boolean,
    @ColumnInfo(name = "include_contacts") val includeContacts: Boolean
)

