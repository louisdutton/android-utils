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

import android.content.ContentUris
import android.content.Context
import dev.octoshrimpy.quik.compat.TelephonyCompat
import dev.octoshrimpy.quik.database.ContactDao
import dev.octoshrimpy.quik.database.ContactEntity
import dev.octoshrimpy.quik.database.ConversationDao
import dev.octoshrimpy.quik.database.ConversationEntity
import dev.octoshrimpy.quik.database.ConversationRecipientEntity
import dev.octoshrimpy.quik.database.MessageDao
import dev.octoshrimpy.quik.database.MessageEntity
import dev.octoshrimpy.quik.database.MmsPartDao
import dev.octoshrimpy.quik.database.RecipientDao
import dev.octoshrimpy.quik.database.RecipientEntity
import dev.octoshrimpy.quik.database.toEntity
import dev.octoshrimpy.quik.database.toModel
import dev.octoshrimpy.quik.extensions.map
import dev.octoshrimpy.quik.filter.ConversationFilter
import dev.octoshrimpy.quik.mapper.CursorToConversation
import dev.octoshrimpy.quik.mapper.CursorToRecipient
import dev.octoshrimpy.quik.model.Contact
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.Recipient
import dev.octoshrimpy.quik.model.SearchResult
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import dev.octoshrimpy.quik.util.tryOrNull
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs

