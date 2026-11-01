package io.github.nuke.oss.app.shared.data

// Reads the string members of a JSON object's top level. Members of other types, including nested objects and arrays,
// are skipped without being read, and anything that is not a well-formed object gives null. Host payloads are small and
// flat, so this stands in for a JSON library the module does not otherwise need.
internal object FlatJson {
    fun strings(json: String): Map<String, String>? = Reader(json).readObject()

    private class Reader(private val text: String) {
        private var at = 0

        fun readObject(): Map<String, String>? {
            skipSpace()
            if (!take('{')) return null
            val members = LinkedHashMap<String, String>()
            skipSpace()
            if (take('}')) return members.takeIf { atEnd() }
            while (true) {
                skipSpace()
                val key = readString() ?: return null
                skipSpace()
                if (!take(':')) return null
                skipSpace()
                if (peek() == '"') members[key] = readString() ?: return null else if (!skipValue()) return null
                skipSpace()
                if (take(',')) continue
                if (take('}')) return members.takeIf { atEnd() }
                return null
            }
        }

        private fun atEnd(): Boolean { skipSpace(); return at == text.length }

        private fun peek(): Char? = text.getOrNull(at)

        private fun take(c: Char): Boolean = if (peek() == c) { at++; true } else false

        private fun skipSpace() { while (at < text.length && text[at].isWhitespace()) at++ }

        private fun readString(): String? {
            if (!take('"')) return null
            val out = StringBuilder()
            while (at < text.length) {
                val c = text[at++]
                when {
                    c == '"' -> return out.toString()
                    c != '\\' -> out.append(c)
                    at >= text.length -> return null
                    else -> when (val escape = text[at++]) {
                        '"', '\\', '/' -> out.append(escape)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            val code = text.substring(at, minOf(at + 4, text.length)).takeIf { it.length == 4 }?.toIntOrNull(16) ?: return null
                            out.append(code.toChar())
                            at += 4
                        }
                        else -> return null
                    }
                }
            }
            return null
        }

        // A nested value is skipped by bracket depth, with brackets inside strings ignored; a scalar ends at , or }.
        private fun skipValue(): Boolean {
            var depth = 0
            while (at < text.length) {
                when (text[at]) {
                    '"' -> { readString() ?: return false; if (depth == 0) return true; continue }
                    '{', '[' -> depth++
                    '}', ']' -> { if (depth == 0) return true; depth-- }
                    ',' -> if (depth == 0) return true
                }
                at++
                if (depth == 0 && at < text.length && (text[at] == ',' || text[at] == '}')) return true
            }
            return false
        }
    }
}
