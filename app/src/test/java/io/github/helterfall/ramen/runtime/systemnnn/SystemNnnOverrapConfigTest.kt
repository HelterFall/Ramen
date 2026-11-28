package io.github.helterfall.ramen.runtime.systemnnn

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnOverrapConfigTest {

    @Test
    fun parsesNativeWhitespaceAndCommaSeparatedNameListPairs() {
        val config = SystemNnnOverrapConfig.parse(
            """
            modename printoverrap
            optionButtonPrintFlag 0
            sceneButtonPrintFlag, 1
            """.trimIndent()
        )

        assertEquals(0, config.optionButtonPrintFlag)
        assertEquals(1, config.sceneButtonPrintFlag)
        assertTrue(config.isConfigured)
    }

    @Test
    fun parsesOptionalEqualsSyntaxCaseInsensitively() {
        val config = SystemNnnOverrapConfig.parse(
            "OPTIONBUTTONPRINTFLAG=0\nSceneButtonPrintFlag=0"
        )

        assertEquals(0, config.optionButtonPrintFlag)
        assertEquals(0, config.sceneButtonPrintFlag)
        assertTrue(config.isConfigured)
    }

    @Test
    fun missingSetupUsesNativeDefaultButtonFlags() {
        val config = SystemNnnOverrapConfig.parse("modename printoverrap")

        assertEquals(1, config.optionButtonPrintFlag)
        assertEquals(1, config.sceneButtonPrintFlag)
        assertFalse(config.isConfigured)
    }

    @Test
    fun loadReadsInitOverrapXtxWithNativeWindows31jEncoding() {
        val root = Files.createTempDirectory("ramen-overrap-init").toFile()
        try {
            val init = File(root, "init").apply { mkdirs() }
            File(init, "Overrap.xtx").writeBytes(
                "optionButtonPrintFlag 0\nsceneButtonPrintFlag 1".toByteArray(Charsets.UTF_8)
            )

            val config = SystemNnnOverrapConfig.load(root)

            assertEquals(0, config.optionButtonPrintFlag)
            assertEquals(1, config.sceneButtonPrintFlag)
            assertTrue(config.isConfigured)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun encryptedInit2OverrapFxfTakesPrecedenceAndUsesNativeXorFF() {
        val root = Files.createTempDirectory("ramen-overrap-init2").toFile()
        try {
            val init = File(root, "init").apply { mkdirs() }
            val init2 = File(root, "init2").apply { mkdirs() }
            File(init, "Overrap.xtx").writeText(
                "optionButtonPrintFlag 1\nsceneButtonPrintFlag 1"
            )
            val encrypted = "optionButtonPrintFlag 0\nsceneButtonPrintFlag 0"
                .toByteArray(Charsets.UTF_8)
                .also { bytes ->
                    for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor 0xFF).toByte()
                }
            File(init2, "Overrap.fxf").writeBytes(encrypted)

            val config = SystemNnnOverrapConfig.load(root)

            assertEquals(0, config.optionButtonPrintFlag)
            assertEquals(0, config.sceneButtonPrintFlag)
            assertTrue(config.isConfigured)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingOptionalSetupReturnsDefaults() {
        val root = Files.createTempDirectory("ramen-overrap-default").toFile()
        try {
            val config = SystemNnnOverrapConfig.load(root)

            assertEquals(SystemNnnOverrapConfig(), config)
        } finally {
            root.deleteRecursively()
        }
    }
}
