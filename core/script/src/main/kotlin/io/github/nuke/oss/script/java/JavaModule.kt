package io.github.nuke.oss.script.java

import io.github.nuke.oss.script.JavaInvocationError
import io.github.nuke.oss.script.JavaValue
import io.github.nuke.oss.script.ScriptException
import io.github.nuke.oss.script.ScriptModule
import io.github.nuke.oss.script.ScriptModuleContext
import io.github.nuke.oss.script.ScriptPermissions
import io.github.nuke.oss.script.ScriptValueAdapter
import io.github.nuke.oss.script.longAt
import io.github.nuke.oss.script.stringAt
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// nuke:java for one session: reflection over the host through handles. classLoader is the host's final ClassLoader;
// scripts may name another loader they hold. The member caches live as long as the session and are keyed by Class
// objects, never by class names, so two loaders never share an entry.
class JavaModule(private val classLoader: ClassLoader, maxHandles: Int = 65_536) : ScriptModule {
    override val name = "nuke:java"
    override val source = SOURCE

    internal val handles = JavaHandles(maxHandles)
    private val methodCache = HashMap<Class<*>, HashMap<String, List<Method>>>()
    private lateinit var context: ScriptModuleContext

    override fun bind(context: ScriptModuleContext) {
        this.context = context
        context.setValueAdapter(Adapter())
        context.own(AutoCloseable { handles.clear() })
        context.function("java_release") { args -> handles.release(args.longAt(0, "id")); null }
        java("java_class") { args ->
            val loader = args.getOrNull(1)?.let { loaderOf(it) } ?: classLoader
            typeOf(args.stringAt(0, "name"), loader)
        }
        java("java_new") { args ->
            val type = classAt(args, 0)
            if (Modifier.isAbstract(type.modifiers)) throw ScriptException.argument("${type.name} is abstract")
            val values = valuesAt(args, 1)
            invoke(JavaValues.select(type.declaredConstructors.toList(), values, "constructor of ${type.name}"), null, values)
        }
        java("java_call") { args ->
            val name = args.stringAt(1, "name")
            val values = valuesAt(args, 2)
            if (args.getOrNull(3) == true) {
                val type = classAt(args, 0)
                val candidates = methods(type, name).filter { Modifier.isStatic(it.modifiers) }
                invoke(JavaValues.select(candidates, values, "static method ${type.name}.$name"), null, values)
            } else {
                val receiver = objectAt(args, 0)
                val candidates = methods(receiver.javaClass, name).filter { !Modifier.isStatic(it.modifiers) }
                invoke(JavaValues.select(candidates, values, "method ${receiver.javaClass.name}.$name"), receiver, values)
            }
        }
        java("java_invoke") { args ->
            val member = objectAt(args, 0) as? Executable ?: throw ScriptException.argument("member must be a method or constructor")
            val receiver = args.getOrNull(1)?.let { handles.get(it.asId("target")) }
            val values = valuesAt(args, 2)
            if (member is Method && !Modifier.isStatic(member.modifiers)) {
                if (receiver == null || !member.declaringClass.isInstance(receiver)) {
                    throw ScriptException.argument("${JavaValues.signature(member)} needs a ${member.declaringClass.name} receiver")
                }
            }
            invoke(member, receiver, values)
        }
        java("java_get") { args ->
            val (field, receiver) = fieldAt(args)
            field.get(receiver)
        }
        java("java_set") { args ->
            val (field, receiver) = fieldAt(args)
            if (Modifier.isFinal(field.modifiers)) throw ScriptException.argument("${field.name} is final")
            field.set(receiver, JavaValues.coerce(scriptValue(args.getOrNull(2)), field.type))
            null
        }
        java("java_method") { args ->
            val type = classAt(args, 0)
            val name = args.stringAt(1, "name")
            val candidates = methods(type, name)
            exactMember(candidates, typesAt(args, 2, type), "method ${type.name}.$name")
        }
        java("java_ctor") { args ->
            val type = classAt(args, 0)
            exactMember(type.declaredConstructors.toList(), typesAt(args, 1, type), "constructor of ${type.name}")
        }
        java("java_instance_of") { args ->
            val value = objectAt(args, 0)
            typeOf(args.stringAt(1, "className"), value.javaClass.classLoader ?: classLoader).isInstance(value)
        }
        java("java_is_instance") { args -> classAt(args, 0).isInstance(objectAt(args, 1)) }
        java("java_class_of") { args -> objectAt(args, 0).javaClass }
        java("java_equals") { args -> objectAt(args, 0) == objectAt(args, 1) }
        java("java_hash") { args -> objectAt(args, 0).hashCode() }
        java("java_string") { args -> objectAt(args, 0).toString() }
        java("java_elements", raw = true) { args ->
            when (val value = objectAt(args, 0)) {
                is Iterable<*> -> value.take(MAX_ELEMENTS + 1).also {
                    if (it.size > MAX_ELEMENTS) throw ScriptException.argument("More than $MAX_ELEMENTS elements")
                }.map(::JavaValue)
                else -> if (value.javaClass.isArray) {
                    val length = ReflectArray.getLength(value)
                    if (length > MAX_ELEMENTS) throw ScriptException.argument("More than $MAX_ELEMENTS elements")
                    List(length) { JavaValue(ReflectArray.get(value, it)) }
                } else {
                    throw ScriptException.argument("${value.javaClass.name} is neither an array nor Iterable")
                }
            }
        }
        java("java_bytes", raw = true) { args ->
            objectAt(args, 0) as? ByteArray ?: throw ScriptException.argument("The object is not a byte[]")
        }
        java("java_array") { args ->
            val component = typeOf(args.stringAt(0, "componentType"), classLoader)
            JavaValues.coerce(scriptValue(args.getOrNull(1)), ReflectArray.newInstance(component, 0).javaClass)
        }
    }

