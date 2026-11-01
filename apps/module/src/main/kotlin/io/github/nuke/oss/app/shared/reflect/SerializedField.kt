package io.github.nuke.oss.app.shared.reflect

import java.lang.reflect.Field

// A field of a Gson model looked up by the name the host serializes it under, with its own name as the fallback, once
// per class and reused. The host's models keep that name in a SerializedName annotation while the field itself may be
// obfuscated or in camelCase, so a lookup by serialized name is the one that holds across builds. The annotation is
// matched by class name, so nothing here links Gson. A class without the field reads as null.
internal class SerializedField(private val name: String) {
    private val fields = object : ClassValue<Field?>() {
        override fun computeValue(type: Class<*>): Field? {
            val chain = generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }.toList()
            val declared = chain.flatMap { it.declaredFields.asList() }.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            val found = declared.firstOrNull { name in serializedNames(it) } ?: declared.firstOrNull { it.name == name }
            return found?.apply { isAccessible = true }
        }
    }

    fun of(owner: Any): Field? = fields.get(owner.javaClass)

    private fun serializedNames(field: Field): List<String> {
        val annotation = field.declaredAnnotations.firstOrNull { it.annotationClass.java.name == SERIALIZED_NAME } ?: return emptyList()
        val type = annotation.annotationClass.java
        val value = runCatching { type.getMethod("value").invoke(annotation) as? String }.getOrNull()
        val alternate = runCatching { (type.getMethod("alternate").invoke(annotation) as? Array<*>)?.filterIsInstance<String>() }.getOrNull()
        return listOfNotNull(value) + alternate.orEmpty()
    }

    private companion object {
        const val SERIALIZED_NAME = "com.google.gson.annotations.SerializedName"
    }
}
