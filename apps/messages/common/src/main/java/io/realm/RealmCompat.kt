package io.realm

import androidx.recyclerview.widget.RecyclerView
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.realm.annotations.PrimaryKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.lang.reflect.Field
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

interface RealmModel : Serializable

open class RealmObject : RealmModel {
    @Transient
    internal var realmIdentity: RealmIdentity? = null

    val isLoaded: Boolean get() = true
    val isValid: Boolean get() = realmIdentity?.deleted != true

    @Suppress("UNCHECKED_CAST")
    fun <T : RealmObject> asFlowable(): Flowable<T> = Flowable.just(this as T)

    fun deleteFromRealm() {
        Realm.getDefaultInstance().deleteObject(this)
    }
}

data class RealmIdentity(
    val type: String,
    val key: String,
    var deleted: Boolean = false
) : Serializable

fun interface RealmChangeListener<T> {
    fun onChange(value: T)
}

enum class Sort {
    ASCENDING,
    DESCENDING
}

enum class Case {
    SENSITIVE,
    INSENSITIVE
}

interface OrderedRealmCollection<T> : MutableList<T> {
    val isLoaded: Boolean
    val isValid: Boolean
}

open class RealmList<T> : ArrayList<T>, OrderedRealmCollection<T> {
    constructor() : super()
    constructor(vararg values: T) : super(values.toList())
    constructor(values: Collection<T>) : super(values)

    @Transient
    private val listeners = CopyOnWriteArrayList<(OrderedRealmCollection<T>) -> Unit>()

    override val isLoaded: Boolean get() = true
    override val isValid: Boolean get() = true

    fun addChangeListener(listener: (OrderedRealmCollection<T>) -> Unit) {
        listeners += listener
        listener(this)
    }

    fun removeChangeListener(listener: (OrderedRealmCollection<T>) -> Unit) {
        listeners -= listener
    }

    fun asFlowable(): Flowable<RealmList<T>> = Flowable.just(this)
}

class RealmResults<T : RealmModel> internal constructor(
    values: Collection<T> = emptyList(),
    private val refreshBlock: (() -> List<T>)? = null
) : ArrayList<T>(values), OrderedRealmCollection<T> {
    @Transient
    private val collectionListeners = CopyOnWriteArrayList<(OrderedRealmCollection<T>) -> Unit>()

    @Transient
    private val realmListeners = CopyOnWriteArrayList<RealmChangeListener<RealmResults<T>>>()

    override val isLoaded: Boolean get() = true
    override val isValid: Boolean get() = true

    fun createSnapshot(): RealmResults<T> = RealmResults(map { RealmSerialization.copy(it) })

    fun addChangeListener(listener: (OrderedRealmCollection<T>) -> Unit) {
        collectionListeners += listener
        listener(this)
    }

    fun addChangeListener(listener: RealmChangeListener<RealmResults<T>>) {
        realmListeners += listener
        listener.onChange(this)
    }

    fun removeChangeListener(listener: (OrderedRealmCollection<T>) -> Unit) {
        collectionListeners -= listener
    }

    fun removeChangeListener(listener: RealmChangeListener<RealmResults<T>>) {
        realmListeners -= listener
    }

    fun asFlowable(): Flowable<RealmResults<T>> {
        return Realm.changes()
            .startWith(Unit)
            .map {
                refresh()
                this
            }
    }

    fun deleteAllFromRealm() {
        Realm.getDefaultInstance().deleteAll(this)
        clear()
        notifyChanged()
    }

    internal fun refresh() {
        val refreshed = refreshBlock?.invoke() ?: return
        clear()
        addAll(refreshed)
        notifyChanged()
    }

    private fun notifyChanged() {
        collectionListeners.forEach { it(this) }
        realmListeners.forEach { it.onChange(this) }
    }
}

