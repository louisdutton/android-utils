/*
 * Copyright (C) 2019 Moez Bhatti <moez.bhatti@gmail.com>
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
package dev.octoshrimpy.quik.feature.blocking.messages

import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDispose
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.common.base.QkPresenter
import dev.octoshrimpy.quik.interactor.DeleteConversations
import dev.octoshrimpy.quik.repository.ConversationRepository
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject

class BlockedMessagesPresenter @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val deleteConversations: DeleteConversations,
    private val navigator: Navigator
) : QkPresenter<BlockedMessagesView, BlockedMessagesState>(BlockedMessagesState()) {

    override fun bindIntents(view: BlockedMessagesView) {
        super.bindIntents(view)

        loadBlockedConversations(view)

        view.menuReadyIntent
                .autoDispose(view.scope())
                .subscribe { newState { copy() } }

        view.optionsItemIntent
                .withLatestFrom(view.selectionChanges) { itemId, conversations ->
                    when (itemId) {
                        R.id.block -> {
                            view.showBlockingDialog(conversations, false)
                            view.clearSelection()
                            removeConversations(conversations)
                        }
                        R.id.delete -> {
                            view.showDeleteDialog(conversations)
                        }
                    }

                }
                .autoDispose(view.scope())
                .subscribe()

        view.confirmDeleteIntent
                .autoDispose(view.scope())
                .subscribe { conversations ->
                    deleteConversations.execute(conversations)
                    view.clearSelection()
                    removeConversations(conversations)
                }

        view.conversationClicks
                .autoDispose(view.scope())
                .subscribe { threadId -> navigator.showConversation(threadId) }

        view.selectionChanges
                .autoDispose(view.scope())
                .subscribe { selection -> newState { copy(selected = selection.size) } }

        view.backClicked
                .withLatestFrom(state) { _, state ->
                    when (state.selected) {
                        0 -> view.goBack()
                        else -> view.clearSelection()
                    }
                }
                .autoDispose(view.scope())
                .subscribe()
    }

    private fun loadBlockedConversations(view: BlockedMessagesView) {
        Observable.fromCallable { conversationRepo.getBlockedConversations() }
            .subscribeOn(Schedulers.io())
            .autoDispose(view.scope())
            .subscribe({ conversations -> newState { copy(data = conversations) } }, {})
    }

    private fun removeConversations(conversations: List<Long>) {
        newState {
            copy(
                data = data?.filterNot { conversation -> conversation.id in conversations },
                selected = 0
            )
        }
    }

}
