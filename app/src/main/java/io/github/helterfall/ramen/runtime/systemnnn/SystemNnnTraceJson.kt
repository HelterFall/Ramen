package io.github.helterfall.ramen.runtime.systemnnn

/** Small dependency-free JSON encoder for bounded diagnostic records. */
internal object SystemNnnTraceJson {
    fun encode(value: Any?): String = buildString {
        appendValue(value, this)
    }

    /** Writes JSON incrementally to avoid holding another full report-sized String in memory. */
    fun write(value: Any?, destination: Appendable) {
        appendValue(value, destination)
    }

    private fun appendValue(value: Any?, destination: Appendable) {
        when (value) {
            null -> destination.append("null")
            is String -> appendQuoted(value, destination)
            is Char -> appendQuoted(value.toString(), destination)
            is Boolean -> destination.append(value.toString())
            is Byte, is Short, is Int, is Long -> destination.append(value.toString())
            is Float -> destination.append(if (value.isFinite()) value.toString() else "null")
            is Double -> destination.append(if (value.isFinite()) value.toString() else "null")
            is Map<*, *> -> {
                destination.append('{')
                var first = true
                value.entries.forEach { entry ->
                    if (!first) destination.append(',')
                    first = false
                    appendQuoted(entry.key?.toString() ?: "null", destination)
                    destination.append(':')
                    appendValue(entry.value, destination)
                }
                destination.append('}')
            }
            is Iterable<*> -> {
                destination.append('[')
                var first = true
                value.forEach { item ->
                    if (!first) destination.append(',')
                    first = false
                    appendValue(item, destination)
                }
                destination.append(']')
            }
            is IntArray -> appendPrimitiveArray(value.iterator(), destination)
            is LongArray -> appendPrimitiveArray(value.iterator(), destination)
            is FloatArray -> {
                destination.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) destination.append(',')
                    destination.append(if (item.isFinite()) item.toString() else "null")
                }
                destination.append(']')
            }
            is Array<*> -> {
                destination.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) destination.append(',')
                    appendValue(item, destination)
                }
                destination.append(']')
            }
            else -> appendQuoted(value.toString(), destination)
        }
    }

    private fun appendPrimitiveArray(values: Iterator<*>, destination: Appendable) {
        destination.append('[')
        var first = true
        values.forEach { item ->
            if (!first) destination.append(',')
            first = false
            destination.append(item.toString())
        }
        destination.append(']')
    }

    private fun appendQuoted(value: String, destination: Appendable) {
        destination.append('"')
        value.forEach { character ->
            when (character) {
                '"' -> destination.append("\\\"")
                '\\' -> destination.append("\\\\")
                '\b' -> destination.append("\\b")
                '\u000C' -> destination.append("\\f")
                '\n' -> destination.append("\\n")
                '\r' -> destination.append("\\r")
                '\t' -> destination.append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        destination.append("\\u")
                        destination.append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        destination.append(character)
                    }
                }
            }
        }
        destination.append('"')
    }
}
