package io.github.nuke.oss.app.shared.data

// A small JSON reader and writer for host payloads that need a nested edit. Objects are LinkedHashMap<String, Any?>,
// arrays are ArrayList<Any?>, numbers without a fraction or exponent are Long and the rest Double, and strings,
// booleans and null are themselves. Anything that is not exactly one well-formed value gives null from parse, so a
// caller never edits half a document.
internal object MiniJson {
    fun parse(text: String): Any? = try {
        Reader(text).readDocument()
    } catch (_: IllegalArgumentException) {
        null
    }

    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): MutableMap<String, Any?>? = value as? MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun array(value: Any?): MutableList<Any?>? = value as? MutableList<Any?>

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> appendString(out, value)
            is Boolean, is Long, is Int -> out.append(value.toString())
            is Double -> out.append(if (value % 1.0 == 0.0 && Math.abs(value) < 1e15) value.toLong().toString() else value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, member) in value) {
                    if (!first) out.append(',')
                    first = false
                    appendString(out, key.toString())
                    out.append(':')
                    append(out, member)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, element ->
                    if (index > 0) out.append(',')
                    append(out, element)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("Cannot write ${value.javaClass.name}")
        }
    }

    private fun appendString(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private class Reader(private val text: String) {
        private var at = 0

        fun readDocument(): Any? {
            val value = readValue()
            skipSpace()
            require(at == text.length) { "Trailing text" }
            return value
        }

        private fun readValue(): Any? {
            skipSpace()
            require(at < text.length) { "Unexpected end" }
            return when (val c = text[at]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw IllegalArgumentException("Unexpected $c")
            }
        }

        private fun readObject(): Any {
            val members = LinkedHashMap<String, Any?>()
            at++
            skipSpace()
            if (peek() == '}') return members.also { at++ }
            while (true) {
                skipSpace()
                require(peek() == '"') { "Expected a key" }
                val key = readString()
                skipSpace()
                require(peek() == ':') { "Expected :" }
                at++
                members[key] = readValue()
                skipSpace()
                when (peek()) {
                    ',' -> at++
                    '}' -> return members.also { at++ }
                    else -> throw IllegalArgumentException("Expected , or }")
                }
            }
        }

        private fun readArray(): Any {
            val elements = ArrayList<Any?>()
            at++
            skipSpace()
            if (peek() == ']') return elements.also { at++ }
            while (true) {
                elements += readValue()
                skipSpace()
                when (peek()) {
                    ',' -> at++
                    ']' -> return elements.also { at++ }
                    else -> throw IllegalArgumentException("Expected , or ]")
                }
            }
        }

        private fun readString(): String {
            val out = StringBuilder()
            at++
            while (true) {
                require(at < text.length) { "Unterminated string" }
                val c = text[at++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(at < text.length) { "Unterminated escape" }
                        when (val e = text[at++]) {
                            '"', '\\', '/' -> out.append(e)
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'u' -> {
                                require(at + 4 <= text.length) { "Short escape" }
                                out.append(text.substring(at, at + 4).toInt(16).toChar())
                                at += 4
                            }
                            else -> throw IllegalArgumentException("Bad escape")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = at
            if (peek() == '-') at++
            while (at < text.length && (text[at].isDigit() || text[at] in ".eE+-")) at++
            val token = text.substring(start, at)
            return token.toLongOrNull() ?: token.toDoubleOrNull() ?: throw IllegalArgumentException("Bad number")
        }

        private fun literal(word: String, value: Any?): Any? {
            require(text.startsWith(word, at)) { "Bad literal" }
            at += word.length
            return value
        }

        private fun peek(): Char = if (at < text.length) text[at] else throw IllegalArgumentException("Unexpected end")
        private fun skipSpace() { while (at < text.length && text[at].isWhitespace()) at++ }
    }
}