class ConversationRepositoryImpl @Inject constructor(
    private val context: Context,
    private val conversationFilter: ConversationFilter,
    private val cursorToConversation: CursorToConversation,
    private val cursorToRecipient: CursorToRecipient,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val conversationDao: ConversationDao,
    private val recipientDao: RecipientDao,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val mmsPartDao: MmsPartDao
) : ConversationRepository {

    override fun observeConversations(
        unreadAtTop: Boolean,
        archived: Boolean
    ): Observable<List<Conversation>> =
        conversationDao.observeConversations(archived)
            .map { conversations -> sortConversations(conversations.map(::mapConversation), unreadAtTop) }
            .toObservable()

    override fun getConversations(
        unreadAtTop: Boolean,
        archived: Boolean
    ): List<Conversation> =
        sortConversations(conversationDao.conversations(archived).map(::mapConversation), unreadAtTop)

    override fun getConversationsSnapshot(unreadAtTop: Boolean): List<Conversation> =
        getConversations(unreadAtTop, archived = false)

    override fun getTopConversations(): List<Conversation> =
        getConversations(unreadAtTop = false)
            .filter { conversation ->
                conversation.pinned ||
                    conversation.date > System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
            }
            .sortedWith(
                compareByDescending<Conversation> { it.pinned }
                    .thenByDescending { conversation ->
                        messageDao.messages(conversation.id)
                            .count { message ->
                                message.date > System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
                            }
                    }
            )
            .take(5)

    override fun setConversationName(id: Long, name: String): Completable =
        Completable.fromAction { conversationDao.updateName(id, name) }
            .subscribeOn(Schedulers.io())

    override fun searchConversations(query: CharSequence): List<SearchResult> {
        val searchQuery = query.toString()
        val conversations = getConversations(unreadAtTop = false)

        val messagesByConversation = messageDao.searchMessages(searchQuery)
            .groupBy { message -> message.threadId }
            .mapNotNull { (threadId, messages) ->
                conversations.firstOrNull { it.id == threadId }
                    ?.let { conversation -> SearchResult(searchQuery, conversation, messages.size) }
            }
            .sortedByDescending { result -> result.messages }

        return conversations
            .filter { conversation -> conversationFilter.filter(conversation, searchQuery) }
            .map { conversation -> SearchResult(searchQuery, conversation, 0) } + messagesByConversation
    }

    override fun getBlockedConversations(): List<Conversation> =
        conversationDao.blockedConversations().map(::mapConversation)

    override fun observeBlockedConversations(): Observable<List<Conversation>> =
        Observable.fromCallable { getBlockedConversations() }

    override fun observeConversation(threadId: Long): Observable<Conversation> =
        conversationDao.observeConversation(threadId)
            .map { conversations ->
                conversations.firstOrNull()?.let(::mapConversation) ?: Conversation(id = threadId)
            }
            .toObservable()

    override fun getConversation(threadId: Long): Conversation? =
        conversationDao.conversation(threadId)?.let(::mapConversation)

    override fun updateSendAsGroup(threadId: Long, sendAsGroup: Boolean): Unit =
        conversationDao.updateSendAsGroup(threadId, sendAsGroup)

    override fun getUnseenIds(archived: Boolean): List<Long> =
        conversationDao.unseenConversations(archived).map { conversation -> conversation.id }

    override fun getUnreadIds(archived: Boolean): List<Long> =
        conversationDao.unreadConversations(archived).map { conversation -> conversation.id }

    override fun getConversationAndLastSenderContactName(threadId: Long): Pair<Conversation?, String?>? =
        getConversation(threadId)?.let { conversation ->
            val senderName = conversation.lastMessage?.address?.let { address ->
                conversation.recipients.find { recipient ->
                    phoneNumberUtils.compare(recipient.address, address)
                }?.contact?.name
            }
            conversation to senderName
        }

    override fun getConversations(vararg threadIds: Long): List<Conversation> =
        conversationDao.conversations(threadIds.toList()).map(::mapConversation)

    override fun getUnmanagedConversations(): Observable<List<Conversation>> =
        observeConversations(unreadAtTop = false).map { conversations -> conversations.take(5) }

    override fun getRecipients(): List<Recipient> =
        recipientDao.recipients().map(::mapRecipient)

    override fun getUnmanagedRecipients(): Observable<List<Recipient>> =
        Observable.fromCallable {
            recipientDao.recipients()
                .map(::mapRecipient)
                .filter { recipient -> recipient.contact != null }
        }.subscribeOn(Schedulers.io())

    override fun getRecipient(recipientId: Long): Recipient? =
        recipientDao.recipient(recipientId)?.let(::mapRecipient)

    override fun createConversation(threadId: Long, sendAsGroup: Boolean): Conversation? =
        createConversationFromCp(threadId, sendAsGroup)

    override fun getConversation(recipients: Collection<String>): Conversation? =
        getConversations(unreadAtTop = false)
            .filter { conversation -> conversation.recipients.size == recipients.size }
            .find { conversation ->
                conversation.recipients.map { it.address }.all { recipientAddress ->
                    recipients.any { phoneNumberUtils.compare(it, recipientAddress) }
                }
            }

    override fun createConversation(addresses: Collection<String>, sendAsGroup: Boolean): Conversation? =
        TelephonyCompat.getOrCreateThreadId(context, addresses.toSet())
            .takeIf { it != 0L }
            ?.let { providerThreadId ->
                createConversationFromCp(providerThreadId, sendAsGroup)
                    ?: createEmptyConversation(providerThreadId, addresses, sendAsGroup)
            }

    override fun getOrCreateConversation(threadId: Long, sendAsGroup: Boolean): Conversation? =
        getConversation(threadId) ?: createConversation(threadId, sendAsGroup)

    override fun getOrCreateConversation(addresses: Collection<String>, sendAsGroup: Boolean): Conversation? =
        getConversation(addresses) ?: createConversation(addresses, sendAsGroup)

    override fun saveDraft(threadId: Long, draft: String) =
        conversationDao.updateDraft(threadId, draft, System.currentTimeMillis())

    override fun updateConversations(threadIds: Collection<Long>) =
        threadIds.forEach(conversationDao::refreshLastMessage)

    override fun markArchived(vararg threadIds: Long) =
        conversationDao.updateArchived(threadIds.toList(), true)

    override fun markUnarchived(threadIds: Collection<Long>) =
        conversationDao.updateArchived(threadIds, false)

    override fun markPinned(vararg threadIds: Long) =
        conversationDao.updatePinned(threadIds.toList(), true)

    override fun markUnpinned(vararg threadIds: Long) =
        conversationDao.updatePinned(threadIds.toList(), false)

    override fun markBlocked(threadIds: Collection<Long>, blockingClient: Int, blockReason: String?) =
        conversationDao.updateBlocked(threadIds, true, blockingClient, blockReason)

    override fun markUnblocked(vararg threadIds: Long) =
        conversationDao.updateBlocked(threadIds.toList(), false, null, null)

    override fun deleteConversations(vararg threadIds: Long) {
        val threadIdList = threadIds.toList()
        val contentIds = threadIdList
            .flatMap { threadId -> messageDao.messages(threadId).map { message -> message.contentId } }

        if (contentIds.isNotEmpty()) {
            mmsPartDao.deleteForMessageContentIds(contentIds)
        }
        messageDao.deleteByThreadIds(threadIdList)
        conversationDao.delete(threadIdList)

        threadIds.forEach { threadId ->
            context.contentResolver.delete(
                ContentUris.withAppendedId(TelephonyCompat.THREADS_CONTENT_URI, threadId),
                null,
                null
            )
        }
    }

    private fun createConversationFromCp(threadId: Long, sendAsGroup: Boolean): Conversation? =
        tryOrNull(true) {
            cursorToConversation.getConversationsCursor()
                ?.map(cursorToConversation::map)
                ?.firstOrNull { conversation -> conversation.id == threadId }
                ?.also { conversation ->
                    val contacts = contactDao.contacts().map(::mapContact)
                    val matchedRecipients = conversation.recipients
                        .mapNotNull { recipient ->
                            cursorToRecipient.getRecipientCursor(recipient.id)?.use { cursor ->
                                cursor.map { cursorToRecipient.map(it) }
                            }
                        }
                        .flatten()
                        .map { recipient ->
                            recipient.apply {
                                contact = contacts.firstOrNull { contact ->
                                    contact.numbers.any { phoneNumber ->
                                        phoneNumberUtils.compare(phoneNumber.address, address)
                                    }
                                }
                            }
                        }

                    conversation.recipients.clear()
                    conversation.recipients.addAll(matchedRecipients)
                    conversation.sendAsGroup = conversation.recipients.size > 1 && sendAsGroup
                    conversation.lastMessage = messageDao.messages(threadId).lastOrNull()?.let(::mapMessage)

                    persistConversation(conversation)
                }
        }

    private fun createEmptyConversation(
        threadId: Long,
        addresses: Collection<String>,
        sendAsGroup: Boolean
    ): Conversation {
        val contacts = contactDao.contacts().map(::mapContact)
        val matchedRecipients = addresses.map { address ->
            Recipient(
                id = stableSyntheticRecipientId(address),
                address = address,
                contact = contacts.firstOrNull { contact ->
                    contact.numbers.any { phoneNumber -> phoneNumberUtils.compare(phoneNumber.address, address) }
                }
            )
        }

        val conversation = Conversation(
            id = threadId,
            recipients = matchedRecipients.toMutableList(),
            sendAsGroup = matchedRecipients.size > 1 && sendAsGroup
        )
        persistConversation(conversation)
        return conversation
    }

    private fun persistConversation(conversation: Conversation) {
        conversationDao.upsert(conversation.toEntity())
        if (conversation.recipients.isNotEmpty()) {
            recipientDao.upsert(conversation.recipients.map { recipient -> recipient.toEntity() })
            recipientDao.deleteConversationRecipients(conversation.id)
            recipientDao.upsertConversationRecipients(
                conversation.recipients.map { recipient ->
                    ConversationRecipientEntity(conversation.id, recipient.id)
                }
            )
        }
    }

    private fun sortConversations(
        conversations: List<Conversation>,
        unreadAtTop: Boolean
    ): List<Conversation> {
        val comparator = compareByDescending<Conversation> { it.pinned }
            .thenByDescending { it.draft.isNotEmpty() }
            .thenByDescending { it.date }

        return if (unreadAtTop) {
            conversations.sortedWith(
                compareBy<Conversation> { it.lastMessage?.read ?: true }
                    .then(comparator)
            )
        } else {
            conversations.sortedWith(comparator)
        }
    }

    private fun mapConversation(conversation: ConversationEntity): Conversation {
        val recipients = recipientDao.recipientsForConversation(conversation.id).map(::mapRecipient)
        val lastMessage = conversation.lastMessageId?.let(messageDao::message)?.let(::mapMessage)
        return conversation.toModel(recipients, lastMessage)
    }

    private fun mapMessage(message: MessageEntity): Message {
        val parts = mmsPartDao.partsForMessage(message.contentId).map { part -> part.toModel() }
        return message.toModel(parts)
    }

    private fun mapRecipient(recipient: RecipientEntity): Recipient =
        recipient.toModel(recipient.contactLookupKey?.let(contactDao::contact)?.let(::mapContact))

    private fun mapContact(contact: ContactEntity): Contact =
        contact.toModel(contactDao.phoneNumbers(contact.lookupKey).map { number -> number.toModel() })

    private fun stableSyntheticRecipientId(address: String): Long =
        -abs(address.hashCode().toLong()).coerceAtLeast(1L)
}
