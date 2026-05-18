package dev.octoshrimpy.quik.database

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "realm_objects",
    primaryKeys = ["type", "object_key"]
)
data class RealmObjectRecord(
    val type: String,
    @ColumnInfo(name = "object_key") val objectKey: String,
    val payload: ByteArray
)
