package dev.octoshrimpy.quik.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name ASC")
    fun contacts(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE lookup_key = :lookupKey LIMIT 1")
    fun contact(lookupKey: String): ContactEntity?

    @Query("SELECT * FROM phone_numbers WHERE contact_lookup_key = :lookupKey")
    fun phoneNumbers(lookupKey: String): List<PhoneNumberEntity>

    @Query("SELECT * FROM phone_numbers WHERE is_default = 1")
    fun defaultPhoneNumbers(): List<PhoneNumberEntity>

    @Query("SELECT * FROM phone_numbers")
    fun phoneNumbers(): List<PhoneNumberEntity>

    @Query("SELECT * FROM contact_groups ORDER BY title ASC")
    fun contactGroups(): List<ContactGroupEntity>

    @Query(
        """
        SELECT c.* FROM contacts c
        INNER JOIN contact_group_members m ON m.contact_lookup_key = c.lookup_key
        WHERE m.group_id = :groupId
        ORDER BY c.name ASC
        """
    )
    fun contactsForGroup(groupId: Long): List<ContactEntity>

    @Upsert
    fun upsertContacts(contacts: Collection<ContactEntity>)

    @Upsert
    fun upsertPhoneNumbers(numbers: Collection<PhoneNumberEntity>)

    @Upsert
    fun upsertGroups(groups: Collection<ContactGroupEntity>)

    @Upsert
    fun upsertGroupMembers(members: Collection<ContactGroupMemberEntity>)

    @Query("DELETE FROM contacts")
    fun deleteContacts()

    @Query("DELETE FROM phone_numbers")
    fun deletePhoneNumbers()

    @Query("DELETE FROM contact_groups")
    fun deleteGroups()

    @Query("DELETE FROM contact_group_members")
    fun deleteGroupMembers()

    @Query("UPDATE phone_numbers SET is_default = CASE WHEN id = :phoneNumberId THEN 1 ELSE 0 END WHERE contact_lookup_key = :lookupKey")
    fun setDefaultPhoneNumber(lookupKey: String, phoneNumberId: Long)
}
