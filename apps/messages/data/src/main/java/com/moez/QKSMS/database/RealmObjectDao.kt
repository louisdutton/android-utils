package dev.octoshrimpy.quik.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface RealmObjectDao {
    @Query("SELECT * FROM realm_objects WHERE type = :type")
    fun all(type: String): List<RealmObjectRecord>

    @Upsert
    fun upsert(record: RealmObjectRecord)

    @Upsert
    fun upsert(records: List<RealmObjectRecord>)

    @Query("DELETE FROM realm_objects WHERE type = :type")
    fun deleteType(type: String)

    @Query("DELETE FROM realm_objects WHERE type = :type AND object_key = :objectKey")
    fun delete(type: String, objectKey: String)
}
