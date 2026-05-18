package dev.octoshrimpy.quik.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "blocked_numbers",
    indices = [Index(value = ["address"])]
)
data class BlockedNumberEntity(
    @PrimaryKey val id: Long,
    val address: String
)

