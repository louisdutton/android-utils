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

import dev.octoshrimpy.quik.database.BlockedNumberEntity
import dev.octoshrimpy.quik.database.BlockingDao
import dev.octoshrimpy.quik.database.toModel
import dev.octoshrimpy.quik.model.BlockedNumber
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import javax.inject.Inject

class BlockingRepositoryImpl @Inject constructor(
    private val phoneNumberUtils: PhoneNumberUtils,
    private val blockingDao: BlockingDao
) : BlockingRepository {

    override fun blockNumber(vararg addresses: String) {
        val blockedNumbers = blockingDao.blockedNumbers()
        val newAddresses = addresses.filter { address ->
            blockedNumbers.none { number -> phoneNumberUtils.compare(number.address, address) }
        }
        val maxId = blockingDao.maxId() ?: -1
        blockingDao.upsert(newAddresses.mapIndexed { index, address ->
            BlockedNumberEntity(maxId + 1 + index, address)
        })
    }

    override fun getBlockedNumbers(): List<BlockedNumber> =
        blockingDao.blockedNumbers().map { number -> number.toModel() }

    override fun getBlockedNumber(id: Long): BlockedNumber? =
        blockingDao.blockedNumber(id)?.toModel()

    override fun isBlocked(address: String): Boolean =
        blockingDao.blockedNumbers().any { number -> phoneNumberUtils.compare(number.address, address) }

    override fun unblockNumber(id: Long) = blockingDao.delete(id)

    override fun unblockNumbers(vararg addresses: String) {
        val ids = blockingDao.blockedNumbers()
            .filter { number ->
                addresses.any { address -> phoneNumberUtils.compare(number.address, address) }
            }
            .map { number -> number.id }
        blockingDao.delete(ids)
    }

}
