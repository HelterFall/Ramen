package io.github.helterfall.ramen.importer

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlInlineResourceExtractorTest {
    @Test
    fun extractsEmbeddedImageAndAudioToPrivateAssets() {
        val directory = Files.createTempDirectory("ramen-inline-assets").toFile()
        try {
            val html = File(directory, "index.html")
            val image = Base64.getEncoder().encodeToString("png-bytes".toByteArray())
            val audio = Base64.getEncoder().encodeToString("audio-bytes".toByteArray())
            html.writeText(
                """<html><body><img src="data:image/png;base64,$image"><script>const sound = new Audio('data:audio/mpeg;base64,$audio');</script></body></html>""",
                StandardCharsets.UTF_8
            )

            val result = HtmlInlineResourceExtractor().extract(html, directory)

            assertEquals(2, result.extractedCount)
            assertEquals(("png-bytes".length + "audio-bytes".length).toLong(), result.extractedBytes)
            val rewritten = html.readText(StandardCharsets.UTF_8)
            assertTrue(rewritten.contains("/game/assets/inline-0001.png"))
            assertTrue(rewritten.contains("/game/assets/inline-0002.mp3"))
            assertEquals(
                "png-bytes",
                File(directory, "assets/inline-0001.png").readText(StandardCharsets.UTF_8)
            )
            assertEquals(
                "audio-bytes",
                File(directory, "assets/inline-0002.mp3").readText(StandardCharsets.UTF_8)
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun leavesUnsupportedDataUrisUntouched() {
        val directory = Files.createTempDirectory("ramen-inline-unsupported").toFile()
        try {
            val html = File(directory, "index.html")
            val source = """<p data-value="data:text/plain;base64,SGVsbG8=">test</p>"""
            html.writeText(source, StandardCharsets.UTF_8)

            val result = HtmlInlineResourceExtractor().extract(html, directory)

            assertEquals(0, result.extractedCount)
            assertEquals(source, html.readText(StandardCharsets.UTF_8))
            assertFalse(File(directory, "assets").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun extractsLargeInlineResourcesWithoutAnArbitraryByteCeiling() {
        val directory = Files.createTempDirectory("ramen-inline-large").toFile()
        try {
            val html = File(directory, "index.html")
            val payload = ByteArray(2 * 1024 * 1024 + 17) { (it % 251).toByte() }
            val encoded = Base64.getEncoder().encodeToString(payload)
            html.writeText(
                """<img src="data:image/png;base64,$encoded">""",
                StandardCharsets.UTF_8
            )

            val result = HtmlInlineResourceExtractor().extract(html, directory)

            assertEquals(1, result.extractedCount)
            assertEquals(payload.size.toLong(), result.extractedBytes)
            assertTrue(File(directory, "assets/inline-0001.png").readBytes().contentEquals(payload))
            assertTrue(html.readText(StandardCharsets.UTF_8).contains("/game/assets/inline-0001.png"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
