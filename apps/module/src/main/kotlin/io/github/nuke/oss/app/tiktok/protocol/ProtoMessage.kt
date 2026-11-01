package io.github.nuke.oss.app.tiktok.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

// Wire representation without a schema. Varint and fixed values live in value (fixed32 in the low 32 bits).
class ProtoField(val number: Int, val wireType: Int, val value: Long = 0, bytes: ByteArray? = null) {
    private val payload = bytes?.copyOf()
    val bytes: ByteArray? get() = payload?.copyOf()
}

class ProtoMessage(fields: List<ProtoField>) {
    val fields: List<ProtoField> = fields.toList()
    fun all(number: Int): List<ProtoField> = fields.filter { it.number == number }
    fun varint(number: Int): Long? = fields.lastOrNull { it.number == number && it.wireType == WIRE_VARINT }?.value
    fun bytes(number: Int): ByteArray? = fields.lastOrNull { it.number == number && it.wireType == WIRE_BYTES }?.bytes
    fun string(number: Int): String? = bytes(number)?.toString(Charsets.UTF_8)
    fun message(number: Int): ProtoMessage? = bytes(number)?.let(::decode)
    fun encode(): ByteArray = native { encode(toTable(fields)) }

    companion object {
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
        private const val MAX_DEPTH = 64
        private const val WIRE_VARINT = 0
        private const val WIRE_FIXED64 = 1
        private const val WIRE_BYTES = 2
        private const val WIRE_FIXED32 = 5

        fun decode(bytes: ByteArray): ProtoMessage = ProtoMessage(fromTable(native { decode(bytes) }))

        // Builds a message from field-number maps: lists repeat a field, maps become nested messages.
        fun encode(fields: Map<Int, Any?>): ByteArray = native { encode(toTable(flatten(fields, 0))) }

        private fun flatten(fields: Map<Int, Any?>, depth: Int): List<ProtoField> {
            require(depth <= MAX_DEPTH) { "Protobuf nesting exceeds $MAX_DEPTH" }
            val out = ArrayList<ProtoField>()
            fields.forEach { (number, value) -> add(out, number, value, depth) }
            return out
        }

        private fun add(out: MutableList<ProtoField>, number: Int, value: Any?, depth: Int) {
            when (value) {
                null -> return
                is Iterable<*> -> {
                    require(depth < MAX_DEPTH) { "Protobuf nesting exceeds $MAX_DEPTH" }
                    value.forEach { add(out, number, it, depth + 1) }
                }
                is Array<*> -> {
                    require(depth < MAX_DEPTH) { "Protobuf nesting exceeds $MAX_DEPTH" }
                    value.forEach { add(out, number, it, depth + 1) }
                }
                is Boolean -> out += ProtoField(number, WIRE_VARINT, if (value) 1 else 0)
                is Int -> out += ProtoField(number, WIRE_VARINT, value.toLong())
                is Long -> out += ProtoField(number, WIRE_VARINT, value)
                is Double -> out += ProtoField(number, WIRE_FIXED64, value.toRawBits())
                is Float -> out += ProtoField(number, WIRE_FIXED32, value.toRawBits().toLong() and 0xffffffffL)
                is String -> out += ProtoField(number, WIRE_BYTES, bytes = value.toByteArray(Charsets.UTF_8))
                is ByteArray -> out += ProtoField(number, WIRE_BYTES, bytes = value)
                is ProtoMessage -> out += ProtoField(number, WIRE_BYTES, bytes = value.encode())
                is Map<*, *> -> {
                    val nested = value.entries.associate { (key, item) ->
                        require(key is Int) { "Nested protobuf keys must be field numbers" }
                        key to item
                    }
                    out += ProtoField(number, WIRE_BYTES, bytes = native { encode(toTable(flatten(nested, depth + 1))) })
                }
                else -> throw IllegalArgumentException("Unsupported protobuf value ${value.javaClass.name}")
            }
        }

        // Layout is defined in table.rs: big-endian number, wire type, then value or length-prefixed bytes.
        private fun toTable(fields: List<ProtoField>): ByteArray {
            val buffer = ByteArrayOutputStream()
            val out = DataOutputStream(buffer)
            for (field in fields) {
                out.writeInt(field.number)
                out.writeByte(field.wireType)
                when (field.wireType) {
                    WIRE_VARINT, WIRE_FIXED64 -> out.writeLong(field.value)
                    WIRE_FIXED32 -> out.writeInt(field.value.toInt())
                    WIRE_BYTES -> {
                        val bytes = requireNotNull(field.bytes) { "Length-delimited field ${field.number} has no bytes" }
                        out.writeInt(bytes.size)
                        out.write(bytes)
                    }
                    else -> throw IllegalArgumentException("Unsupported wire type ${field.wireType}")
                }
            }
            return buffer.toByteArray()
        }

        private fun fromTable(table: ByteArray): List<ProtoField> {
            val buffer = ByteBuffer.wrap(table)
            val fields = ArrayList<ProtoField>()
            while (buffer.hasRemaining()) {
                val number = buffer.int
                when (val wire = buffer.get().toInt()) {
                    WIRE_VARINT, WIRE_FIXED64 -> fields += ProtoField(number, wire, buffer.long)
                    WIRE_FIXED32 -> fields += ProtoField(number, wire, buffer.int.toLong() and 0xffffffffL)
                    WIRE_BYTES -> fields += ProtoField(number, wire, bytes = ByteArray(buffer.int).also(buffer::get))
                    else -> throw ProtocolException(ProtocolError(ProtocolErrorCode.INTERNAL, "Unknown wire type $wire in native field table"))
                }
            }
            return fields
        }
    }
}
