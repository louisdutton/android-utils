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
package dev.octoshrimpy.quik.feature.blocking.numbers

import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDispose
import dev.octoshrimpy.quik.common.base.QkPresenter
import dev.octoshrimpy.quik.interactor.MarkUnblocked
import dev.octoshrimpy.quik.repository.BlockingRepository
import dev.octoshrimpy.quik.repository.ConversationRepository
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject

class BlockedNumbersPresenter @Inject constructor(
    private val blockingRepo: BlockingRepository,
    private val conversationRepo: ConversationRepository,
    private val markUnblocked: MarkUnblocked
) : QkPresenter<BlockedNumbersView, BlockedNumbersState>(BlockedNumbersState()) {

    override fun bindIntents(view: BlockedNumbersView) {
        super.bindIntents(view)

        loadBlockedNumbers(view)

        view.unblockAddress()
            .observeOn(Schedulers.io())
            .map { id ->
                blockingRepo.getBlockedNumber(id)?.address
                    ?.let { address -> conversationRepo.getConversation(listOf(address)) }
                    ?.let { conversation -> markUnblocked.execute(listOf(conversation.id)) }
                blockingRepo.unblockNumber(id)
                blockingRepo.getBlockedNumbers()
            }
            .subscribeOn(Schedulers.io())
            .autoDispose(view.scope())
            .subscribe({ numbers -> newState { copy(numbers = numbers) } }, {})

        view.addAddress()
            .autoDispose(view.scope())
            .subscribe { view.showAddDialog() }

        view.saveAddress()
            .observeOn(Schedulers.io())
            .map { address ->
                blockingRepo.blockNumber(address)
                blockingRepo.getBlockedNumbers()
            }
            .subscribeOn(Schedulers.io())
            .autoDispose(view.scope())
            .subscribe({ numbers -> newState { copy(numbers = numbers) } }, {})
    }

    private fun loadBlockedNumbers(view: BlockedNumbersView) {
        Observable.fromCallable { blockingRepo.getBlockedNumbers() }
            .subscribeOn(Schedulers.io())
            .autoDispose(view.scope())
            .subscribe({ numbers -> newState { copy(numbers = numbers) } }, {})
    }

}
