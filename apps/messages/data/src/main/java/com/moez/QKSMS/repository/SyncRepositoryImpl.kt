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

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.Telephony
import com.f2prateek.rx.preferences2.RxSharedPreferences
import dev.octoshrimpy.quik.database.ContactDao
import dev.octoshrimpy.quik.database.ContactGroupEntity
import dev.octoshrimpy.quik.database.ContactGroupMemberEntity
import dev.octoshrimpy.quik.database.ConversationDao
import dev.octoshrimpy.quik.database.ConversationRecipientEntity
import dev.octoshrimpy.quik.database.EmojiReactionDao
import dev.octoshrimpy.quik.database.MessageDao
import dev.octoshrimpy.quik.database.MmsPartDao
import dev.octoshrimpy.quik.database.RecipientDao
import dev.octoshrimpy.quik.database.SyncStateDao
import dev.octoshrimpy.quik.database.SyncStateEntity
import dev.octoshrimpy.quik.database.toEntity
import dev.octoshrimpy.quik.database.toModel
import dev.octoshrimpy.quik.extensions.forEach
import dev.octoshrimpy.quik.extensions.map
import dev.octoshrimpy.quik.interactor.DeduplicateMessages
import dev.octoshrimpy.quik.manager.KeyManager
import dev.octoshrimpy.quik.mapper.CursorToContact
import dev.octoshrimpy.quik.mapper.CursorToContactGroup
import dev.octoshrimpy.quik.mapper.CursorToContactGroupMember
import dev.octoshrimpy.quik.mapper.CursorToConversation
import dev.octoshrimpy.quik.mapper.CursorToMessage
import dev.octoshrimpy.quik.mapper.CursorToPart
import dev.octoshrimpy.quik.mapper.CursorToRecipient
import dev.octoshrimpy.quik.model.Contact
import dev.octoshrimpy.quik.model.ContactGroup
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.MmsPart
import dev.octoshrimpy.quik.model.PhoneNumber
import dev.octoshrimpy.quik.model.Recipient
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import dev.octoshrimpy.quik.util.tryOrNull
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val contentResolver: ContentResolver,
    private val conversationRepo: ConversationRepository,
    private val cursorToConversation: CursorToConversation,
    private val cursorToMessage: CursorToMessage,
    private val cursorToPart: CursorToPart,
    private val cursorToRecipient: CursorToRecipient,
    private val cursorToContact: CursorToContact,
    private val cursorToContactGroup: CursorToContactGroup,
    private val cursorToContactGroupMember: CursorToContactGroupMember,
    private val keys: KeyManager,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val messageRepo: Provider<MessageRepository>,
    private val rxPrefs: RxSharedPreferences,
    private val reactions: EmojiReactionRepository,
    private val messageDao: MessageDao,
    private val mmsPartDao: MmsPartDao,
    private val conversationDao: ConversationDao,
    private val recipientDao: RecipientDao,
    private val contactDao: ContactDao,
    private val syncStateDao: SyncStateDao,
    private val emojiReactionDao: EmojiReactionDao,
) : SyncRepository {
    companion object {
        private const val LAST_MESSAGE_SYNC_KEY = "last_message_sync"
    }

    override val syncProgress: Subject<SyncRepository.SyncProgress> =
        BehaviorSubject.createDefault(SyncRepository.SyncProgress.Idle)

    override fun lastMessageSync(): Long = syncStateDao.value(LAST_MESSAGE_SYNC_KEY) ?: 0

    override fun syncMessages() {
        val oldBlockedSenders = rxPrefs.getStringSet("pref_key_blocked_senders")

        if (syncProgress.blockingFirst() is SyncRepository.SyncProgress.Running) return
        syncProgress.onNext(SyncRepository.SyncProgress.Running(0, 0, true))

        val handlerThread = HandlerThread("MessagesSyncThread")
        handlerThread.start()
        Handler(handlerThread.looper).post {
            try {
                val persistedData = conversationDao.conversationsWithLocalState()
                    .associateBy { conversation -> conversation.id }
                    .toMutableMap()

                oldBlockedSenders.get()
                    .map { threadIdString -> threadIdString.toLong() }
                    .filter { threadId -> !persistedData.contains(threadId) }
                    .forEach { threadId ->
                        persistedData[threadId] = Conversation(id = threadId, blocked = true).toEntity()
                    }

                val sourceContacts = getContacts()

                removeOldSqliteMessages()
                keys.reset()

                val partsCursor = cursorToPart.getPartsCursor()
                val messageCursor = cursorToMessage.getMessagesCursor()
                val conversationCursor = cursorToConversation.getConversationsCursor()
                val recipientCursor = cursorToRecipient.getRecipientCursor()

                val max = (partsCursor?.count ?: 0) +
                    (messageCursor?.count ?: 0) +
                    (conversationCursor?.count ?: 0) +
                    (recipientCursor?.count ?: 0)

                var progress = 0

                val parts = mutableListOf<MmsPart>()
                partsCursor?.use {
                    partsCursor.forEach { cursor ->
                        tryOrNull {
                            parts += cursorToPart.map(cursor)
                            progress++
                        }
                    }
                }
                mmsPartDao.upsert(parts.map { part -> part.toEntity() })
                val partsByContentId = parts.groupBy { part -> part.messageId }

                val messages = mutableListOf<Message>()
                messageCursor?.use {
                    val messageColumns = CursorToMessage.MessageColumns(messageCursor)
                    messageCursor.forEach { cursor ->
                        tryOrNull {
                            syncProgress.onNext(
                                SyncRepository.SyncProgress.Running(max, ++progress, false)
                            )
                            val message = cursorToMessage.map(Pair(cursor, messageColumns)).apply {
                                if (isMms()) {
                                    this.parts = partsByContentId[contentId].orEmpty().toMutableList()
                                }
                            }
                            messages += message
                        }
                    }
                }
                messageDao.upsert(messages.map { message -> message.toEntity() })

                persistContacts(sourceContacts)
                val contactGroups = getContactGroups(sourceContacts)
                persistContactGroups(contactGroups)

                val recipients = mutableListOf<Recipient>()
                recipientCursor?.use {
                    recipientCursor.forEach { cursor ->
                        tryOrNull {
                            syncProgress.onNext(
                                SyncRepository.SyncProgress.Running(max, ++progress, false)
                            )
                            val rec = cursorToRecipient.map(cursor).apply {
                                contact = sourceContacts.firstOrNull { contact ->
                                    contact.numbers.any { number ->
                                        phoneNumberUtils.compare(address, number.address)
                                    }
                                }
                            }
                            recipients += rec
                        }
                    }
                }
                recipientDao.upsert(recipients.map { recipient -> recipient.toEntity() })
                val recipientsById = recipients.associateBy { recipient -> recipient.id }
                val messagesByThread = messages.groupBy { message -> message.threadId }

                val conversations = mutableListOf<Conversation>()
                conversationCursor?.use {
                    conversationCursor.forEach { cursor ->
                        tryOrNull {
                            syncProgress.onNext(
                                SyncRepository.SyncProgress.Running(max, ++progress, false)
                            )
                            val conversation = cursorToConversation.map(cursor).apply {
                                val syncedRecipients = this.recipients.mapNotNull { recipient ->
                                    recipientsById[recipient.id]
                                }
                                recipients.clear()
                                recipients.addAll(syncedRecipients)

                                persistedData[id]?.let { persistedConversation ->
                                    archived = persistedConversation.archived
                                    blocked = persistedConversation.blocked
                                    pinned = persistedConversation.pinned
                                    name = persistedConversation.name
                                    blockingClient = persistedConversation.blockingClient
                                    blockReason = persistedConversation.blockReason
                                    sendAsGroup = persistedConversation.sendAsGroup
                                }

                                lastMessage = messagesByThread[id]?.maxByOrNull { message -> message.date }
                            }
                            conversations += conversation
                        }
                    }
                }
                conversationDao.upsert(conversations.map { conversation -> conversation.toEntity() })
                recipientDao.upsertConversationRecipients(conversations.flatMap { conversation ->
                    conversation.recipients.map { recipient ->
                        ConversationRecipientEntity(conversation.id, recipient.id)
                    }
                })

                syncProgress.onNext(SyncRepository.SyncProgress.ParsingEmojis(0, 0, true))
                reactions.deleteAndReparseAllEmojiReactions { progress ->
                    syncProgress.onNext(progress)
                }

                if (rxPrefs.getBoolean("autoDeduplicateMessages").get()) {
                    DeduplicateMessages(messageRepo.get())
                        .buildObservable(Unit)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe { result ->
                            when (result) {
                                is MessageRepository.DeduplicationResult.NoDuplicates -> {
                                    Timber.i("No duplicate messages found.")
                                }
                                is MessageRepository.DeduplicationResult.Success -> {
                                    Timber.i("Deleted duplicate messages")
                                }
                                is MessageRepository.DeduplicationResult.Failure -> {
                                    Timber.e(result.error, "Deduplication failed")
                                }
                            }
                        }
                }

                val syncedAt = System.currentTimeMillis()
                syncStateDao.upsert(SyncStateEntity(LAST_MESSAGE_SYNC_KEY, syncedAt))
                oldBlockedSenders.delete()
            } catch (error: Throwable) {
                Timber.e(error, "syncMessages Failed")
            } finally {
                handlerThread.quitSafely()
                syncProgress.onNext(SyncRepository.SyncProgress.Idle)
            }
        }
    }

    override fun syncMessage(uri: Uri, messageId: Long): Message? {
        val type = when {
            uri.toString().contains(Message.TYPE_MMS) -> Message.TYPE_MMS
            uri.toString().contains(Message.TYPE_SMS) -> Message.TYPE_SMS
            else -> return null
        }

        val contentId = tryOrNull(false) { ContentUris.parseId(uri) } ?: return null
        val existingId = messageDao.messageByContentId(type, contentId)?.id
            ?: messageDao.message(messageId)
                ?.takeIf { message -> message.contentId == 0L }
                ?.id

        val stableUri = when (type) {
            Message.TYPE_MMS -> ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, contentId)
            else -> ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, contentId)
        }

        return contentResolver.query(stableUri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null

            val columnsMap = CursorToMessage.MessageColumns(cursor)
            cursorToMessage.map(Pair(cursor, columnsMap)).apply {
                existingId?.let { id = it }

                if (isMms()) {
                    parts = cursorToPart.getPartsCursor(contentId)
                        ?.map { cursorToPart.map(it) }
                        ?.toMutableList()
                        ?: mutableListOf()
                }

                conversationRepo.getOrCreateConversation(threadId)
                mmsPartDao.upsert(parts.map { part -> part.toEntity() })
                messageDao.upsert(toEntity())

                val parsedReaction = reactions.parseEmojiReaction(getText(false))
                if (parsedReaction != null) {
                    val targetMessage = reactions.findTargetMessage(
                        threadId,
                        parsedReaction.originalMessage,
                    )
                    reactions.saveEmojiReaction(
                        this,
                        parsedReaction,
                        targetMessage,
                    )
                }
            }
        }
    }

    override fun syncContacts() {
        val contacts = getContacts()
        persistContacts(contacts)
        persistContactGroups(getContactGroups(contacts))

        val updatedRecipients = recipientDao.recipients().map { recipient ->
            recipient.toModel(
                contact = contacts.firstOrNull { contact ->
                    contact.numbers.any { number ->
                        phoneNumberUtils.compare(recipient.address, number.address)
                    }
                }
            )
        }
        recipientDao.upsert(updatedRecipients.map { recipient -> recipient.toEntity() })
    }

    private fun getContacts(): List<Contact> {
        val defaultNumberIds = contactDao.defaultPhoneNumbers()
            .map { number -> number.id }
            .toSet()

        return cursorToContact.getContactsCursor()
            ?.map { cursor -> cursorToContact.map(cursor) }
            ?.groupBy { contact -> contact.lookupKey }
            ?.map { contacts ->
                val uniqueNumbers = mutableListOf<PhoneNumber>()
                contacts.value
                    .flatMap { contact -> contact.numbers }
                    .forEach { number ->
                        number.isDefault = defaultNumberIds.contains(number.id)
                        val duplicate = uniqueNumbers.find { other ->
                            phoneNumberUtils.compare(number.address, other.address)
                        }

                        if (duplicate == null) {
                            uniqueNumbers += number
                        } else if (!duplicate.isDefault && number.isDefault) {
                            duplicate.isDefault = true
                        }
                    }

                contacts.value.first().apply {
                    numbers.clear()
                    numbers.addAll(uniqueNumbers)
                }
            } ?: listOf()
    }

    private fun getContactGroups(contacts: List<Contact>): List<ContactGroup> {
        val groupMembers = cursorToContactGroupMember.getGroupMembersCursor()
            ?.map(cursorToContactGroupMember::map)
            .orEmpty()

        val groups = cursorToContactGroup.getContactGroupsCursor()
            ?.map(cursorToContactGroup::map)
            .orEmpty()

        groups.forEach { group ->
            group.contacts.addAll(groupMembers
                .filter { member -> member.groupId == group.id }
                .mapNotNull { member -> contacts.find { contact -> contact.lookupKey == member.lookupKey } })
        }

        return groups
    }

    private fun removeOldSqliteMessages() {
        emojiReactionDao.deleteAll()
        recipientDao.deleteAllConversationRecipients()
        conversationDao.deleteAll()
        messageDao.deleteAll()
        mmsPartDao.deleteAll()
        recipientDao.deleteAll()
        contactDao.deleteGroupMembers()
        contactDao.deleteGroups()
        contactDao.deletePhoneNumbers()
        contactDao.deleteContacts()
    }

    private fun persistContacts(contacts: List<Contact>) {
        contactDao.deleteGroupMembers()
        contactDao.deleteGroups()
        contactDao.deletePhoneNumbers()
        contactDao.deleteContacts()
        contactDao.upsertContacts(contacts.map { contact -> contact.toEntity() })
        contactDao.upsertPhoneNumbers(contacts.flatMap { contact ->
            contact.numbers.map { number -> number.toEntity(contact.lookupKey) }
        })
    }

    private fun persistContactGroups(groups: List<ContactGroup>) {
        contactDao.upsertGroups(groups.map { group -> ContactGroupEntity(group.id, group.title) })
        contactDao.upsertGroupMembers(groups.flatMap { group ->
            group.contacts.map { contact ->
                ContactGroupMemberEntity(group.id, contact.lookupKey)
            }
        })
    }
}
