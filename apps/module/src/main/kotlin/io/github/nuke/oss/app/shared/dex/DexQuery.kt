package io.github.nuke.oss.app.shared.dex

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindField
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

// Marks an object whose DexQuery properties make up part of its host's catalog. core/hook-ksp lists every such
// property in the host package's generatedDexQueries under the name "<Object>.<PROPERTY>", so a query is never
// registered or named by hand. Properties are UPPER_SNAKE_CASE and must not be private.
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class DexQueries

// How to find one host member with DexKit. locate returns the serialized descriptor, never a reflection object.
// The query's name comes from the catalog it is registered in; a locate failure is reported under that name, so the
// messages it throws need not repeat it. Raise version whenever the lookup logic changes so cached descriptors
// from older logic are discarded.
class DexQuery(val version: Int = 1, val locate: (DexKitBridge) -> String) {
    init {
        require(version > 0) { "Dex query version must be positive" }
    }
}

fun method(version: Int = 1, query: FindMethod.() -> Unit): DexQuery = DexQuery(version) { it.uniqueMethod(query) }

// Every method the query selects is part of the result; an empty selection is a stale lookup.
fun methods(version: Int = 1, query: FindMethod.() -> Unit): DexQuery = DexQuery(version) { joinDescriptors(it.findMethod(query)) }

fun field(version: Int = 1, query: FindField.() -> Unit): DexQuery = DexQuery(version) { it.uniqueField(query) }

// A matcher that selects zero or several members is a stale or ambiguous lookup, not a best-effort hit.
fun DexKitBridge.uniqueMethod(query: FindMethod.() -> Unit): String {
    val found = findMethod(query)
    check(found.size == 1) { "expected one method, found ${found.size}: " + found.take(8).joinToString { it.descriptor } }
    return found.single().descriptor
}

fun DexKitBridge.uniqueField(query: FindField.() -> Unit): String {
    val found = findField(query)
    check(found.size == 1) { "expected one field, found ${found.size}: " + found.take(8).joinToString { it.descriptor } }
    return found.single().descriptor
}

// A class located first and then searched for one method, for hosts whose method has no string of its own.
fun DexKitBridge.uniqueClass(query: FindClass.() -> Unit): ClassData {
    val found = findClass(query)
    check(found.size == 1) { "expected one class, found ${found.size}: " + found.take(8).joinToString { it.name } }
    return found.single()
}

// Several descriptors in one cached value, for a host behavior that is implemented in more than one place.
const val DESCRIPTOR_SEPARATOR = "\n"

fun joinDescriptors(found: Collection<MethodData>): String {
    check(found.isNotEmpty()) { "matched no method" }
    return found.joinToString(DESCRIPTOR_SEPARATOR) { it.descriptor }
}