abstract class RealmRecyclerViewAdapter<T : RealmModel, VH : RecyclerView.ViewHolder>(
    data: OrderedRealmCollection<T>?,
    @Suppress("UNUSED_PARAMETER") autoUpdate: Boolean
) : RecyclerView.Adapter<VH>() {
    private var currentData: OrderedRealmCollection<T>? = data

    open fun getData(): OrderedRealmCollection<T>? = currentData

    open fun updateData(data: OrderedRealmCollection<T>?) {
        currentData = data
        notifyDataSetChanged()
    }

    open fun getItem(index: Int): T? = currentData?.getOrNull(index)

    override fun getItemCount(): Int = currentData?.size ?: 0
}

interface RealmStore {
    fun <T : RealmModel> all(clazz: Class<T>): List<T>
    fun upsert(model: RealmModel): RealmModel
    fun upsertAll(models: Collection<RealmModel>): List<RealmModel>
    fun insert(model: RealmModel): RealmModel
    fun delete(clazz: Class<out RealmModel>)
    fun delete(typeName: String)
    fun deleteIdentity(identity: RealmIdentity)
    fun changes(): Flowable<Unit>
}

class Realm private constructor(private val store: RealmStore) : Closeable {
    private val tracked = linkedMapOf<RealmIdentity, RealmModel>()

    companion object {
        @Volatile
        private var defaultStore: RealmStore = InMemoryRealmStore()

        fun init(@Suppress("UNUSED_PARAMETER") context: android.content.Context) = Unit

        fun setStore(store: RealmStore) {
            defaultStore = store
        }

        fun getDefaultInstance(): Realm = Realm(defaultStore)

        fun changes(): Flowable<Unit> = defaultStore.changes()
    }

    fun <T : RealmModel> where(clazz: Class<T>): RealmQuery<T> = RealmQuery(this, clazz)

    fun where(className: String): RealmQuery<RealmModel> = RealmQuery(this, classForName(className))

    fun <T : RealmModel> insertOrUpdate(model: T): T {
        @Suppress("UNCHECKED_CAST")
        return track(store.upsert(model) as T)
    }

    fun <T : RealmModel> insertOrUpdate(models: Collection<T>) {
        store.upsertAll(models).forEach { track(it) }
    }

    fun <T : RealmModel> copyToRealmOrUpdate(model: T): T = insertOrUpdate(model)

    fun <T : RealmModel> copyToRealmOrUpdate(models: Collection<T>): RealmList<T> {
        @Suppress("UNCHECKED_CAST")
        return RealmList(store.upsertAll(models).map { track(it) as T })
    }

    fun <T : RealmModel> insert(model: T): T {
        @Suppress("UNCHECKED_CAST")
        return track(store.insert(model) as T)
    }

    fun <T : RealmModel> insert(models: Collection<T>) {
        store.upsertAll(models).forEach { track(it) }
    }

    fun <T : RealmModel> copyFromRealm(model: T): T = RealmSerialization.copy(model)

    fun <T : RealmModel> copyFromRealm(models: Collection<T>): List<T> = models.map { copyFromRealm(it) }

    fun executeTransaction(block: (Realm) -> Unit) {
        block(this)
        persistTracked()
    }

