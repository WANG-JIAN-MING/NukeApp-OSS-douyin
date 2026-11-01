package io.github.nuke.oss.app.tiktok.sdk.chat

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

// Finds the chat Message inside one entry of the chat list's data. The entries wrap the message in host classes whose
// field names change with every obfuscation, so the object graph is searched a few levels deep for an instance of the
// Message class instead of reading a named field. The path found for a class of entry is remembered.
internal class ChatMessageFinder(private val messageType: Class<*>) {
    private val paths = Collections.synchronizedMap(HashMap<Class<*>, List<Field>>())

    fun find(entry: Any?): Any? {
        entry ?: return null
        if (messageType.isInstance(entry)) return entry
        paths[entry.javaClass]?.let { path -> follow(entry, path)?.let { return it } }
        val path = search(entry) ?: return null
        paths[entry.javaClass] = path
        return follow(entry, path)
    }

    private fun follow(entry: Any, path: List<Field>): Any? {
        var current: Any? = entry
        for (field in path) current = current?.let { field.get(it) }
        return current?.takeIf(messageType::isInstance)
    }

    private fun search(entry: Any): List<Field>? {
        var level = listOf(entry to emptyList<Field>())
        repeat(DEPTH) {
            val next = ArrayList<Pair<Any, List<Field>>>()
            for ((holder, path) in level) {
                for (field in fieldsOf(holder.javaClass)) {
                    val value = field.get(holder) ?: continue
                    if (messageType.isInstance(value)) return path + field
                    if (!value.javaClass.isPlatformClass()) next += value to path + field
                }
            }
            level = next
        }
        return null
    }

    private fun fieldsOf(type: Class<*>): List<Field> = generateSequence(type) { it.superclass }
        .takeWhile { !it.isPlatformClass() }
        .flatMap { it.declaredFields.asSequence() }
        .filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
        .onEach { it.isAccessible = true }
        .toList()

    private companion object {
        const val DEPTH = 3
    }
}

// Pulls the message for a row out of the adapter's data store: the store keeps its entries newest last, and the adapter
// numbers rows from the newest. Older hosts hand the adapter a store that is itself the list holder; Douyin 40.7.0
// hands it a thin wrapper whose list sits one object deeper, next to a second list that mirrors it, so the lists of the
// store's own fields are searched as well. Fields only: calling a getter on a live controller from a bind could do
// work or take locks. A list is taken only if its size is the adapter's row count, which tells the mirror apart while
// the two disagree; hosts whose count means something else still resolve without that check.
internal class ChatRows(private val finder: ChatMessageFinder) {
    private val lists = object : ClassValue<List<(Any) -> List<*>?>>() {
        override fun computeValue(type: Class<*>): List<(Any) -> List<*>?> {
            val chain = generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }.toList()
            val getters = chain.flatMap { it.declaredMethods.asList() }
                .filter { it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) && !Modifier.isStatic(it.modifiers) }
                .map { method -> method.apply { isAccessible = true }.let { m: Method -> { store: Any -> m.invoke(store) as? List<*> } } }
            val readers = chain.flatMap { it.declaredFields.asList() }
                .filter { List::class.java.isAssignableFrom(it.type) && !Modifier.isStatic(it.modifiers) }
                .map { field -> field.apply { isAccessible = true }.let { f: Field -> { store: Any -> f.get(store) as? List<*> } } }
            return getters + readers
        }
    }

    private val nested = object : ClassValue<List<Field>>() {
        override fun computeValue(type: Class<*>): List<Field> = generateSequence(type) { it.superclass }
            .takeWhile { !it.isPlatformClass() }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive && !it.type.isArray && !it.type.isPlatformClass() }
            .onEach { it.isAccessible = true }
            .toList()
    }

    private val fieldLists = object : ClassValue<List<Field>>() {
        override fun computeValue(type: Class<*>): List<Field> = generateSequence(type) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { List::class.java.isAssignableFrom(it.type) && !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }
            .toList()
    }

    // rowCount is the adapter's own row count when it can be read.
    fun messageAt(store: Any, position: Int, rowCount: Int? = null): Any? {
        if (rowCount != null) find(store, position, rowCount)?.let { return it }
        return find(store, position, null)
    }

    private fun find(store: Any, position: Int, rowCount: Int?): Any? {
        for (list in candidates(store)) {
            if (rowCount != null && list.size != rowCount) continue
            val message = finder.find(list.getOrNull(list.size - 1 - position))
            if (message != null) return message
        }
        return null
    }

    private fun candidates(store: Any): Sequence<List<*>> = sequence {
        for (read in lists.get(store.javaClass)) read(store)?.let { yield(it) }
        for (field in nested.get(store.javaClass)) {
            val inner = field.get(store)?.takeIf { !it.javaClass.isPlatformClass() } ?: continue
            for (listField in fieldLists.get(inner.javaClass)) (listField.get(inner) as? List<*>)?.let { yield(it) }
        }
    }
}

