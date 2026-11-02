package io.github.helterfall.ramen.runtime.engine

import java.io.ByteArrayInputStream
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
