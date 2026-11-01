package io.github.nuke.oss.app.shared.reflect

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.makeAccessible
import java.lang.reflect.Field
import java.lang.reflect.Method

// A field looked up by name once per class and reused. The cache is keyed by the Class itself, so two ClassLoaders
// that define a class of the same name never share an entry. A class without the field reads as null.
internal class NamedField(private val name: String) {
    private val fields = object : ClassValue<Field?>() {
        override fun computeValue(type: Class<*>): Field? =
            type.resolve().optional(silent = true).firstFieldOrNull {
                name = this@NamedField.name
                superclass()
            }?.self?.apply { makeAccessible() }
    }

    fun of(owner: Any): Field? = fields.get(owner.javaClass)
}

// The first of several names the class has, for a model that names a field in camelCase in one host and by its
// serialized snake_case name in another. A class with none of them reads as null.
internal class NamedFieldAlias(vararg names: String) {
    private val candidates = names.map(::NamedField)

    fun of(owner: Any): Field? = candidates.firstNotNullOfOrNull { it.of(owner) }
}

// A public no-argument method looked up by name once per class, like NamedField. A class without it reads as null.
internal class NamedGetter(private val name: String) {
    private val methods = object : ClassValue<Method?>() {
        override fun computeValue(type: Class<*>): Method? =
            type.resolve().optional(silent = true).firstMethodOrNull {
                name = this@NamedGetter.name
                emptyParameters()
                modifiers(Modifiers.PUBLIC)
                superclass(interfaces = true)
            }?.self?.apply { makeAccessible() }
    }

    fun of(owner: Any): Method? = methods.get(owner.javaClass)
}

// A no-argument method of any visibility looked up by name in the class and its superclasses, like NamedField.
internal class NamedCall(private val name: String) {
    private val methods = object : ClassValue<Method?>() {
        override fun computeValue(type: Class<*>): Method? =
            type.resolve().optional(silent = true).firstMethodOrNull {
                name = this@NamedCall.name
                emptyParameters()
                superclass()
            }?.self?.apply { makeAccessible() }
    }

    fun of(owner: Any): Method? = methods.get(owner.javaClass)
}
