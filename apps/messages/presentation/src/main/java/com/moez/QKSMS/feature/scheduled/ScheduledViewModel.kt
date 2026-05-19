package dev.octoshrimpy.quik.feature.scheduled

import android.content.Context
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDispose
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.common.base.QkViewModel
import dev.octoshrimpy.quik.common.util.ClipboardUtils
import dev.octoshrimpy.quik.extensions.mapNotNull
import dev.octoshrimpy.quik.interactor.DeleteScheduledMessages
import dev.octoshrimpy.quik.interactor.SendScheduledMessage
import dev.octoshrimpy.quik.manager.BillingManager
import dev.octoshrimpy.quik.repository.ScheduledMessageRepository
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Named

class ScheduledViewModel @Inject constructor(
    @Named("conversationId") private val conversationId: Long?,
    billingManager: BillingManager,
    private val context: Context,
    private val navigator: Navigator,
    private val scheduledMessageRepo: ScheduledMessageRepository,
    private val sendScheduledMessageInteractor: SendScheduledMessage,
    private val deleteScheduledMessagesInteractor: DeleteScheduledMessages,
) : QkViewModel<ScheduledView, ScheduledState>(ScheduledState(
    scheduledMessages = null,
    conversationId = conversationId
)) {

    init {
        loadMessages(conversationId)
        disposables += billingManager.upgradeStatus
            .subscribe { upgraded -> newState { copy(upgraded = upgraded) } }
    }

    override fun bindView(view: ScheduledView) {
        super.bindView(view)

        // update the state when the message selected count changes
        view.messagesSelectedIntent
            .map { selection -> selection.size }
            .autoDispose(view.scope())
            .subscribe { newState { copy(selectedMessages = it) } }

        // toggle select all / select none
        view.optionsItemIntent
            .filter { it == R.id.select_all }
            .autoDispose(view.scope())
            .subscribe { view.toggleSelectAll() }

        // show the delete message dialog if one or more messages selected
        view.optionsItemIntent
            .filter { it == R.id.delete }
            .withLatestFrom(view.messagesSelectedIntent) { _, selectedMessages ->
                selectedMessages }
            .observeOn(Schedulers.io())
            .map { selectedMessages ->
                selectedMessages
                    .mapNotNull(scheduledMessageRepo::getScheduledMessage)
                    .map { it.id }
            }
            .observeOn(AndroidSchedulers.mainThread())
            .autoDispose(view.scope())
            .subscribe(view::showDeleteDialog, Timber::e)


        // copy the selected message text to the clipboard
        view.optionsItemIntent
            .filter { it == R.id.copy }
            .withLatestFrom(view.messagesSelectedIntent) { _, selectedMessageIds ->
                selectedMessageIds
            }
            .observeOn(Schedulers.io())
            .map { selectedMessageIds ->
                selectedMessageIds
                    .mapNotNull(scheduledMessageRepo::getScheduledMessage)
                    .sortedBy { it.date }
            }
            .observeOn(AndroidSchedulers.mainThread())
            .autoDispose(view.scope())
            .subscribe({ scheduledMessages ->
                ClipboardUtils.copy(
                    context,
                    when (scheduledMessages.size) {
                        1 -> scheduledMessages.first().body
                        else -> scheduledMessages.fold(StringBuilder()) { acc, message ->
                            if (acc.isNotEmpty() && message.body.isNotEmpty()) {
                                acc.append("\n\n")
                            }
                            acc.append(message.body)
                        }
                    }.toString()
                )
            }, Timber::e)

        // send the messages now menu item selected
        view.optionsItemIntent
            .filter { it == R.id.send_now }
            .withLatestFrom(view.messagesSelectedIntent) { _, selectedMessages ->
                view.showSendNowDialog(selectedMessages)
            }
            .autoDispose(view.scope())
            .subscribe()

        // edit message menu item selected
        view.optionsItemIntent
            .filter { it == R.id.edit_message }
            .withLatestFrom(view.messagesSelectedIntent) { _, selectedMessage ->
                view.showEditMessageDialog(selectedMessage.first())
            }
            .autoDispose(view.scope())
            .subscribe()

        // delete message(s) (fired after the confirmation dialog has been shown)
        view.deleteScheduledMessages
            .autoDispose(view.scope())
            .subscribe { selectedMessagesIds ->
                deleteScheduledMessagesInteractor.execute(selectedMessagesIds.toList()) {
                    loadMessages(conversationId)
                }
                view.clearSelection()
            }

        // send message(s) now (fired after the confirmation dialog has been shown)
        view.sendScheduledMessages
            .autoDispose(view.scope())
            .subscribe { selectedMessagesIds ->
                selectedMessagesIds.forEach { selectedMessagesId ->
                    sendScheduledMessageInteractor.execute(selectedMessagesId) {
                        loadMessages(conversationId)
                    }
                }
                view.clearSelection()
            }


        // edit message (fired after the confirmation dialog has been shown)
        view.editScheduledMessage
            .observeOn(Schedulers.io())
            .mapNotNull(scheduledMessageRepo::getScheduledMessage)
            .observeOn(AndroidSchedulers.mainThread())
            .doOnNext(navigator::showCompose)
            .observeOn(Schedulers.io())
            .doOnNext { scheduledMessage -> scheduledMessageRepo.deleteScheduledMessage(scheduledMessage.id) }
            .observeOn(AndroidSchedulers.mainThread())
            .autoDispose(view.scope())
            .subscribe({
                view.clearSelection()
                loadMessages(conversationId)
            }, Timber::e)

        // navigate back or unselect
        view.optionsItemIntent
            .filter { it == android.R.id.home }
            .map { }
            .mergeWith(view.backPressedIntent)
            .withLatestFrom(state) { _, state -> state }
            .autoDispose(view.scope())
            .subscribe {
                when {
                    (it.selectedMessages > 0) -> view.clearSelection()
                    else -> view.finishActivity()
                }
            }

        view.composeIntent
            .autoDispose(view.scope())
            .subscribe {
                navigator.showCompose(mode = "scheduling")
                view.clearSelection()
            }

        view.upgradeIntent
            .autoDispose(view.scope())
            .subscribe { navigator.showQksmsPlusActivity("schedule_fab") }
    }

    private fun loadMessages(conversationId: Long?) {
        disposables += Single.fromCallable {
            if (conversationId != null) {
                scheduledMessageRepo.getScheduledMessagesForConversation(conversationId)
            } else {
                scheduledMessageRepo.getScheduledMessages()
            }
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ results ->
                newState { copy(scheduledMessages = results) }
            }, { error ->
                Timber.e(error, "Error loading scheduled messages")
                newState { copy(scheduledMessages = emptyList()) }
            })
    }
}
