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
package dev.octoshrimpy.quik.blocking

import io.reactivex.Completable
import io.reactivex.Single

interface BlockingClient {

    companion object {
        const val BUILT_IN_CLIENT_ID = 0
    }

    sealed class Action {
        class Block(val reason: String? = null) : Action()
        object Unblock : Action()

        override fun toString(): String {
            return when (this) {
                is Block -> "Block"
                is Unblock -> "Unblock"
            }
        }
    }

    /**
     * Returns the recommendation action to perform given a message from the [address]
     */
    fun shouldBlock(address: String): Single<Action>

    /**
     * Blocks the numbers.
     */
    fun block(addresses: List<String>): Completable

    /**
     * Unblocks the numbers.
     */
    fun unblock(addresses: List<String>): Completable

}
