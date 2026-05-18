/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.repository

import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import dev.octoshrimpy.quik.database.ContactDao
import dev.octoshrimpy.quik.database.ContactEntity
import dev.octoshrimpy.quik.database.toModel
import dev.octoshrimpy.quik.extensions.asFlowable
import dev.octoshrimpy.quik.extensions.mapNotNull
import dev.octoshrimpy.quik.model.Contact
import dev.octoshrimpy.quik.model.ContactGroup
import dev.octoshrimpy.quik.util.Preferences
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepositoryImpl @Inject constructor(
    private val context: Context,
    private val prefs: Preferences,
    private val contactDao: ContactDao
) : ContactRepository {

    override fun findContactUri(address: String): Single<Uri> {
        return Flowable.just(address)
                .map {
                    when {
                        address.contains('@') -> {
                            Uri.withAppendedPath(Email.CONTENT_FILTER_URI, Uri.encode(address))
                        }

                        else -> {
                            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
                        }
                    }
                }
                .mapNotNull { uri -> context.contentResolver.query(uri, arrayOf(BaseColumns._ID), null, null, null) }
                .flatMap { cursor -> cursor.asFlowable() }
                .firstOrError()
                .map { cursor -> cursor.getString(cursor.getColumnIndexOrThrow(BaseColumns._ID)) }
                .map { id -> Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, id) }
    }

    override fun getContacts(): List<Contact> =
        contactDao.contacts().map(::mapContact)

    override fun getUnmanagedContact(lookupKey: String): Contact? {
        return contactDao.contact(lookupKey)?.let(::mapContact)
    }

    override fun getUnmanagedAllContacts(): List<Contact> = getContacts()

    override fun getUnmanagedContacts(starred: Boolean): Observable<List<Contact>> {
        val mobileOnly = prefs.mobileOnly.get()
        val mobileLabel by lazy { Phone.getTypeLabel(context.resources, Phone.TYPE_MOBILE, "Mobile").toString() }

        return Observable.fromCallable { getContacts() }
                .map { contacts ->
                    val starredContacts = if (starred) contacts.filter { contact -> contact.starred } else contacts
                    if (mobileOnly) {
                        starredContacts.map { contact ->
                            val filteredNumbers = contact.numbers.filter { number -> number.type == mobileLabel }
                            contact.numbers.clear()
                            contact.numbers.addAll(filteredNumbers)
                            contact
                        }
                    } else {
                        starredContacts
                    }
                }
                .map { contacts ->
                    contacts.sortedWith { c1, c2 ->
                        val initial = c1.name.firstOrNull()
                        val other = c2.name.firstOrNull()
                        if (initial?.isLetter() == true && other?.isLetter() != true) {
                            -1
                        } else if (initial?.isLetter() != true && other?.isLetter() == true) {
                            1
                        } else {
                            c1.name.compareTo(c2.name, ignoreCase = true)
                        }
                    }
                }
                .subscribeOn(Schedulers.io())
    }

    override fun getUnmanagedContactGroups(): Observable<List<ContactGroup>> {
        return Observable.fromCallable {
            contactDao.contactGroups().mapNotNull { group ->
                val contacts = contactDao.contactsForGroup(group.id).map(::mapContact)
                group.toModel(contacts).takeIf { it.contacts.isNotEmpty() }
            }
        }.subscribeOn(Schedulers.io())
    }

    override fun setDefaultPhoneNumber(lookupKey: String, phoneNumberId: Long) {
        contactDao.setDefaultPhoneNumber(lookupKey, phoneNumberId)
    }

    override fun isContact(address: String): Boolean {
        val uri : Uri
        if (address.contains('@')) {
            uri = Uri.withAppendedPath(Email.CONTENT_FILTER_URI, Uri.encode(address))
        } else {
            uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        }
        return context.contentResolver.query(
            uri,
            arrayOf(BaseColumns._ID),
            null,
            null,
            null
        )?.use { cursor ->
            cursor.count > 0
        } ?: false
    }

    private fun mapContact(contact: ContactEntity): Contact =
        contact.toModel(contactDao.phoneNumbers(contact.lookupKey).map { number -> number.toModel() })

}
