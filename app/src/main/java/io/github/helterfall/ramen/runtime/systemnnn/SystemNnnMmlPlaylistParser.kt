package io.github.helterfall.ramen.runtime.systemnnn

import java.io.IOException

internal data class SystemNnnMmlSequence(
    val segmentNames: List<String>,
    val infiniteLoopStartIndex: Int? = null
)

/** Parses the token and [count: ... ] loop syntax used by CMMLControl::Kaiseki. */
internal object SystemNnnMmlPlaylistParser {
    private const val MAX_DEPTH = 15
    private const val MAX_EXPANDED_SEGMENTS = 10_000
    private const val MAX_SEGMENT_NAME_LENGTH = 255

    fun parse(source: String): SystemNnnMmlSequence {
        val parsed = Parser(source.removePrefix("\uFEFF"))
            .parseSequence(depth = 0, stopAtClose = false)
        if (parsed.segmentNames.isEmpty()) throw IOException("The BGM MML playlist contains no WGQ segments.")
        return SystemNnnMmlSequence(parsed.segmentNames, parsed.infiniteLoopStartIndex)
    }

    private data class ParseResult(val segmentNames: List<String>, val infiniteLoopStartIndex: Int?)

    private class Parser(private val source: String) {
        private var cursor = 0
        private var totalExpandedSegments = 0

        fun parseSequence(depth: Int, stopAtClose: Boolean): ParseResult {
            if (depth > MAX_DEPTH) throw IOException("The BGM MML playlist nesting is too deep.")
            val segments = ArrayList<String>()
            while (cursor < source.length) {
                skipSeparators()
                if (cursor >= source.length) break
                when (source[cursor]) {
                    ']' -> {
                        if (!stopAtClose) throw IOException("The BGM MML playlist has an unexpected closing bracket.")
                        cursor++
                        return ParseResult(segments, null)
                    }
                    '[' -> {
                        cursor++
                        val loopCount = readLoopCount()
                        val nested = parseSequence(depth + 1, stopAtClose = true)
                        val start = segments.size
                        // The nested parser already counted its expanded segments. Copy its
                        // result into this parent without charging the same segments twice.
                        segments.addAll(nested.segmentNames)
                        if (nested.infiniteLoopStartIndex != null) {
                            return ParseResult(segments, start + nested.infiniteLoopStartIndex)
                        }
                        if (loopCount == 0) {
                            if (nested.segmentNames.isEmpty()) throw IOException("The BGM MML infinite loop is empty.")
                            return ParseResult(segments, start)
                        }
                        // Repeating an empty finite body cannot add any segments. Avoid
                        // iterating up to Int.MAX_VALUE times for a syntactically empty loop.
                        if (loopCount > 1 && nested.segmentNames.isNotEmpty()) {
                            repeat(loopCount - 1) { appendBounded(segments, nested.segmentNames) }
                        }
                    }
                    else -> {
                        val token = readSegmentName()
                        if (token.isNotEmpty()) appendBounded(segments, listOf(token))
                    }
                }
            }
            if (stopAtClose) throw IOException("The BGM MML playlist has an unclosed loop bracket.")
            return ParseResult(segments, null)
        }

        private fun readLoopCount(): Int {
            val start = cursor
            while (cursor < source.length && source[cursor].isDigit()) cursor++
            val digits = source.substring(start, cursor)
            if (cursor < source.length && source[cursor] == ':') cursor++
            if (digits.isEmpty()) return 0
            return digits.toIntOrNull()
                ?: throw IOException("The BGM MML loop count is outside the supported integer range.")
        }

        private fun readSegmentName(): String {
            val start = cursor
            while (cursor < source.length) {
                val c = source[cursor]
                if (c.isWhitespace() || c == ',' || c == '[' || c == ']') break
                if (c.isISOControl()) throw IOException("The BGM MML playlist contains a control character.")
                cursor++
                if (cursor - start > MAX_SEGMENT_NAME_LENGTH) {
                    throw IOException("A BGM WGQ segment name exceeds the native limit.")
                }
            }
            return source.substring(start, cursor)
        }

        private fun skipSeparators() {
            while (cursor < source.length && (source[cursor].isWhitespace() || source[cursor] == ',')) cursor++
        }

        private fun appendBounded(destination: MutableList<String>, additions: List<String>) {
            if (additions.size > MAX_EXPANDED_SEGMENTS - totalExpandedSegments) {
                throw IOException("The BGM MML playlist expands beyond the supported segment limit.")
            }
            destination.addAll(additions)
            totalExpandedSegments += additions.size
        }
    }
}
