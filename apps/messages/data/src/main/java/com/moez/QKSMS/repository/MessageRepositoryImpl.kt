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

import com.moez.QKSMS.manager.QkTransaction
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Telephony
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.telephony.SmsManager
import android.webkit.MimeTypeMap
import androidx.core.content.contentValuesOf
import com.google.android.mms.ContentType
import com.klinker.android.send_message.SmsManagerFactory
import dev.octoshrimpy.quik.common.util.extensions.now
import dev.octoshrimpy.quik.compat.TelephonyCompat
import dev.octoshrimpy.quik.database.ConversationDao
import dev.octoshrimpy.quik.database.EmojiReactionDao
import dev.octoshrimpy.quik.database.MessageDao
import dev.octoshrimpy.quik.database.MmsPartDao
import dev.octoshrimpy.quik.database.toEntity
import dev.octoshrimpy.quik.database.toModel
import dev.octoshrimpy.quik.extensions.isImage
import dev.octoshrimpy.quik.extensions.isVideo
import dev.octoshrimpy.quik.extensions.map
import dev.octoshrimpy.quik.extensions.resourceExists
import dev.octoshrimpy.quik.manager.ActiveConversationManager
import dev.octoshrimpy.quik.manager.KeyManager
import dev.octoshrimpy.quik.mapper.CursorToMessage
import dev.octoshrimpy.quik.mapper.CursorToPart
import dev.octoshrimpy.quik.model.Attachment
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.Message.Companion.TYPE_MMS
import dev.octoshrimpy.quik.model.Message.Companion.TYPE_SMS
import dev.octoshrimpy.quik.model.MmsPart
import dev.octoshrimpy.quik.receiver.MessageDeliveredReceiver
import dev.octoshrimpy.quik.receiver.MessageSentReceiver
import dev.octoshrimpy.quik.receiver.SendDelayedMessageReceiver
import dev.octoshrimpy.quik.receiver.SendDelayedMessageReceiver.Companion.MESSAGE_ID_EXTRA
import dev.octoshrimpy.quik.util.ImageUtils
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import dev.octoshrimpy.quik.util.Preferences
import dev.octoshrimpy.quik.util.sha256
import dev.octoshrimpy.quik.util.tryOrNull
import io.reactivex.Flowable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
open class MessageRepositoryImpl @Inject constructor(
    private val activeConversationManager: ActiveConversationManager,
    private val context: Context,
    private val messageIds: KeyManager,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val prefs: Preferences,
    private val syncRepository: SyncRepository,
    private val reactions: EmojiReactionRepository,
    private val cursorToMessage: CursorToMessage,
    private val cursorToPart: CursorToPart,
    private val messageDao: MessageDao,
    private val mmsPartDao: MmsPartDao,
    private val conversationDao: ConversationDao,
    private val emojiReactionDao: EmojiReactionDao,
) : MessageRepository {

    override val deduplicationProgress: Subject<MessageRepository.DeduplicationProgress> =
        BehaviorSubject.createDefault(MessageRepository.DeduplicationProgress.Idle)

    companion object {
        const val TELEPHONY_UPDATE_CHUNK_SIZE = 200
    }

    override fun observeMessages(threadId: Long, query: String) =
        messageDao.observeMessages(threadId, query)
            .map(::mapMessages)
            .toObservable()

    override fun getMessagesSnapshot(threadId: Long, query: String): List<Message> =
        mapMessages(messageDao.messages(threadId, query))

    override fun getMessages(threadId: Long, query: String): List<Message> =
        getMessagesSnapshot(threadId, query)

    override fun getMessagesSync(threadId: Long, query: String): List<Message> =
        getMessagesSnapshot(threadId, query)

    private fun mapMessages(messages: List<dev.octoshrimpy.quik.database.MessageEntity>): List<Message> {
        if (messages.isEmpty()) return emptyList()
        val partsByMessageId = mmsPartDao
            .partsForMessages(messages.map { message -> message.contentId })
            .map { part -> part.toModel() }
            .groupBy { part -> part.messageId }

        val reactionsByTargetId = emojiReactionDao
            .reactionsForTargets(messages.map { message -> message.id })
            .groupBy { reaction -> reaction.targetMessageId }

        return messages.map { message ->
            message.toModel(partsByMessageId[message.contentId].orEmpty()).also { model ->
                model.emojiReactions.addAll(
                    reactionsByTargetId[model.id].orEmpty().map { reaction -> reaction.toModel() }
                )
            }
        }
    }

    override fun getMessage(messageId: Long): Message? =
        messageDao.message(messageId)?.let { message -> mapMessages(listOf(message)).firstOrNull() }

    override fun getUnmanagedMessage(messageId: Long) =
        getMessage(messageId)

    override fun getMessages(messageIds: Collection<Long>): List<Message> =
        mapMessages(messageDao.messages(messageIds))

    override fun getMessageForPart(id: Long) =
        mmsPartDao.messageForPart(id)?.let { message -> mapMessages(listOf(message)).firstOrNull() }

    override fun getLastIncomingMessage(threadId: Long): List<Message> =
        mapMessages(
            messageDao.lastIncomingMessages(
                threadId,
                intArrayOf(Sms.MESSAGE_TYPE_INBOX, Sms.MESSAGE_TYPE_ALL),
                intArrayOf(Mms.MESSAGE_BOX_INBOX, Mms.MESSAGE_BOX_ALL)
            )
        )

    override fun getUnreadCount() =
        conversationDao.unreadConversations(false).size.toLong()

    override fun getPart(id: Long) =
        mmsPartDao.part(id)?.toModel()

    override fun getPartsForConversation(threadId: Long): List<MmsPart> =
        mmsPartDao.mediaPartsForConversation(threadId).map { part -> part.toModel() }

    override fun savePart(id: Long): Uri? {
        val part = getPart(id) ?: return null

        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(part.type)
            ?: return null
        // fileDateAndTime is divided by 1000 in order to remove the extra 0's after date and time
        // This way the file name isn't so long.
        val fileDateAndTime = (part.messages?.first()?.date)?.div(1000)
        val fileName = "QUIK_${part.type.split("/").last()}_$fileDateAndTime.$extension"

        val values = contentValuesOf(
            MediaStore.MediaColumns.DISPLAY_NAME to fileName,
            MediaStore.MediaColumns.MIME_TYPE to part.type,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            values.put(
                MediaStore.MediaColumns.RELATIVE_PATH, when {
                    part.isImage() -> "${Environment.DIRECTORY_PICTURES}/QUIK"
                    part.isVideo() -> "${Environment.DIRECTORY_MOVIES}/QUIK"
                    else -> "${Environment.DIRECTORY_DOWNLOADS}/QUIK"
                }
            )
        }

        val contentUri = when {
            part.isImage() -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            part.isVideo() -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                MediaStore.Downloads.EXTERNAL_CONTENT_URI

            else -> MediaStore.Files.getContentUri("external")
        }

        val uri = context.contentResolver.insert(contentUri, values)
        Timber.v("Saving $fileName (${part.type}) to $uri")

        uri?.let {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                context.contentResolver.openInputStream(part.getUri())?.use { inputStream ->
                    inputStream.copyTo(outputStream, 1024)
                }
            }
            Timber.v("Saved $fileName (${part.type}) to $uri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(
                    uri,
                    contentValuesOf(MediaStore.MediaColumns.IS_PENDING to 0),
                    null,
                    null
                )
                Timber.v("Marked $uri as not pending")
            }
        }

        return uri
    }

    override fun getUnreadUnseenMessages(threadId: Long): List<Message> =
        mapMessages(messageDao.unreadUnseenMessages(threadId))

    override fun getUnreadMessages(threadId: Long): List<Message> =
        mapMessages(messageDao.unreadMessages(threadId))

    // marks all messages in threads as read and/or seen in the native provider
    private fun telephonyMarkSeenRead(
        seen: Boolean?,
        read: Boolean?,
        threadIds: Collection<Long>,
    ): Int {
        if (((seen == null) && (read == null)) || threadIds.isEmpty())
            return -1

        var countUpdated = 0

        // 'read' can be modified at the conversation level which updates all messages
        read?.let {
            tryOrNull(true) {
                // chunked so where clause doesn't get too long if there are many threads
                threadIds.forEach {
                    countUpdated += context.contentResolver.update(
                        ContentUris.withAppendedId(
                            Telephony.MmsSms.CONTENT_CONVERSATIONS_URI,
                            it
                        ),
                        contentValuesOf(Sms.READ to read),
                        "${Sms.READ} = ${if (read) 0 else 1}",
                        null
                    )
                }
            }
        }

        seen?.let {
            // 'seen' has to be modified at the messages level
            threadIds.chunked(TELEPHONY_UPDATE_CHUNK_SIZE).forEach {
                // chunked for smaller where clause size
                val values = contentValuesOf(Sms.SEEN to seen)
                val whereClause = "${Sms.SEEN} = ${if (seen) 0 else 1} " +
                        "and ${Sms.THREAD_ID} in (${it.joinToString(",")})"

                // sms messages
                tryOrNull(true) {
                    countUpdated += context.contentResolver.update(
                        Sms.CONTENT_URI, values, whereClause, null
                    )
                }

                // mms messages
                tryOrNull(true) {
                    countUpdated += context.contentResolver.update(
                        Mms.CONTENT_URI, values, whereClause, null
                    )
                }
            }
        }

        return countUpdated  // a mix of convo and message updates, so not overly useful. meh
    }

    override fun markAllSeen() =
        mutableSetOf<Long>().let { threadIds ->
            messageDao.messagesOlderThan(Long.MAX_VALUE)
                .filter { message -> !message.seen }
                .forEach { message -> threadIds += message.threadId }
            if (threadIds.isNotEmpty()) {
                messageDao.updateSeen(threadIds, true)
                telephonyMarkSeenRead(true, null, threadIds)
            }
            threadIds.size
        }

    override fun markSeen(threadIds: Collection<Long>) =
        if (threadIds.isNotEmpty()) {
            messageDao.updateSeen(threadIds, true)
            telephonyMarkSeenRead(true, null, threadIds)
        } else 0

    override fun markRead(threadIds: Collection<Long>) =
        threadIds.takeIf { it.isNotEmpty() }
            ?.let {
                messageDao.updateSeenRead(threadIds, seen = true, read = true)
                telephonyMarkSeenRead(seen = true, read = true, threadIds = threadIds)
            }
            ?: 0

    override fun markUnread(threadIds: Collection<Long>) =
        threadIds.takeIf { it.isNotEmpty() }
            ?.let {
                messageDao.updateRead(threadIds, read = false)
                telephonyMarkSeenRead(null, false, threadIds)
            }
            ?: 0

    private fun syncProviderMessage(uri: Uri, sendAsGroup: Boolean): Message? {
        // if uri doesn't have valid type
        val type = when {
            uri.toString().contains(TYPE_MMS) -> TYPE_MMS
            uri.toString().contains(TYPE_SMS) -> TYPE_SMS
            else -> return null
        }

        // if uri doesn't have a valid id, fail
        val contentId = tryOrNull(false) { ContentUris.parseId(uri) } ?: return null

        val stableUri = when (type) {
            TYPE_MMS -> ContentUris.withAppendedId(Mms.CONTENT_URI, contentId)
            else -> ContentUris.withAppendedId(Sms.CONTENT_URI, contentId)
        }

        return context.contentResolver.query(
            stableUri, null, null, null, null
        )?.use { cursor ->
            // if there are no rows, return null. else, move to the first row
            if (!cursor.moveToFirst())
                return null

            cursorToMessage.map(Pair(cursor, CursorToMessage.MessageColumns(cursor))).apply {
                this.sendAsGroup = sendAsGroup

                if (isMms()) {
                    parts = mutableListOf<MmsPart>().apply {
                        addAll(
                            cursorToPart.getPartsCursor(contentId)
                                ?.map { cursorToPart.map(it) }
                                .orEmpty()
                        )
                    }
                }

                if (parts.isNotEmpty()) {
                    mmsPartDao.upsert(parts.map { part -> part.toEntity() })
                }
                messageDao.upsert(toEntity())
            }
        }
    }

    override fun sendNewMessages(
        subId: Int, toAddresses: Collection<String>, body: String,
        attachments: Collection<Attachment>, sendAsGroup: Boolean, delayMs: Int
    ): Collection<Message> {
        Timber.v("sending message(s)")

        val parts = mutableListOf<com.google.android.mms.MMSPart>()

        if (attachments.isNotEmpty()) {
            Timber.v("has attachments")
            val smsManager = subId.takeIf { it != -1 }
                ?.let(SmsManagerFactory::createSmsManager)
                ?: SmsManager.getDefault()

            val maxWidth = smsManager.carrierConfigValues
                .getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_WIDTH)
                .takeIf { prefs.mmsSize.get() == -1 }
                ?: Int.MAX_VALUE

            val maxHeight = smsManager.carrierConfigValues
                .getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_HEIGHT)
                .takeIf { prefs.mmsSize.get() == -1 }
                ?: Int.MAX_VALUE

            var remainingBytes = when (prefs.mmsSize.get()) {
                -1 -> smsManager.carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE)
                0 -> Int.MAX_VALUE
                else -> prefs.mmsSize.get() * 1024
            } * 0.9 // Ugly, but buys us a bit of wiggle room

            remainingBytes -= body.takeIf { it.isNotEmpty() }?.toByteArray()?.size ?: 0

            // Attach those that can't be compressed (ie. everything but images)
            parts += attachments
                // filter in non-images only
                .filter { !it.isImage(context) }
                // filter in only items that exist (user may have deleted the file)
                .filter { it.uri.resourceExists(context) }
                .map {
                    remainingBytes -= it.getResourceBytes(context).size
                    val part = com.google.android.mms.MMSPart().apply {
                        MimeType = it.getType(context)
                        Name = it.getName(context)
                        Data = it.getResourceBytes(context)
                    }

                    // release the attachment hold on the image bytes so the GC can reclaim
                    it.releaseResourceBytes()

                    part
                }

            val imageBytesByAttachment = attachments
                // filter in images only
                .filter { it.isImage(context) }
                // filter in only items that exist (user may have deleted the file)
                .filter { it.uri.resourceExists(context) }
                .associateWith {
                    when (it.getType(context) == "image/gif") {
                        true -> ImageUtils.getScaledGif(context, it.uri, maxWidth, maxHeight)
                        false -> ImageUtils.getScaledImage(context, it.uri, maxWidth, maxHeight)
                    }
                }
                .toMutableMap()

            val imageByteCount = imageBytesByAttachment.values.sumOf { it.size }
            if (imageByteCount > remainingBytes) {
                imageBytesByAttachment.forEach { (attachment, originalBytes) ->
                    val uri = attachment.uri
                    val maxBytes = originalBytes.size / imageByteCount.toFloat() * remainingBytes

                    // Get the image dimensions
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(
                        context.contentResolver.openInputStream(uri),
                        null,
                        options
                    )
                    val width = options.outWidth
                    val height = options.outHeight
                    val aspectRatio = width.toFloat() / height.toFloat()

                    var attempts = 0
                    var scaledBytes = originalBytes

                    while (scaledBytes.size > maxBytes) {
                        // Estimate how much we need to scale the image down by. If it's still
                        // too big, we'll need to try smaller and smaller values
                        val scale = maxBytes / originalBytes.size * (0.9 - attempts * 0.2)
                        if (scale <= 0) {
                            Timber.w(
                                "Failed to compress ${
                                    originalBytes.size / 1024
                                }Kb to ${maxBytes.toInt() / 1024}Kb"
                            )
                            return@forEach
                        }

                        val newArea = scale * width * height
                        val newWidth = sqrt(newArea * aspectRatio).toInt()
                        val newHeight = (newWidth / aspectRatio).toInt()

                        attempts++
                        scaledBytes = when (attachment.getType(context) == "image/gif") {
                            true -> ImageUtils.getScaledGif(
                                context, attachment.uri, newWidth, newHeight
                            )

                            false -> ImageUtils.getScaledImage(
                                context, attachment.uri, newWidth, newHeight
                            )
                        }

                        Timber.d(
                            "Compression attempt $attempts: ${
                                scaledBytes.size / 1024
                            }/${maxBytes.toInt() / 1024}Kb ($width*$height -> $newWidth*${
                                newHeight
                            })"
                        )

                        // release the attachment hold on the image bytes so the GC can reclaim
                        attachment.releaseResourceBytes()
                    }

                    Timber.v(
                        "Compressed ${originalBytes.size / 1024}Kb to ${
                            scaledBytes.size / 1024
                        }Kb with a target size of ${
                            maxBytes.toInt() / 1024
                        }Kb in $attempts attempts"
                    )
                    imageBytesByAttachment[attachment] = scaledBytes
                }
            }

            imageBytesByAttachment.forEach { (attachment, bytes) ->
                parts += com.google.android.mms.MMSPart().apply {
                    MimeType =
                        if (attachment.getType(context) == "image/gif") ContentType.IMAGE_GIF
                        else ContentType.IMAGE_JPEG
                    Name = attachment.getName(context)
                    Data = bytes
                }
            }
        }

        Timber.v("create os provider message")

        // 3 stage sending process - stage 1, create records in os provider
        val group = (sendAsGroup && (toAddresses.size > 1))
        val messageUri = QkTransaction.createMessage(
            context, subId, body, prefs.signature.get(),
            toAddresses.map(phoneNumberUtils::normalizeNumber).toTypedArray(),
            parts, group, prefs.longAsMms.get(), prefs.unicode.get()
        )

        if (messageUri == Uri.EMPTY) {
            Timber.v("create os provider message failed")
            return listOf()
        }

        val message = syncProviderMessage(messageUri, group)
        if (message == null) {
            Timber.v("sync message failed for uri $messageUri")
            return listOf()
        }

        Timber.v("created message id ${message.id} from uri $messageUri")

        if (delayMs > 0) {  // if delaying
            val sendTime = (now() + delayMs)

            message.date = sendTime
            messageDao.upsert(message.toEntity())

            // create alarm that will trigger sending the message
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, sendTime, getIntentForDelayedSms(message.id)
                )

            Timber.v("set ${delayMs}ms delay for message id ${message.id}")

            return listOf(message)
        }

        // send now (message will be exploded, as required, and all sent)
        return sendMessage(message)
    }

    override fun sendMessage(message: Message): Collection<Message> {
        val retVal = mutableListOf<Message>()

        tryOrNull(true) {
            // explode message if needed
            val explodedMessages = QkTransaction.explodeMessage(
                context, message.getUri(), message.sendAsGroup
            ).filter { explodedMessageUri -> (explodedMessageUri != Uri.EMPTY) }

            // if multiple messages to send, create each and recurse to send
            if (explodedMessages.size > 1) {
                explodedMessages.forEach { explodedMessageUri ->
                    val childMessage = syncProviderMessage(explodedMessageUri, message.sendAsGroup)
                    if (childMessage != null) {
                        Timber.v("created message id ${childMessage.id} from uri $explodedMessageUri")
                        retVal.addAll(sendMessage(childMessage))
                    }
                    else
                        Timber.e("sync failed for uri $explodedMessageUri")
                }

                // mark original message as sent
                markSent(message.id)
            } else {
                markSending(message.id)

                // individual message to send, send it
                val sentIntent = Intent(context, MessageSentReceiver::class.java)
                    .putExtra(MessageSentReceiver.EXTRA_QUIK_MESSAGE_ID, message.id)

                val deliveryIntent =
                    if (prefs.delivery.get())
                        Intent(context, MessageDeliveredReceiver::class.java)
                            .putExtra(MessageDeliveredReceiver.EXTRA_QUIK_MESSAGE_ID, message.id)
                    else null

                // use values from os provider to resend the message, except subId
                if (!QkTransaction.sendMessage(context, message.getUri(), sentIntent, deliveryIntent))
                    Timber.e("message id ${message.id} not sent by smsmms")
            }

            retVal.add(message)
        }

        return retVal
    }

    override fun sendMessage(messageId: Long) =
        getMessage(messageId)
            ?.let { message -> sendMessage(message) }
            ?: listOf()

    override fun cancelDelayedSmsAlarm(messageId: Long) =
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .cancel(getIntentForDelayedSms(messageId))

    private fun getIntentForDelayedSms(messageId: Long) =
        PendingIntent.getBroadcast(
            context,
            messageId.toInt(),
            Intent(context, SendDelayedMessageReceiver::class.java)
                .putExtra(MESSAGE_ID_EXTRA, messageId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    override fun insertReceivedSms(subId: Int, address: String, body: String, sentTime: Long)
    : Message {
        val threadId = TelephonyCompat.getOrCreateThreadId(context, address)

        // insert the message to the native content provider
        val values = contentValuesOf(
            Sms.ADDRESS to address,
            Sms.BODY to body,
            Sms.DATE_SENT to sentTime,
            Sms.THREAD_ID to threadId
        )

        if (prefs.canUseSubId.get())
            values.put(Sms.SUBSCRIPTION_ID, subId)

        val providerContentId = context.contentResolver.insert(Sms.Inbox.CONTENT_URI, values)
            ?.let { insertedUri -> ContentUris.parseId(insertedUri) }
            ?: 0

        val message = Message().apply {
            id = messageIds.newId()

            this.address = address
            this.body = body
            this.dateSent = sentTime
            this.threadId = threadId
            this.subId = subId

            date = System.currentTimeMillis()

            contentId = providerContentId
            boxId = Sms.MESSAGE_TYPE_INBOX
            type = TYPE_SMS
            read = (activeConversationManager.getActiveConversation() == threadId)
        }
        messageDao.upsert(message.toEntity())

        val parsedReaction = reactions.parseEmojiReaction(body)
        if (parsedReaction != null) {
            val targetMessage = reactions.findTargetMessage(
                message.threadId,
                parsedReaction.originalMessage
            )
            reactions.saveEmojiReaction(
                message,
                parsedReaction,
                targetMessage,
            )
        }

        return message
    }

    override fun markAsSendingNow(messageId: Long) =
        messageDao.updateDate(messageId, System.currentTimeMillis())

    /**
     * Marks the message as sending, in case we need to retry sending it
     */
    override fun markSending(messageId: Long) =
        getMessage(messageId)?.let { message ->
            val boxId = when (message.isSms()) {
                true -> Sms.MESSAGE_TYPE_OUTBOX
                false -> Mms.MESSAGE_BOX_OUTBOX
            }
            messageDao.updateBoxId(messageId, boxId)
            context.contentResolver.update(
                message.getUri(),
                when (message.isSms()) {
                    true -> contentValuesOf(Sms.TYPE to Sms.MESSAGE_TYPE_OUTBOX)
                    false -> contentValuesOf(Mms.MESSAGE_BOX to Mms.MESSAGE_BOX_OUTBOX)
                },
                null,
                null
            )
            Unit
        } ?: Unit

    override fun markSent(messageId: Long) {
        Timber.v("mark message id $messageId as sent")

        getMessage(messageId)?.let { message ->
            if (message.isSms()) {
                messageDao.updateBoxId(messageId, Sms.MESSAGE_TYPE_SENT)
                context.contentResolver.update(
                    message.getUri(),
                    contentValuesOf(Sms.TYPE to Sms.MESSAGE_TYPE_SENT),
                    null,
                    null
                )
            } else {
                messageDao.updateBoxId(messageId, Mms.MESSAGE_BOX_SENT)
                context.contentResolver.update(
                    message.getUri(),
                    contentValuesOf(Mms.MESSAGE_BOX to Mms.MESSAGE_BOX_SENT),
                    null,
                    null
                )
            }
        }
    }

    override fun markFailed(messageId: Long, resultCode: Int) =
        getMessage(messageId)
            ?.let { message ->
                Timber.v("mark message id $messageId as failed. code $resultCode")
                    if (message.isSms()) {
                        if (message.boxId != Sms.MESSAGE_TYPE_FAILED) {
                            messageDao.updateBoxIdError(messageId, Sms.MESSAGE_TYPE_FAILED, resultCode)

                            // Update the message in the native ContentProvider
                            context.contentResolver.update(
                                message.getUri(),
                                contentValuesOf(
                                    Sms.TYPE to Sms.MESSAGE_TYPE_FAILED,
                                    Sms.ERROR_CODE to resultCode,
                                ),
                                null,
                                null
                            )
                            true
                        } else false
                    } else {  // mms
                        if (message.boxId != Mms.MESSAGE_BOX_FAILED) {
                            messageDao.updateBoxIdError(messageId, Mms.MESSAGE_BOX_FAILED, resultCode)

                            // Update the message in the native ContentProvider
                            context.contentResolver.update(
                                message.getUri(),
                                contentValuesOf(
                                    Mms.MESSAGE_BOX to Mms.MESSAGE_BOX_FAILED
                                ),
                                null,
                                null
                            )

                            // TODO this query isn't able to find any results
                            // Need to figure out why the message isn't appearing in the PendingMessages Uri,
                            // so that we can properly assign the error type
                            context.contentResolver.update(
                                Telephony.MmsSms.PendingMessages.CONTENT_URI,
                                contentValuesOf(
                                    Telephony.MmsSms.PendingMessages.ERROR_TYPE to Telephony.MmsSms.ERR_TYPE_GENERIC_PERMANENT
                                ),
                                "${Telephony.MmsSms.PendingMessages.MSG_ID} = ?",
                                arrayOf(message.id.toString())
                            )
                            true
                        } else false
                    }
            } ?: false

    override fun markDelivered(messageId: Long) =
        getMessage(messageId)?.let { message ->
            Timber.v("mark message id $messageId as delivered")
                    val dateSent = System.currentTimeMillis()
                    messageDao.updateDelivery(messageId, Sms.STATUS_COMPLETE, dateSent, true)

                    // Update the message in the native ContentProvider
                    context.contentResolver.update(
                        message.getUri(),
                        contentValuesOf(
                            Sms.STATUS to Sms.STATUS_COMPLETE,
                            Sms.DATE_SENT to dateSent,
                            Sms.READ to true,
                        ),
                        null,
                        null
                    )
            Unit
        } ?: Unit

    override fun markDeliveryFailed(messageId: Long, resultCode: Int) =
        getMessage(messageId)?.let { message ->
            Timber.v("mark message id $messageId as delivery failed result code $resultCode")
                    val dateSent = System.currentTimeMillis()
                    messageDao.updateDeliveryError(messageId, Sms.STATUS_FAILED, dateSent, true, resultCode)

                    // Update the message in the native ContentProvider
                    context.contentResolver.update(
                        message.getUri(),
                        contentValuesOf(
                            Sms.STATUS to Sms.STATUS_FAILED,
                            Sms.DATE_SENT to dateSent,
                            Sms.READ to true,
                            Sms.ERROR_CODE to resultCode,
                        ),
                        null,
                        null
                    )
            Unit
        } ?: Unit

    override fun deleteMessages(messageIds: Collection<Long>) =
        getMessages(messageIds).let { messages ->
            messages.forEach { message ->
                val uri = message.getUri()
                if (uri != Uri.EMPTY) {
                    context.contentResolver.delete(uri, null, null)
                }
            }
            if (messages.isNotEmpty()) {
                mmsPartDao.deleteForMessageContentIds(messages.map { message -> message.contentId })
                messageDao.delete(messages.map { message -> message.id })
            }
        }

    override fun getOldMessageCounts(maxAgeDays: Int) =
        messageDao.messagesOlderThan(now() - TimeUnit.DAYS.toMillis(maxAgeDays.toLong()))
            .groupingBy { message -> message.threadId }
            .eachCount()

    override fun deleteOldMessages(maxAgeDays: Int) =
        mapMessages(messageDao.messagesOlderThan(now() - TimeUnit.DAYS.toMillis(maxAgeDays.toLong()))).let { messages ->
            messages.forEach { message ->
                context.contentResolver.delete(message.getUri(), null, null)
            }
            if (messages.isNotEmpty()) {
                mmsPartDao.deleteForMessageContentIds(messages.map { message -> message.contentId })
                messageDao.delete(messages.map { message -> message.id })
            }
        }

    override fun deduplicateMessages(): Flowable<MessageRepository.DeduplicationResult> =
        Flowable.fromCallable {
            val duplicateIds = findDuplicateMessages()
            if (duplicateIds.isEmpty()) {
                MessageRepository.DeduplicationResult.NoDuplicates
            } else {
                deduplicationProgress.onNext(MessageRepository.DeduplicationProgress.Running(0, 0, true))
                deleteMessages(duplicateIds)
                MessageRepository.DeduplicationResult.Success
            }
        }
        .onErrorReturn { MessageRepository.DeduplicationResult.Failure(it) }
        .doFinally {
            deduplicationProgress.onNext(MessageRepository.DeduplicationProgress.Idle)
        }

    private fun findDuplicateMessages(): List<Long> {
        val seenSignatures = HashSet<String>()
        val duplicateIds = ArrayList<Long>()

        val allMessages = mapMessages(messageDao.messagesOlderThan(Long.MAX_VALUE))
            .sortedBy { message -> message.id }

        val max = allMessages.size
        var progress = 0

        allMessages.forEach { message ->
            ++progress
            tryOrNull {
                if (progress % 100 == 0 || progress == max) {
                    deduplicationProgress.onNext(
                        MessageRepository.DeduplicationProgress.Running(max, progress, false)
                    )
                }
                val signature = messageHash(message)
                if (!seenSignatures.add(signature)) {
                    duplicateIds.add(message.id)
                }
            }
        }
        return duplicateIds
    }

    private fun messageHash(message: Message): String {
        val signatureString= buildString {
            append(message.address).append('|')
            append(message.dateSent).append('|')
            append(message.boxId).append('|')
            append(message.body).append('|')
            append(message.attachmentTypeString)
        }
        return sha256(signatureString)
    }
}
