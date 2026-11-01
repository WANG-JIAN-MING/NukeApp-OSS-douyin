package io.github.nuke.oss.app.shared.reflect

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.makeAccessible
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

// Host members are resolved once while a feature installs, so Hook callbacks never search by name.
// A missing or ambiguous member fails the install instead of picking the first candidate.

internal fun Class<*>.declaredMethodNamed(name: String): Method {
    val found = declaredMethodsNamed(name)
    check(found.size == 1) { "Expected one $name in ${this.name}, found ${found.size}" }
    return found.single()
}

// Every non-synthetic method of that name declared by the class itself, the way an overload set is hooked as a whole.
internal fun Class<*>.declaredMethodsNamed(name: String): List<Method> =
    resolve().method {
        this.name = name
        isSynthetic = false
        isBridge = false
    }.map { it.self.apply { makeAccessible() } }

// The one method with that return type and no parameters; a second candidate is ambiguous, not a tie to break.
internal fun Class<*>.noArgMethodReturning(type: Class<*>): Method {
    val found = resolve().optional(silent = true).method {
        emptyParameters()
        returnType = type
        isSynthetic = false
        isBridge = false
    }
    check(found.size == 1) { "Expected one ${type.simpleName} getter in ${this.name}, found ${found.size}" }
    return found.single().self.apply { makeAccessible() }
}

// Calls the first method of that name and parameter count found on the object, its superclasses or its interfaces.
// An exception the host method throws comes out as itself, not wrapped. Only for calls whose target is not known
// before the object is; anything knowable at install time is resolved once instead.
internal fun Any.callByName(name: String, vararg arguments: Any?): Any? {
    val method = asResolver().optional(silent = true).firstMethodOrNull {
        this.name = name
        parameterCount = arguments.size
        isBridge = false
        superclass(interfaces = true)
    } ?: throw NoSuchMethodException("${javaClass.name}.$name")
    try {
        return method.invoke(*arguments)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}
