package dev.octoshrimpy.quik.database

import android.content.Context
import androidx.room.Room
import io.reactivex.Flowable
import io.reactivex.processors.PublishProcessor
import io.realm.RealmIdentity
import io.realm.RealmModel
import io.realm.RealmReflection
import io.realm.RealmSerialization
import io.realm.RealmStore
import java.util.concurrent.atomic.AtomicLong

class RoomRealmStore(context: Context) : RealmStore {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        MessagesDatabase::class.java,
        "messages.db"
    )
        .fallbackToDestructiveMigration(false)
        .allowMainThreadQueries()
        .build()

    private val dao = database.realmObjects()
    private val changes = PublishProcessor.create<Unit>()
    private val ids = AtomicLong(System.currentTimeMillis())

    override fun <T : RealmModel> all(clazz: Class<T>): List<T> {
        return dao.all(clazz.name).map { record ->
            RealmSerialization.fromBytes<T>(record.payload)
        }
    }

    override fun upsert(model: RealmModel): RealmModel {
        val identity = RealmReflection.identity(model) ?: RealmIdentity(model.javaClass.name, nextObjectKey())
        dao.upsert(record(identity, model))
        changes.onNext(Unit)
        return RealmSerialization.copy(model)
    }

    override fun upsertAll(models: Collection<RealmModel>): List<RealmModel> {
        val records = models.map { model ->
            val identity = RealmReflection.identity(model) ?: RealmIdentity(model.javaClass.name, nextObjectKey())
            record(identity, model)
        }
        if (records.isNotEmpty()) {
            dao.upsert(records)
            changes.onNext(Unit)
        }
        return models.map { RealmSerialization.copy(it) }
    }

    override fun insert(model: RealmModel): RealmModel {
        val identity = RealmReflection.identity(model)
            ?.takeIf { RealmReflection.primaryKeyValue(model) != null }
            ?: RealmIdentity(model.javaClass.name, nextObjectKey())
        dao.upsert(record(identity, model))
        changes.onNext(Unit)
        return RealmSerialization.copy(model)
    }

    override fun delete(clazz: Class<out RealmModel>) {
        dao.deleteType(clazz.name)
        changes.onNext(Unit)
    }

    override fun delete(typeName: String) {
        val fullName = if (typeName.contains(".")) typeName else "dev.octoshrimpy.quik.model.$typeName"
        dao.deleteType(fullName)
        changes.onNext(Unit)
    }

    override fun deleteIdentity(identity: RealmIdentity) {
        dao.delete(identity.type, identity.key)
        changes.onNext(Unit)
    }

    override fun changes(): Flowable<Unit> {
        return changes.startWith(Unit).onBackpressureLatest()
    }

    private fun record(identity: RealmIdentity, model: RealmModel): RealmObjectRecord {
        return RealmObjectRecord(
            type = identity.type,
            objectKey = identity.key,
            payload = RealmSerialization.toBytes(model)
        )
    }

    private fun nextObjectKey(): String = ids.incrementAndGet().toString()
}