    // Applies this module's conversions to an argument a script passed to any module.
    fun scriptValue(value: Any?): Any? = Adapter().resolve(value)

    internal fun objectOf(value: Any?, name: String): Any = handles.get(value.asId(name))

    // Results go back through JavaValue so the session exports them, even Java collections; Strings, booleans and
    // numbers stay values. A raw function builds its script-facing result itself.
    private fun java(name: String, raw: Boolean = false, block: (List<Any?>) -> Any?) {
        context.function(name) { args ->
            context.requirePermission(ScriptPermissions.JAVA)
            val result = block(args)
            if (raw) result else JavaValue(result)
        }
    }

    private fun invoke(member: Executable, receiver: Any?, args: List<Any?>): Any? {
        val types = member.parameterTypes
        val values = Array(types.size) { JavaValues.coerce(args[it], types[it]) }
        member.access()
        return try {
            when (member) {
                is Method -> member.invoke(receiver, *values)
                is Constructor<*> -> member.newInstance(*values)
            }
        } catch (error: InvocationTargetException) {
            val thrown = error.targetException
            throw JavaInvocationError(thrown.javaClass.name, "${thrown.javaClass.name}: ${thrown.message}", thrown)
        } catch (error: IllegalAccessException) {
            throw ScriptException(ScriptException.JAVA_ERROR, "${JavaValues.signature(member)} is not accessible: ${error.message}")
        }
    }

    // Every method called name that type has or inherits, one per signature. Invoking any declaration of an
    // overridable signature dispatches to the most derived implementation, so a declaration in a public type is
    // preferred: a public method of a private class cannot always be made accessible.
    private fun methods(type: Class<*>, name: String): List<Method> =
        methodCache.getOrPut(type) { HashMap() }.getOrPut(name) {
            val found = LinkedHashMap<List<Class<*>>, Method>()
            fun offer(method: Method) {
                if (method.name != name || method.isBridge) return
                val signature = method.parameterTypes.toList()
                val current = found[signature]
                if (current == null || (!current.isPublicApi() && method.isPublicApi())) found[signature] = method
            }
            var current: Class<*>? = type
            while (current != null) {
                current.declaredMethods.forEach(::offer)
                current = current.superclass
            }
            type.methods.forEach(::offer)
            interfacesOf(type).forEach { it.methods.forEach(::offer) }
            found.values.toList()
        }

