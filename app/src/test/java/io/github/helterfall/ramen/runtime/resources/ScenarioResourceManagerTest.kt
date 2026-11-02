package io.github.helterfall.ramen.runtime.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScenarioResourceManagerTest {
    @Test
    fun matchesFullResourcePathBeforeBasename() {
        assertEquals(0, GpkResourceNameMatcher.rank("background/shore", "background/shore"))
        assertEquals(1, GpkResourceNameMatcher.rank("background/shore.png", "shore.png"))
    }

    @Test
    fun matchesFilenameWithoutExtensionAsFallback() {
        assertEquals(2, GpkResourceNameMatcher.rank("background/shore.jpg", "shore"))
        assertEquals(2, GpkResourceNameMatcher.rank("shore", "shore.bmp"))
    }

    @Test
    fun matchingIsCaseInsensitiveAndRejectsUnrelatedNames() {
        assertEquals(0, GpkResourceNameMatcher.rank("BG/SHORE", "bg/shore"))
        assertNull(GpkResourceNameMatcher.rank("bg/shore", "bg/night"))
    }
}