    fun executeTransactionAsync(
        block: (Realm) -> Unit,
        onSuccess: (() -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null
    ) {
        thread(name = "MessagesSqliteTransaction") {
            try {
                executeTransaction(block)
                onSuccess?.invoke()
            } catch (throwable: Throwable) {
                onError?.invoke(throwable)
            }
        }
    }

    fun executeTransactionAsync(block: (Realm) -> Unit) {
        executeTransactionAsync(block, null, null)
    }

    fun delete(clazz: Class<out RealmModel>) {
        store.delete(clazz)
    }

    fun delete(className: String) {
        store.delete(className)
    }

    fun refresh() = Unit

    override fun close() = persistTracked()

    internal fun <T : RealmModel> loaded(clazz: Class<T>): List<T> = store.all(clazz).map(::track)

    internal fun <T : RealmModel> track(model: T): T {
        val identity = RealmReflection.identity(model)
        if (identity != null) {
            (model as? RealmObject)?.realmIdentity = identity
            tracked[identity] = model
        }
        return model
    }

    internal fun deleteAll(models: Collection<RealmModel>) {
        models.forEach(::deleteObject)
    }

    internal fun deleteObject(model: RealmModel) {
        val identity = (model as? RealmObject)?.realmIdentity ?: RealmReflection.identity(model) ?: return
        identity.deleted = true
        tracked.remove(identity)
        store.deleteIdentity(identity)
    }

    private fun persistTracked() {
        tracked.values
            .filter { (it as? RealmObject)?.realmIdentity?.deleted != true }
            .forEach { store.upsert(it) }
    }

    private fun classForName(className: String): Class<RealmModel> {
        val fullName = if (className.contains(".")) className else "dev.octoshrimpy.quik.model.$className"
        @Suppress("UNCHECKED_CAST")
        return Class.forName(fullName) as Class<RealmModel>
    }
}

class RealmQuery<T : RealmModel> internal constructor(
    private val realm: Realm,
    private val clazz: Class<T>
) {
    private var predicate: ((T) -> Boolean)? = null
    private var nextOperator = Operator.AND
    private val groups = mutableListOf<Pair<((T) -> Boolean)?, Operator>>()
    private val sorts = mutableListOf<Pair<String, Sort>>()
    private var limit: Int? = null

    fun equalTo(fieldName: String, value: Long): RealmQuery<T> = add { values(it, fieldName).any { field -> number(field) == value.toDouble() } }
    fun equalTo(fieldName: String, value: Int): RealmQuery<T> = add { values(it, fieldName).any { field -> number(field) == value.toDouble() } }
    fun equalTo(fieldName: String, value: Boolean): RealmQuery<T> = add { values(it, fieldName).any { field -> field == value } }
    fun equalTo(fieldName: String, value: String?): RealmQuery<T> = add { values(it, fieldName).any { field -> field?.toString() == value } }

    fun notEqualTo(fieldName: String, value: Long): RealmQuery<T> = add { values(it, fieldName).none { field -> number(field) == value.toDouble() } }
    fun notEqualTo(fieldName: String, value: Int): RealmQuery<T> = add { values(it, fieldName).none { field -> number(field) == value.toDouble() } }
    fun notEqualTo(fieldName: String, value: Boolean): RealmQuery<T> = add { values(it, fieldName).none { field -> field == value } }

    fun contains(fieldName: String, value: String): RealmQuery<T> = contains(fieldName, value, Case.SENSITIVE)

    fun contains(fieldName: String, value: String, casing: Case): RealmQuery<T> = add {
        values(it, fieldName).any { field ->
            val text = field?.toString() ?: return@any false
            when (casing) {
                Case.SENSITIVE -> text.contains(value)
                Case.INSENSITIVE -> text.lowercase(Locale.getDefault()).contains(value.lowercase(Locale.getDefault()))
            }
        }
    }

    fun isNotNull(fieldName: String): RealmQuery<T> = add { values(it, fieldName).any { field -> field != null } }
    fun isNull(fieldName: String): RealmQuery<T> = add { values(it, fieldName).all { field -> field == null } }
    fun isNotEmpty(fieldName: String): RealmQuery<T> = add { values(it, fieldName).any(::notEmpty) }
    fun isEmpty(fieldName: String): RealmQuery<T> = add { values(it, fieldName).all { field -> !notEmpty(field) } }

    fun greaterThan(fieldName: String, value: Long): RealmQuery<T> = add { values(it, fieldName).any { field -> number(field) > value } }
    fun greaterThan(fieldName: String, value: Int): RealmQuery<T> = add { values(it, fieldName).any { field -> number(field) > value } }
    fun lessThan(fieldName: String, value: Long): RealmQuery<T> = add { values(it, fieldName).any { field -> number(field) < value } }
    fun lessThan(fieldName: String, value: Int): RealmQuery<T> = add { values(it, fieldName).any { field -> number(field) < value } }

    fun <N : Number> `in`(fieldName: String, values: Array<N>): RealmQuery<T> = add { model ->
        val normalized = values.map { it.toDouble() }
        values(model, fieldName).any { field -> (field as? Number)?.toDouble() in normalized }
    }

    fun beginGroup(): RealmQuery<T> {
        groups += predicate to nextOperator
        predicate = null
        nextOperator = Operator.AND
        return this
    }

    fun endGroup(): RealmQuery<T> {
        val groupPredicate = predicate ?: { _: T -> true }
        val (parentPredicate, parentOperator) = groups.removeLastOrNull() ?: (null to Operator.AND)
        predicate = combine(parentPredicate, groupPredicate, parentOperator)
        nextOperator = Operator.AND
        return this
    }

    fun or(): RealmQuery<T> {
        nextOperator = Operator.OR
        return this
    }

    fun and(): RealmQuery<T> {
        nextOperator = Operator.AND
        return this
    }

    fun sort(fieldName: String): RealmQuery<T> = sort(fieldName, Sort.ASCENDING)

    fun sort(fieldName: String, direction: Sort): RealmQuery<T> {
        sorts += fieldName to direction
        return this
    }

    fun sort(fieldName: String, direction: Sort, fieldName2: String, direction2: Sort): RealmQuery<T> {
        sorts += fieldName to direction
        sorts += fieldName2 to direction2
        return this
    }

    fun sort(fieldNames: Array<String>, directions: Array<Sort>): RealmQuery<T> {
        fieldNames.zip(directions).forEach { sorts += it }
        return this
    }

    fun max(fieldName: String): Number? = evaluated().mapNotNull { values(it, fieldName).firstOrNull() as? Number }.maxByOrNull { it.toDouble() }

    fun count(): Long = evaluated().size.toLong()

    fun findFirst(): T? = evaluated().firstOrNull()

    fun findFirstAsync(): T = findFirst() ?: clazz.getDeclaredConstructor().newInstance()

    fun findAll(): RealmResults<T> = results()

    fun findAllAsync(): RealmResults<T> = results()

    fun limit(count: Long): RealmQuery<T> {
        limit = count.toInt()
        return this
    }

    fun limit(count: Int): RealmQuery<T> {
        limit = count
        return this
    }

    private fun add(condition: (T) -> Boolean): RealmQuery<T> {
        predicate = combine(predicate, condition, nextOperator)
        nextOperator = Operator.AND
        return this
    }

    private fun combine(left: ((T) -> Boolean)?, right: (T) -> Boolean, operator: Operator): (T) -> Boolean {
        return when {
            left == null -> right
            operator == Operator.OR -> { model -> left(model) || right(model) }
            else -> { model -> left(model) && right(model) }
        }
    }

    private fun results(): RealmResults<T> = RealmResults(evaluated()) { evaluated() }

    private fun evaluated(): List<T> {
        val filter = predicate ?: { _: T -> true }
        val sorted = sorted(realm.loaded(clazz).filter(filter))
        return limit?.let(sorted::take) ?: sorted
    }

    private fun sorted(values: List<T>): List<T> {
        if (sorts.isEmpty()) return values
        return values.sortedWith { left, right ->
            sorts.firstNotNullOfOrNull { (fieldName, direction) ->
                compareValuesForSort(values(left, fieldName).firstOrNull(), values(right, fieldName).firstOrNull())
                    .takeIf { it != 0 }
                    ?.let { if (direction == Sort.ASCENDING) it else -it }
            } ?: 0
        }
    }

    private fun compareValuesForSort(left: Any?, right: Any?): Int {
        if (left == null && right == null) return 0
        if (left == null) return -1
        if (right == null) return 1
        if (left is Number && right is Number) return left.toDouble().compareTo(right.toDouble())
        if (left is Boolean && right is Boolean) return left.compareTo(right)
        return left.toString().compareTo(right.toString())
    }

    private fun values(model: Any?, path: String): List<Any?> {
        if (model == null) return listOf(null)
        val dot = path.indexOf('.')
        if (dot == -1) return listOf(RealmReflection.value(model, path))

        val head = path.substring(0, dot)
        val tail = path.substring(dot + 1)
        val value = RealmReflection.value(model, head)
        return when (value) {
            is Iterable<*> -> value.flatMap { values(it, tail) }
            else -> values(value, tail)
        }
    }

    private fun number(value: Any?): Double = (value as? Number)?.toDouble() ?: Double.NaN

    private fun notEmpty(value: Any?): Boolean = when (value) {
        null -> false
        is CharSequence -> value.isNotEmpty()
        is Collection<*> -> value.isNotEmpty()
        else -> true
    }

    private enum class Operator {
        AND,
        OR
    }
}

object RealmReflection {
    fun identity(model: RealmModel): RealmIdentity? {
        val field = primaryKeyField(model.javaClass) ?: fallbackKeyField(model.javaClass) ?: return null
        val value = field.value(model) ?: return null
        return RealmIdentity(model.javaClass.name, value.toString())
    }

