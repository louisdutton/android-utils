package dev.octoshrimpy.quik.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recipients",
    indices = [
        Index(value = ["address"]),
        Index(value = ["contact_lookup_key"])
    ]
)
data class RecipientEntity(
    @PrimaryKey val id: Long,
    val address: String,
    @ColumnInfo(name = "contact_lookup_key") val contactLookupKey: String?,
    @ColumnInfo(name = "last_update") val lastUpdate: Long
)