private fun Class<*>.isPlatformClass() = name.startsWith("java.") || name.startsWith("android.") || name.startsWith("kotlin.")

// The chat list adapter's row events: a row view created, and a row bound to the message it shows. The adapter is found
// by class name, and the message behind a row comes from the data store the adapter was built with. Features that
// decorate chat rows each install their own hooks through this, in their own scope.
internal class ChatAdapterEvents(
    private val hostClassLoader: ClassLoader,
    private val adapterName: String = ADAPTER,
    private val messageName: String = MESSAGE,
) {
    private val itemView = NamedField("itemView")

    // onCreated gets the list the row was created for (the RecyclerView) and the row; onBound gets the row and message.
    fun install(scope: FeatureScope, featureId: String, onCreated: (list: Any?, row: Any) -> Unit, onBound: (row: Any, message: Any) -> Unit) {
        val adapter = adapterName.toClass(hostClassLoader)
        val members = adapter.resolve().optional(silent = true)
        val create = members.method {
            name = "onCreateViewHolder"
            parameters { it.size == 2 && it[1] == Int::class.javaPrimitiveType }
            isSynthetic = false
        }.singleOrNull()?.self ?: error("${adapter.name} declares no single onCreateViewHolder")
        val bind = members.method {
            name = "onBindViewHolder"
            parameters { it.size == 3 && it[1] == Int::class.javaPrimitiveType && it[2] == List::class.java }
            isSynthetic = false
        }.singleOrNull()?.self ?: error("${adapter.name} declares no single onBindViewHolder")
        val constructors = members.constructor { isSynthetic = false; parameterCount { it >= 2 } }.map { it.self }
        check(constructors.isNotEmpty()) { "${adapter.name} declares no constructor that takes a data store" }
        val rows = ChatRows(ChatMessageFinder(messageName.toClass(hostClassLoader)))
        // The data store is the second constructor argument; each adapter keeps its own, weakly.
        val stores = Collections.synchronizedMap(WeakHashMap<Any, WeakReference<Any>>())
        constructors.forEachIndexed { index, constructor ->
            constructor.isAccessible = true
            scope.hooks.install(constructor, HookSpec(
                id = "$featureId:AdapterStore$index",
                requiredCapabilities = setOf(HookCapability.CONSTRUCTOR_OBSERVE_AFTER),
                after = { call ->
                    val receiver = call.receiver
                    val store = call.argument(1)
                    if (call.outcome is CallOutcome.Returned && receiver != null && store != null) stores[receiver] = WeakReference(store)
                },
            ))
        }
        create.isAccessible = true
        bind.isAccessible = true
        scope.hooks.install(create, HookSpec(id = "$featureId:RowCreated", after = { call ->
            val holder = (call.outcome as? CallOutcome.Returned)?.value
            val row = holder?.let { itemView.of(it)?.get(it) }
            if (row != null) onCreated(call.argument(0), row)
        }))
        scope.hooks.install(bind, HookSpec(id = "$featureId:RowBound", after = { call ->
            val holder = call.argument(0)
            val position = call.argument(1) as? Int
            val row = holder?.let { itemView.of(it)?.get(it) }
            val adapterInstance = call.receiver
            val store = adapterInstance?.let { stores[it]?.get() }
            if (call.outcome is CallOutcome.Returned && row != null && position != null && store != null) {
                rows.messageAt(store, position, rowCount(adapterInstance))?.let { onBound(row, it) }
            }
        }))
    }

    // RecyclerView.Adapter.getItemCount is public API; an adapter stand-in without it just skips the size check.
    private val itemCount = object : ClassValue<Method?>() {
        override fun computeValue(type: Class<*>): Method? = try {
            type.getMethod("getItemCount")
        } catch (_: NoSuchMethodException) {
            null
        }
    }

    private fun rowCount(adapter: Any): Int? = try {
        itemCount.get(adapter.javaClass)?.invoke(adapter) as? Int
    } catch (_: ReflectiveOperationException) {
        null
    }

    companion object {
        const val ADAPTER = "com.ss.android.ugc.aweme.im.business.chat.msgcell.common.CellComposeAdapter"
        const val MESSAGE = "com.bytedance.im.core.model.Message"
    }
}
