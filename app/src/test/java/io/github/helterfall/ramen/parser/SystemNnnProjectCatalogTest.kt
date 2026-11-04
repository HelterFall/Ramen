package io.github.helterfall.ramen.parser

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SystemNnnProjectCatalogTest {

    @Test
    fun parsesCrLfManifestAndPreservesDeclaredModuleOrder() {
        val directory = Files.createTempDirectory("ramen-system-nnn-project").toFile()
        try {
            val manifest = File(directory, "project.xtx")
            manifest.writeText("sys\r\nbackspt\r\n01\r\nyuka2\r\n", Charsets.US_ASCII)

            val catalog = SystemNnnProjectCatalog.load(manifest)

            assertEquals(listOf("sys", "backspt", "01", "yuka2"), catalog.moduleNames)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun resolvesSiblingScriptsWithoutChangingManifestOrder() {
        val directory = Files.createTempDirectory("ramen-system-nnn-project-files").toFile()
        try {
            val manifest = File(directory, "project.xtx")
            manifest.writeText("sys\r\n01\r\nyuka2\r\n", Charsets.US_ASCII)
            File(directory, "sys.spt").writeBytes(byteArrayOf(1))
            File(directory, "01.SPT").writeBytes(byteArrayOf(2))
            File(directory, "yuka2.spt").writeBytes(byteArrayOf(3))

            val modules = SystemNnnProjectCatalog.load(manifest).resolveModules(manifest)

            assertEquals(listOf("sys", "01", "yuka2"), modules.map { it.name })
            assertEquals(
                listOf("sys.spt", "01.SPT", "yuka2.spt"),
                modules.map { it.scriptFile.name }
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsDuplicateNamesIgnoringCase() {
        val directory = Files.createTempDirectory("ramen-system-nnn-duplicate").toFile()
        try {
            val manifest = File(directory, "project.xtx")
            manifest.writeText("01\r\n01\r\n", Charsets.US_ASCII)

            assertThrows(IOException::class.java) {
                SystemNnnProjectCatalog.load(manifest)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsPathLikeModuleNames() {
        val directory = Files.createTempDirectory("ramen-system-nnn-path").toFile()
        try {
            val manifest = File(directory, "project.xtx")
            manifest.writeText("../01\r\n", Charsets.US_ASCII)

            assertThrows(IOException::class.java) {
                SystemNnnProjectCatalog.load(manifest)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsNonAsciiManifestBytes() {
        val directory = Files.createTempDirectory("ramen-system-nnn-encoding").toFile()
        try {
            val manifest = File(directory, "project.xtx")
            manifest.writeBytes(byteArrayOf(0x31, 0x0D, 0x0A, 0xFF.toByte()))

            assertThrows(IOException::class.java) {
                SystemNnnProjectCatalog.load(manifest)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun reportsMissingSiblingScript() {
        val directory = Files.createTempDirectory("ramen-system-nnn-missing").toFile()
        try {
            val manifest = File(directory, "project.xtx")
            manifest.writeText("sys\r\n", Charsets.US_ASCII)

            assertThrows(IOException::class.java) {
                SystemNnnProjectCatalog.load(manifest).resolveModules(manifest)
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
