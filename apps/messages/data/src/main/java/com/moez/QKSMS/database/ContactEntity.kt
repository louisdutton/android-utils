package dev.octoshrimpy.quik.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey @ColumnInfo(name = "lookup_key") val lookupKey: String,
    val name: String,
    @ColumnInfo(name = "photo_uri") val photoUri: String?,
    val starred: Boolean,
    @ColumnInfo(name = "last_update") val lastUpdate: Long
)

@Entity(
    tableName = "phone_numbers",
    indices = [
        Index(value = ["contact_lookup_key"]),
        Index(value = ["address"])
    ]
)
data class PhoneNumberEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "contact_lookup_key") val contactLookupKey: String,
    @ColumnInfo(name = "account_type") val accountType: String?,
    val address: String,
    val type: String,
    @ColumnInfo(name = "is_default") val isDefault: Boolean
)

@Entity(tableName = "contact_groups")
data class ContactGroupEntity(
    @PrimaryKey val id: Long,
    val title: String
)

@Entity(
    tableName = "contact_group_members",
    primaryKeys = ["group_id", "contact_lookup_key"],
    indices = [Index(value = ["contact_lookup_key"])]
)
data class ContactGroupMemberEntity(
    @ColumnInfo(name = "group_id") val groupId: Long,
    @ColumnInfo(name = "contact_lookup_key") val contactLookupKey: String
)