    fun value(model: Any, name: String): Any? {
        field(model.javaClass, name)?.let { return it.value(model) }
        val suffix = name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        return listOf("get$suffix", "is$suffix")
            .firstNotNullOfOrNull { methodName ->
                runCatching { model.javaClass.getMethod(methodName).invoke(model) }.getOrNull()
            }
    }

    fun primaryKeyValue(model: RealmModel): Any? {
        return primaryKeyField(model.javaClass)?.value(model)
    }

    private fun primaryKeyField(clazz: Class<*>): Field? {
        return allFields(clazz).firstOrNull { it.getAnnotation(PrimaryKey::class.java) != null }
    }

    private fun fallbackKeyField(clazz: Class<*>): Field? {
        return listOf("id", "lookupKey", "date", "createdAt")
            .firstNotNullOfOrNull { field(clazz, it) }
    }

    private fun field(clazz: Class<*>, name: String): Field? {
        return allFields(clazz).firstOrNull { it.name == name }
    }

    private fun allFields(clazz: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            fields += current.declaredFields.onEach { it.isAccessible = true }
            current = current.superclass
        }
        return fields
    }

    private fun Field.value(model: Any): Any? = runCatching {
        isAccessible = true
        get(model)
    }.getOrNull()
}

object RealmSerialization {
    fun toBytes(model: RealmModel): ByteArray {
        return ByteArrayOutputStream().use { bytes ->
            ObjectOutputStream(bytes).use { output -> output.writeObject(model) }
            bytes.toByteArray()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : RealmModel> fromBytes(bytes: ByteArray): T {
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { input -> input.readObject() as T }
    }

    fun <T : RealmModel> copy(model: T): T = fromBytes(toBytes(model))
}

private class InMemoryRealmStore : RealmStore {
    private val objects = linkedMapOf<RealmIdentity, RealmModel>()

    override fun <T : RealmModel> all(clazz: Class<T>): List<T> {
        return objects
            .filterKeys { it.type == clazz.name }
            .values
            .mapNotNull { clazz.cast(it)?.let(RealmSerialization::copy) }
    }

    override fun upsert(model: RealmModel): RealmModel {
        val identity = RealmReflection.identity(model) ?: RealmIdentity(model.javaClass.name, System.nanoTime().toString())
        val copy = RealmSerialization.copy(model)
        objects[identity] = copy
        return RealmSerialization.copy(copy)
    }

    override fun upsertAll(models: Collection<RealmModel>): List<RealmModel> = models.map(::upsert)

    override fun insert(model: RealmModel): RealmModel {
        val existing = RealmReflection.identity(model)
        val identity = when {
            RealmReflection.primaryKeyValue(model) != null && existing != null -> existing
            else -> RealmIdentity(model.javaClass.name, System.nanoTime().toString())
        }
        val copy = RealmSerialization.copy(model)
        objects[identity] = copy
        return RealmSerialization.copy(copy)
    }

    override fun delete(clazz: Class<out RealmModel>) {
        objects.keys.filter { it.type == clazz.name }.forEach(objects::remove)
    }

    override fun delete(typeName: String) {
        val fullName = if (typeName.contains(".")) typeName else "dev.octoshrimpy.quik.model.$typeName"
        objects.keys.filter { it.type == fullName }.forEach(objects::remove)
    }

    override fun deleteIdentity(identity: RealmIdentity) {
        objects.remove(identity)
    }

    override fun changes(): Flowable<Unit> = Flowable.create({ emitter -> emitter.onNext(Unit) }, BackpressureStrategy.LATEST)
}
