package dev.octoshrimpy.quik.blocking

import io.reactivex.Completable
import io.reactivex.Single
import javax.inject.Inject

class CallControlBlockingClient @Inject constructor() : BlockingClient {
    override fun isAvailable(): Boolean = false

    override fun getClientCapability(): BlockingClient.Capability = BlockingClient.Capability.CANT_BLOCK

    override fun shouldBlock(address: String): Single<BlockingClient.Action> =
        Single.just(BlockingClient.Action.DoNothing)

    override fun isBlacklisted(address: String): Single<BlockingClient.Action> =
        Single.just(BlockingClient.Action.DoNothing)

    override fun block(addresses: List<String>): Completable = Completable.complete()

    override fun unblock(addresses: List<String>): Completable = Completable.complete()

    override fun openSettings() = Unit
}
