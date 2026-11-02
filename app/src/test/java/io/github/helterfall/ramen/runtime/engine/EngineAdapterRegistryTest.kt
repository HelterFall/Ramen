package io.github.helterfall.ramen.runtime.engine

import java.io.ByteArrayInputStream
import java.io.IOException
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineAdapterRegistryTest {
    @Test
    fun returnsTheOnlyMatchingAdapterWithoutInventingPriority() {
        val source = FakeEngineSource()
        val registry = EngineAdapterRegistry(
            listOf(
                FakeAdapter("engine-a", EngineProbeResult.NoMatch),
                FakeAdapter(
                    "engine-b",
                    EngineProbeResult.Match(
                        confidence = EngineMatchConfidence.MEDIUM,
                        formatVersion = "2",
                        evidence = listOf(EngineEvidence("Recognized marker.", "script/main.bin"))
                    )
                )
            )
        )

        val result = registry.probe(source)

        assertEquals("engine-b", result.uniqueMatch?.descriptor?.id)
        assertFalse(result.isAmbiguous)
        assertFalse(result.hasNoMatch)
    }

    @Test
    fun multipleMatchingAdaptersRemainAmbiguousEvenWhenConfidenceDiffers() {
        val registry = EngineAdapterRegistry(
            listOf(
                FakeAdapter(
                    "engine-a",
                    EngineProbeResult.Match(
                        EngineMatchConfidence.HIGH,
                        evidence = listOf(EngineEvidence("Marker A.", "a.dat"))
                    )
                ),
                FakeAdapter(
                    "engine-b",
                    EngineProbeResult.Match(
                        EngineMatchConfidence.LOW,
                        evidence = listOf(EngineEvidence("Marker B.", "b.dat"))
                    )
                )
            )
        )

        val result = registry.probe(FakeEngineSource())

        assertTrue(result.isAmbiguous)
        assertNull(result.uniqueMatch)
        assertEquals(2, result.matches.size)
    }

    @Test
    fun malformedCandidateIsPreservedSeparatelyFromNoMatch() {
        val registry = EngineAdapterRegistry(
            listOf(
                FakeAdapter(
                    "engine-a",
                    EngineProbeResult.Malformed(
                        reason = "Index table is truncated.",
                        evidence = listOf(EngineEvidence("Found an incomplete index.", "index.bin"))
                    )
                )
            )
        )

        val result = registry.probe(FakeEngineSource())

        assertFalse(result.hasNoMatch)
        assertEquals(1, result.malformed.size)
        assertEquals("Index table is truncated.", result.malformed.single().reason)
    }

    @Test
    fun ioFailureFromOneAdapterDoesNotPreventOtherAdaptersFromMatching() {
        val registry = EngineAdapterRegistry(
            listOf(
                ThrowingAdapter("engine-a", "Candidate file ended unexpectedly."),
                FakeAdapter(
                    "engine-z",
                    EngineProbeResult.Match(
                        confidence = EngineMatchConfidence.MEDIUM,
                        evidence = listOf(EngineEvidence("Independent marker.", "main.idx"))
                    )
                )
            )
        )

        val result = registry.probe(FakeEngineSource())

        assertEquals("engine-z", result.uniqueMatch?.descriptor?.id)
        assertEquals("engine-a", result.malformed.single().descriptor.id)
        assertEquals(
            "Candidate file ended unexpectedly.",
            result.malformed.single().reason
        )
    }

    @Test
    fun adapterResultsAreOrderedByStableIdRatherThanInputCollectionOrder() {
        val registry = EngineAdapterRegistry(
            listOf(
                FakeAdapter(
                    "engine-z",
                    EngineProbeResult.Match(
                        EngineMatchConfidence.LOW,
                        evidence = listOf(EngineEvidence("Z marker.", "z.idx"))
                    )
                ),
                FakeAdapter(
                    "engine-a",
                    EngineProbeResult.Match(
                        EngineMatchConfidence.LOW,
                        evidence = listOf(EngineEvidence("A marker.", "a.idx"))
                    )
                )
            )
        )

        val result = registry.probe(FakeEngineSource())

        assertEquals(listOf("engine-a", "engine-z"), result.matches.map { it.descriptor.id })
        assertTrue(result.isAmbiguous)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateAdapterIdsAreRejected() {
        EngineAdapterRegistry(
            listOf(
                FakeAdapter("engine-a", EngineProbeResult.NoMatch),
                FakeAdapter("engine-a", EngineProbeResult.NoMatch)
            )
        )
    }

    private class FakeAdapter(
        id: String,
        private val result: EngineProbeResult
    ) : EngineAdapter {
        override val descriptor = EngineAdapterDescriptor(
            id = id,
            displayName = id,
            formatIds = setOf("sample-format")
        )

        override fun probe(source: EngineSource): EngineProbeResult = result
    }

    private class ThrowingAdapter(
        id: String,
        private val message: String
    ) : EngineAdapter {
        override val descriptor = EngineAdapterDescriptor(
            id = id,
            displayName = id,
            formatIds = setOf("sample-format")
        )

        override fun probe(source: EngineSource): EngineProbeResult {
            throw IOException(message)
        }
    }

    private class FakeEngineSource : EngineSource {
        override val sourceId = "test-source"
        override val displayName = "Test source"
        override val entries = listOf(EngineSourceEntry("script/main.bin", 0L))
        override val isTruncated = false
        override val warnings = emptyList<String>()

        override fun openRead(relativePath: String) = ByteArrayInputStream(ByteArray(0))

        override fun openRandomAccess(relativePath: String): ReadOnlyRandomAccessSource {
            throw UnsupportedOperationException("Not used by this test.")
        }
    }
}
