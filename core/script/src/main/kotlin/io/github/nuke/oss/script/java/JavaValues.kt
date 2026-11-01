package io.github.nuke.oss.script.java

import io.github.nuke.oss.script.ScriptException
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Executable

// A Java object the script passed back by reference, as opposed to a JavaScript value that needs converting.
class JavaRef(val value: Any)

// Converts script values to Java parameter types. A cost ranks how well a value fits a type so overloads can be
// chosen; null means it does not fit at all.
object JavaValues {
    private const val MAX_SAFE = 9_007_199_254_740_991.0

    fun cost(value: Any?, type: Class<*>): Int? = when (value) {
        null -> if (type.isPrimitive) null else if (type == Any::class.java) 2 else 1
        is JavaRef -> when {
            type.isPrimitive -> numberCost(value.value as? Number, type)?.let { it + 1 }
            type.isInstance(value.value) -> if (type == Any::class.java) 2 else 0
            else -> null
        }
        is Boolean -> when (type) {
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> 0
            else -> if (generalOf(type)) 3 else null
        }
        is Number -> numberCost(value, type)
        is String -> when {
            type == String::class.java -> 0
            type == CharSequence::class.java -> 1
            (type == Char::class.javaPrimitiveType || type == Char::class.javaObjectType) && value.length == 1 -> 2
            generalOf(type) -> 3
            else -> null
        }
        is ByteArray -> when {
            type == ByteArray::class.java -> 0
            type == Any::class.java -> 3
            else -> null
        }
        is List<*> -> when {
            type.isArray -> value.map { cost(it, type.componentType) ?: return null }.maxOrNull()?.plus(1) ?: 1
            type.isAssignableFrom(ArrayList::class.java) && type != Any::class.java -> 2
            else -> null
        }
        is Map<*, *> -> if (type.isAssignableFrom(LinkedHashMap::class.java) && type != Any::class.java) 2 else null
        else -> null
    }

    fun coerce(value: Any?, type: Class<*>): Any? {
        if (cost(value, type) == null) {
            throw ScriptException.argument("${describe(value)} cannot be passed as ${type.typeName}")
        }
        return when (value) {
            null -> null
            is JavaRef -> if (type.isPrimitive) number(value.value as Number, type) else value.value
            is Number -> number(value, type)
            is String -> if (type == Char::class.javaPrimitiveType || type == Char::class.javaObjectType) value[0] else value
            is List<*> -> if (type.isArray) {
                val array = ReflectArray.newInstance(type.componentType, value.size)
                value.forEachIndexed { index, item -> ReflectArray.set(array, index, coerce(item, type.componentType)) }
                array
            } else {
                ArrayList(value.map { coerce(it, Any::class.java) })
            }
            is Map<*, *> -> LinkedHashMap(value.entries.associate { (key, item) -> key.toString() to coerce(item, Any::class.java) })
            else -> value
        }
    }

    fun describe(value: Any?): String = when (value) {
        null -> "null"
        is JavaRef -> value.value.javaClass.name
        is List<*> -> "array"
        is Map<*, *> -> "object"
        is ByteArray -> "bytes"
        else -> value.javaClass.simpleName.lowercase()
    }

    // Picks the overload whose parameters fit best; a tie between different signatures is an error rather than a
    // guess.
    fun <T : Executable> select(candidates: Collection<T>, args: List<Any?>, what: String): T {
        val scored = candidates.filter { it.parameterCount == args.size }.mapNotNull { candidate ->
            var total = 0
            candidate.parameterTypes.forEachIndexed { index, type -> total += cost(args[index], type) ?: return@mapNotNull null }
            candidate to total
        }
        val best = scored.minOfOrNull { it.second }
            ?: throw ScriptException.argument("No $what accepts (${args.joinToString { describe(it) }})")
        val winners = scored.filter { it.second == best }.map { it.first }
        if (winners.size > 1) {
            throw ScriptException.argument(
                "Ambiguous $what for (${args.joinToString { describe(it) }}): " +
                    winners.joinToString { signature(it) } + "; pass an explicit signature",
            )
        }
        return winners.single()
    }

    fun signature(member: Executable): String =
        "${member.name}(${member.parameterTypes.joinToString(",") { it.typeName }})"

    private fun generalOf(type: Class<*>) =
        type == Any::class.java || type == java.io.Serializable::class.java || type == Comparable::class.java

    private fun numberCost(value: Number?, type: Class<*>): Int? {
        value ?: return null
        val double = value.toDouble()
        val whole = value !is Double && value !is Float || (double % 1.0 == 0.0 && double in -MAX_SAFE..MAX_SAFE)
        return when (type) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> if (whole && double in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) 0 else null
            Long::class.javaPrimitiveType, Long::class.javaObjectType -> if (whole) 1 else null
            Short::class.javaPrimitiveType, Short::class.javaObjectType ->
                if (whole && double in Short.MIN_VALUE.toDouble()..Short.MAX_VALUE.toDouble()) 2 else null
            Byte::class.javaPrimitiveType, Byte::class.javaObjectType ->
                if (whole && double in Byte.MIN_VALUE.toDouble()..Byte.MAX_VALUE.toDouble()) 2 else null
            Double::class.javaPrimitiveType, Double::class.javaObjectType -> if (whole) 3 else 0
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> if (whole) 4 else 1
            Number::class.java -> 5
            else -> if (generalOf(type)) 5 else null
        }
    }

    private fun number(value: Number, type: Class<*>): Any = when (type) {
        Int::class.javaPrimitiveType, Int::class.javaObjectType -> value.toInt()
        Long::class.javaPrimitiveType, Long::class.javaObjectType -> value.toLong()
        Short::class.javaPrimitiveType, Short::class.javaObjectType -> value.toShort()
        Byte::class.javaPrimitiveType, Byte::class.javaObjectType -> value.toByte()
        Double::class.javaPrimitiveType, Double::class.javaObjectType -> value.toDouble()
        Float::class.javaPrimitiveType, Float::class.javaObjectType -> value.toFloat()
        // A general parameter gets the narrowest box that keeps the value.
        else -> when {
            value is Double || value is Float -> {
                val double = value.toDouble()
                if (double % 1.0 != 0.0) double
                else if (double in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) double.toInt() else double.toLong()
            }
            value.toLong() in Int.MIN_VALUE..Int.MAX_VALUE -> value.toInt()
            else -> value.toLong()
        }
    }
}
