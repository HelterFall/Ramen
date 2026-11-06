package io.github.helterfall.ramen.runtime.systemnnn

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SystemNnnMmlPlaylistParserTest {

    @Test
    fun parsesASequenceOfNamedSegments() {
        val sequence = SystemNnnMmlPlaylistParser.parse("intro.wgq, scene01.wgq outro.wgq")

        assertEquals(
            listOf("intro.wgq", "scene01.wgq", "outro.wgq"),
            sequence.segmentNames
        )
        assertEquals(null, sequence.infiniteLoopStartIndex)
    }

    @Test
    fun expandsFiniteLoopsWithoutCountingNestedSegmentsTwice() {
        val sequence = SystemNnnMmlPlaylistParser.parse("intro [2: a b] outro")

        assertEquals(
            listOf("intro", "a", "b", "a", "b", "outro"),
            sequence.segmentNames
        )
        assertEquals(null, sequence.infiniteLoopStartIndex)
    }

    @Test
    fun tracksTheStartOfAnInfiniteLoopAfterTheIntro() {
        val sequence = SystemNnnMmlPlaylistParser.parse("intro [0: a b] ignored")

        assertEquals(listOf("intro", "a", "b"), sequence.segmentNames)
        assertEquals(1, sequence.infiniteLoopStartIndex)
    }

    @Test
    fun expandsNestedFiniteLoops() {
        val sequence = SystemNnnMmlPlaylistParser.parse("start [2: a [2: b c]]")

        assertEquals(
            listOf("start", "a", "b", "c", "b", "c", "a", "b", "c", "b", "c"),
            sequence.segmentNames
        )
    }

    @Test
    fun acceptsExactlyTheConfiguredExpandedSegmentLimit() {
        val sequence = SystemNnnMmlPlaylistParser.parse("[5000: a b]")

        assertEquals(10_000, sequence.segmentNames.size)
    }

    @Test
    fun rejectsExpansionBeyondTheConfiguredLimit() {
        assertThrows(IOException::class.java) {
            SystemNnnMmlPlaylistParser.parse("[5001: a b]")
        }
    }

    @Test
    fun rejectsLoopCountsThatOverflowTheSupportedIntegerRange() {
        assertThrows(IOException::class.java) {
            SystemNnnMmlPlaylistParser.parse("[999999999999999999999: a]")
        }
    }

    @Test
    fun rejectsMalformedAndEmptySequences() {
        assertThrows(IOException::class.java) {
            SystemNnnMmlPlaylistParser.parse("   , ")
        }
        assertThrows(IOException::class.java) {
            SystemNnnMmlPlaylistParser.parse("[2: a b")
        }
        assertThrows(IOException::class.java) {
            SystemNnnMmlPlaylistParser.parse("a b]")
        }
    }
}
