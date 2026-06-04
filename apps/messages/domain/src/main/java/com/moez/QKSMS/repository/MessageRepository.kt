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

import android.net.Uri
import dev.octoshrimpy.quik.model.Attachment
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.MmsPart
import io.reactivex.Flowable
import io.reactivex.Observable

interface MessageRepository {
    sealed class DeduplicationProgress {
        object Idle : DeduplicationProgress()
        data class Running(val max: Int, val progress: Int, val indeterminate: Boolean) : DeduplicationProgress()
    }

    sealed class DeduplicationResult {
        object Success : DeduplicationResult()
        object NoDuplicates : DeduplicationResult()
        data class Failure(val error: Throwable) : DeduplicationResult()
    }

    val deduplicationProgress: Observable<DeduplicationProgress>

    fun observeMessages(threadId: Long, query: String = ""): Observable<List<Message>>

    fun getMessagesSnapshot(threadId: Long, query: String = ""): List<Message>

    fun getMessages(threadId: Long, query: String = ""): List<Message>

    fun getMessagesSync(threadId: Long, query: String = ""): List<Message>

    fun getMessage(messageId: Long): Message?

    fun getUnmanagedMessage(messageId: Long): Message?

    fun getMessages(messageIds: Collection<Long>): List<Message>

    fun getMessageForPart(id: Long): Message?

    fun getLastIncomingMessage(threadId: Long): List<Message>

    fun getUnreadCount(): Long

    fun getPart(id: Long): MmsPart?

    fun getPartsForConversation(threadId: Long): List<MmsPart>

    fun savePart(id: Long): Uri?

    fun getUnreadUnseenMessages(threadId: Long): List<Message>

    fun getUnreadMessages(threadId: Long): List<Message>

    fun markAllSeen(): Int

    fun markSeen(threadIds: Collection<Long>): Int

    fun markRead(threadIds: Collection<Long>): Int

    fun markUnread(threadIds: Collection<Long>): Int

    fun markSending(messageId: Long)

    fun markSent(messageId: Long)

    fun markFailed(messageId: Long, resultCode: Int): Boolean

    fun markDelivered(messageId: Long)

    fun markDeliveryFailed(messageId: Long, resultCode: Int)

    fun sendNewMessages(
        subId: Int, toAddresses: Collection<String>, body: String,
        attachments: Collection<Attachment>, sendAsGroup: Boolean
    ): Collection<Message>

    fun sendMessage(message: Message): Collection<Message>

    fun sendMessage(messageId: Long): Collection<Message>

    fun insertReceivedSms(subId: Int, address: String, body: String, sentTime: Long): Message

    fun deleteMessages(messageIds: Collection<Long>)

    fun getOldMessageCounts(maxAgeDays: Int): Map<Long, Int>

    fun deleteOldMessages(maxAgeDays: Int)

    fun deduplicateMessages(): Flowable<DeduplicationResult>

    fun markAsSendingNow(messageId: Long)
}