    private fun interfacesOf(type: Class<*>): Set<Class<*>> {
        val all = LinkedHashSet<Class<*>>()
        var current: Class<*>? = type
        while (current != null) {
            val pending = ArrayDeque(current.interfaces.toList())
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                if (all.add(next)) pending.addAll(next.interfaces)
            }
            current = current.superclass
        }
        return all
    }

    private fun fieldAt(args: List<Any?>): Pair<Field, Any?> {
        val name = args.stringAt(1, "field")
        val static = args.getOrNull(3) == true
        val receiver = if (static) null else objectAt(args, 0)
        val type = if (static) classAt(args, 0) else receiver!!.javaClass
        var current: Class<*>? = type
        while (current != null) {
            current.declaredFields.firstOrNull { it.name == name && Modifier.isStatic(it.modifiers) == static }?.let {
                it.access()
                return it to receiver
            }
            current = current.superclass
        }
        type.fields.firstOrNull { it.name == name && Modifier.isStatic(it.modifiers) == static }?.let { return it to receiver }
        throw ScriptException.argument("${type.name} has no ${if (static) "static " else ""}field $name")
    }

    private fun <T : Executable> exactMember(candidates: List<T>, types: List<Class<*>>?, what: String): T {
        if (types == null) {
            return candidates.singleOrNull() ?: throw ScriptException.argument(
                if (candidates.isEmpty()) "No $what" else "$what is overloaded: ${candidates.joinToString { JavaValues.signature(it) }}; pass parameter types",
            )
        }
        return candidates.firstOrNull { it.parameterTypes.toList() == types }
            ?: throw ScriptException.argument("No $what(${types.joinToString(",") { it.typeName }})")
    }

    private fun typesAt(args: List<Any?>, index: Int, owner: Class<*>): List<Class<*>>? {
        val names = args.getOrNull(index) as? List<*> ?: return null
        return names.map { typeOf(it as? String ?: throw ScriptException.argument("Parameter types must be names"), owner.classLoader ?: classLoader) }
    }

    private fun classAt(args: List<Any?>, index: Int): Class<*> =
        objectAt(args, index) as? Class<*> ?: throw ScriptException.argument("Expected a Java class")

    private fun objectAt(args: List<Any?>, index: Int): Any = handles.get(args.getOrNull(index).asId("object"))

    private fun valuesAt(args: List<Any?>, index: Int): List<Any?> =
        (scriptValue(args.getOrNull(index)) as? List<*>)?.toList() ?: throw ScriptException.argument("Arguments must be an array")

    private fun loaderOf(value: Any): ClassLoader =
        handles.get(value.asId("loader")) as? ClassLoader ?: throw ScriptException.argument("loader must be a ClassLoader")

    private fun typeOf(name: String, loader: ClassLoader): Class<*> {
        if (name.endsWith("[]")) return ReflectArray.newInstance(typeOf(name.removeSuffix("[]"), loader), 0).javaClass
        PRIMITIVES[name]?.let { return it }
        return try {
            Class.forName(name, false, loader)
        } catch (error: ClassNotFoundException) {
            throw ScriptException(ScriptException.JAVA_ERROR, "Class not found: $name")
        } catch (error: LinkageError) {
            throw ScriptException(ScriptException.JAVA_ERROR, "Class $name failed to load: $error")
        }
    }

    private inner class Adapter : ScriptValueAdapter {
        override fun export(value: Any): Any? = when (value) {
            is String, is Boolean, is Int, is Long, is Double, is Float, is Short, is Byte -> value
            is Char -> value.toString()
            else -> {
                val (id, collect) = handles.export(value)
                if (collect) context.requestGc()
                mapOf(
                    "__nkJava" to id,
                    "className" to value.javaClass.name,
                    "isClass" to (value is Class<*>),
                    "name" to (value as? Class<*>)?.name,
                )
            }
        }

        @OptIn(ExperimentalUnsignedTypes::class)
        override fun resolve(value: Any?): Any? = when (value) {
            is Map<*, *> -> if (value.containsKey("__nkJava")) {
                JavaRef(handles.get(value["__nkJava"].asId("object")))
            } else {
                value.entries.associate { (key, item) -> key.toString() to resolve(item) }
            }
            is List<*> -> value.map(::resolve)
            is UByteArray -> value.asByteArray()
            Unit -> null
            else -> value
        }
    }

    private companion object {
        const val MAX_ELEMENTS = 100_000
        val PRIMITIVES = listOfNotNull(
            Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Double::class.javaPrimitiveType, Float::class.javaPrimitiveType, Short::class.javaPrimitiveType,
            Byte::class.javaPrimitiveType, Char::class.javaPrimitiveType, Void.TYPE,
        ).associateBy { it.name }

        fun Any?.asId(name: String): Long = when (this) {
            is Number -> toLong()
            is Map<*, *> -> (this["__nkJava"] as? Number)?.toLong()
            else -> null
        } ?: throw ScriptException.argument("$name must be a Java object")

        fun Method.isPublicApi() = Modifier.isPublic(modifiers) && Modifier.isPublic(declaringClass.modifiers)

        // AccessibleObject.trySetAccessible is not on Android; a member that stays inaccessible fails on use.
        fun AccessibleObject.access() {
            try {
                isAccessible = true
            } catch (_: RuntimeException) {
            }
        }

        val SOURCE = """
const n = globalThis;
const c = __nuke.check;
const HANDLE = Symbol("nuke.java.handle");
const registry = new FinalizationRegistry((id) => n.__nk_java_release(id));

function isDescriptor(value) { return value !== null && typeof value === "object" && typeof value.__nkJava === "number"; }

function wrap(value) {
  if (isDescriptor(value)) return value.isClass ? new JavaClass(value) : new JavaObject(value);
  if (Array.isArray(value)) return value.map(wrap);
  return value;
}

function unwrap(value) {
  if (value instanceof JavaObject) return { __nkJava: value[HANDLE] };
  if (Array.isArray(value)) return value.map(unwrap);
  if (value !== null && typeof value === "object" && Object.getPrototypeOf(value) === Object.prototype) {
    const plain = {};
    for (const [key, item] of Object.entries(value)) plain[key] = unwrap(item);
    return plain;
  }
  return value === undefined ? null : value;
}

function handleOf(value, name) {
  if (!(value instanceof JavaObject)) throw new TypeError(name + " must be a Java object");
  return value[HANDLE];
}

function typeNames(list) {
  if (list === undefined || list === null) return null;
  if (!Array.isArray(list)) throw new TypeError("parameterTypes must be an array");
  return list.map((type) => (type instanceof JavaClass ? type.name : String(type)));
}

export class JavaObject {
  constructor(descriptor) {
    Object.defineProperty(this, HANDLE, { value: descriptor.__nkJava });
    Object.defineProperty(this, "className", { value: descriptor.className, enumerable: true });
    registry.register(this, descriptor.__nkJava);
  }
  call(name, ...args) { return wrap(c(n.__nk_java_call(this[HANDLE], String(name), unwrap(args), false))); }
  invoke(member, ...args) { return wrap(c(n.__nk_java_invoke(handleOf(member, "member"), this[HANDLE], unwrap(args)))); }
  get(field) { return wrap(c(n.__nk_java_get(this[HANDLE], String(field), null, false))); }
  set(field, value) { c(n.__nk_java_set(this[HANDLE], String(field), unwrap(value), false)); }
  instanceOf(className) { return c(n.__nk_java_instance_of(this[HANDLE], String(className))); }
  getClass() { return wrap(c(n.__nk_java_class_of(this[HANDLE]))); }
  equals(other) { return other instanceof JavaObject && c(n.__nk_java_equals(this[HANDLE], other[HANDLE])); }
  hashCode() { return c(n.__nk_java_hash(this[HANDLE])); }
  toString() { return c(n.__nk_java_string(this[HANDLE])); }
  toArray() { return wrap(c(n.__nk_java_elements(this[HANDLE]))); }
  toBytes() { return c(n.__nk_java_bytes(this[HANDLE])); }
}

export class JavaClass extends JavaObject {
  constructor(descriptor) {
    super(descriptor);
    Object.defineProperty(this, "name", { value: descriptor.name, enumerable: true });
  }
  new(...args) { return wrap(c(n.__nk_java_new(this[HANDLE], unwrap(args)))); }
  call(name, ...args) { return wrap(c(n.__nk_java_call(this[HANDLE], String(name), unwrap(args), true))); }
  invoke(member, ...args) { return wrap(c(n.__nk_java_invoke(handleOf(member, "member"), null, unwrap(args)))); }
  get(field) { return wrap(c(n.__nk_java_get(this[HANDLE], String(field), null, true))); }
  set(field, value) { c(n.__nk_java_set(this[HANDLE], String(field), unwrap(value), true)); }
  method(name, parameterTypes) { return wrap(c(n.__nk_java_method(this[HANDLE], String(name), typeNames(parameterTypes)))); }
  ctor(parameterTypes) { return wrap(c(n.__nk_java_ctor(this[HANDLE], typeNames(parameterTypes)))); }
  isInstance(value) { return value instanceof JavaObject && c(n.__nk_java_is_instance(this[HANDLE], value[HANDLE])); }
}

export function use(name, loader) {
  return wrap(c(n.__nk_java_class(String(name), loader === undefined || loader === null ? null : handleOf(loader, "loader"))));
}

export function array(componentType, values) {
  const name = componentType instanceof JavaClass ? componentType.name : String(componentType);
  return wrap(c(n.__nk_java_array(name, unwrap([...values]))));
}

export function isJava(value) { return value instanceof JavaObject; }

export { wrap as __wrap, unwrap as __unwrap };
"""
    }
}
