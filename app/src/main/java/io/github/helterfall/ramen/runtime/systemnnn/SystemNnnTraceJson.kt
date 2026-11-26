package io.github.helterfall.ramen.runtime.systemnnn

/** Small dependency-free JSON encoder for bounded diagnostic records. */
internal object SystemNnnTraceJson {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Char -> quote(value.toString())
        is Boolean -> value.toString()
        is Byte, is Short, is Int, is Long -> value.toString()
        is Float -> if (value.isFinite()) value.toString() else "null"
        is Double -> if (value.isFinite()) value.toString() else "null"
        is Map<*, *> -> value.entries.joinToString(
            prefix = "{",
            postfix = "}",
            separator = ","
        ) { entry ->
            quote(entry.key?.toString() ?: "null") + ":" + encode(entry.value)
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") {
            encode(it)
        }
        is IntArray -> value.joinToString(prefix = "[", postfix = "]", separator = ",")
        is LongArray -> value.joinToString(prefix = "[", postfix = "]", separator = ",")
        is FloatArray -> value.joinToString(prefix = "[", postfix = "]", separator = ",") {
            if (it.isFinite()) it.toString() else "null"
        }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") {
            encode(it)
        }
        else -> quote(value.toString())
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }
}
