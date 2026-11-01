package io.github.nuke.oss.hook.runtime

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

internal object CallValidation {
    fun arguments(target: Executable, arguments: Array<Any?>) {
        require(arguments.size == target.parameterCount) { "Argument count does not match $target" }
        target.parameterTypes.forEachIndexed { index, type -> value(type, arguments[index]) }
    }

    fun returned(target: Executable, result: Any?) {
        require(target is Method) { "Constructors are observe-only" }
        if (target.returnType == Void.TYPE) require(result == null) { "void must return null" }
        else value(target.returnType, result)
    }

    fun argument(target: Executable, index: Int, argument: Any?) {
        require(target !is Constructor<*>) { "Constructors are observe-only" }
        value(target.parameterTypes[index], argument)
    }

    private fun value(type: Class<*>, value: Any?) {
        val boxed = when (type) {
            Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
            Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
            Char::class.javaPrimitiveType -> Char::class.javaObjectType
            Short::class.javaPrimitiveType -> Short::class.javaObjectType
            Int::class.javaPrimitiveType -> Int::class.javaObjectType
            Long::class.javaPrimitiveType -> Long::class.javaObjectType
            Float::class.javaPrimitiveType -> Float::class.javaObjectType
            Double::class.javaPrimitiveType -> Double::class.javaObjectType
            else -> type
        }
        require(if (value == null) !type.isPrimitive else boxed.isInstance(value)) {
            "Value is incompatible with ${type.name}"
        }
    }
}

@Suppress("DEPRECATION")
internal fun Throwable.rethrowIfFatal() {
    if (this is VirtualMachineError || this is ThreadDeath || this is LinkageError) throw this
}
