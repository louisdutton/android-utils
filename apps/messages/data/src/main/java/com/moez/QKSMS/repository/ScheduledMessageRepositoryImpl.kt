package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.database.ScheduledMessageDao
import dev.octoshrimpy.quik.database.toEntity
import dev.octoshrimpy.quik.database.toModel
import dev.octoshrimpy.quik.model.ScheduledMessage
import io.reactivex.Completable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import javax.inject.Inject

class ScheduledMessageRepositoryImpl @Inject constructor(
    private val scheduledMessageDao: ScheduledMessageDao
) : ScheduledMessageRepository {

    private val disposables = CompositeDisposable()

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
        val subscription = Completable.fromAction {
            scheduledMessageDao.delete(id)
        }.subscribeOn(Schedulers.io()) // Run on a background thread and switch to main if needed
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                Timber.v("Successfully deleted scheduled messages.")
            }, {
                Timber.e("Deleting scheduled messages failed.")
            })

        disposables.add(subscription)
    }

    override fun deleteScheduledMessages(ids: List<Long>) {
        val subscription = Completable.fromAction {
            scheduledMessageDao.delete(ids)
        }.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                Timber.v("Successfully deleted scheduled messages.")
            }, {
                Timber.e("Deleting scheduled messages failed.")
            })

        disposables.add(subscription)
    }

    override fun getAllScheduledMessageIdsSnapshot(): List<Long> {
        return scheduledMessageDao.ids()
    }
}
