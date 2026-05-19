package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.database.ScheduledMessageDao
import dev.octoshrimpy.quik.database.toEntity
import dev.octoshrimpy.quik.database.toModel
import dev.octoshrimpy.quik.model.ScheduledMessage
import javax.inject.Inject

class ScheduledMessageRepositoryImpl @Inject constructor(
    private val scheduledMessageDao: ScheduledMessageDao
) : ScheduledMessageRepository {

    override fun saveScheduledMessage(
        date: Long,
        subId: Int,
        recipients: List<String>,
        sendAsGroup: Boolean,
        body: String,
        attachments: List<String>,
        conversationId: Long
    ): ScheduledMessage {
        val id = (scheduledMessageDao.maxId() ?: -1) + 1
        val message = ScheduledMessage(
            id,
            date,
            subId,
            recipients.toMutableList(),
            sendAsGroup,
            body,
            attachments.toMutableList(),
            conversationId
        )
        scheduledMessageDao.upsert(message.toEntity())
        return message
    }

    override fun updateScheduledMessage(scheduledMessage: ScheduledMessage) {
        scheduledMessageDao.upsert(scheduledMessage.toEntity())
    }

    override fun getScheduledMessages(): List<ScheduledMessage> =
        scheduledMessageDao.scheduledMessages().map { message -> message.toModel() }

    override fun getScheduledMessage(id: Long): ScheduledMessage? =
        scheduledMessageDao.scheduledMessage(id)?.toModel()

    override fun getScheduledMessagesForConversation(conversationId: Long): List<ScheduledMessage> =
        scheduledMessageDao.scheduledMessagesForConversation(conversationId).map { message -> message.toModel() }

    override fun deleteScheduledMessage(id: Long) {
        scheduledMessageDao.delete(id)
    }

    override fun deleteScheduledMessages(ids: List<Long>) {
        scheduledMessageDao.delete(ids)
    }

    override fun getAllScheduledMessageIdsSnapshot(): List<Long> {
        return scheduledMessageDao.ids()
    }
}
