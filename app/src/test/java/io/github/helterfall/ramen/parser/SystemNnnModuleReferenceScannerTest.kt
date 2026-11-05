package io.github.helterfall.ramen.parser

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnModuleReferenceScannerTest {

    @Test
    fun returnsExactPoolTwoModuleNameReferencesButNotOrdinaryLabels() {
        val script = testScript(
            records = listOf(
                listOf(5u, 0x66660006u, 0x22220004u, 1u, 0u),
                listOf(5u, 0x66660007u, 0x3333001Au, 1u, 1u),
                listOf(5u, 0x66660002u, 0x5556000Cu, 1u, 2u)
            ),
            pool2 = listOf("yamiko", "background_01", "01")
        )
        val stream = SystemNnnCommandStreamReader().read(script)

        val references = SystemNnnModuleReferenceScanner().scan(
            sourceModuleName = "03",
            script = script,
            stream = stream,
            projectModuleNames = listOf("sys", "01", "03", "yamiko")
        )

        assertEquals(2, references.size)
        assertEquals(listOf("yamiko", "01"), references.map { it.targetModuleName })
        assertEquals(listOf(0x50L, 0x64L), references.map { it.commandOffset })
        assertEquals(0x2222, references[0].codeFamily)
        assertEquals(0x0004, references[0].codeId)
        assertEquals(0x3333, references[1].codeFamily)
        assertEquals(0x001A, references[1].codeId)
        assertTrue(references.all { it.sourceModuleName == "03" })
    }

    @Test
    fun ignoresModuleNameLikePayloadsThatDoNotMatchTheExactReferenceShape() {
        val script = testScript(
            records = listOf(
                listOf(5u, 0x66660006u, 0x22220004u, 2u, 0u),
                listOf(4u, 0x66660007u, 0x3333001Au, 1u, 0u),
                listOf(5u, 0x66660007u, 0x3333001Bu, 1u, 4u)
            ),
            pool2 = listOf("yamiko")
        )
        val stream = SystemNnnCommandStreamReader().read(script)

        val references = SystemNnnModuleReferenceScanner().scan(
            "03", script, stream, listOf("yamiko")
        )

        assertTrue(references.isEmpty())
    }

    private fun testScript(
        records: List<List<UInt>>,
        pool2: List<String>
    ): SystemNnnScript {
        val words = listOf(0u, 0u, 0u, 0u) + records.flatten() + listOf(0u, 0u, 0u, 0u)
        val bytes = ByteArray(words.size * 4)
        words.forEachIndexed { index, word -> putUInt32(bytes, index * 4, word) }

        return SystemNnnScript(
            fileSizeBytes = 0x40L + bytes.size,
            firstHeaderWord = 0u,
            offsetTables = emptyList(),
            commandStartOffset = 0x40L,
            commandEndOffset = 0x40L + bytes.size,
            textPoolOffset = 0x40L + bytes.size,
            textPool = emptyList(),
            commandBytes = bytes,
            secondaryTextPool = pool2.mapIndexed { index, value ->
                SystemNnnTextEntry(
                    offset = 0L,
                    text = value,
                    rawBytes = value.toByteArray(Charsets.US_ASCII),
                    logicalAddress = index.toUInt()
                )
            }
        )
    }

    private fun putUInt32(bytes: ByteArray, offset: Int, value: UInt) {
        repeat(4) { index ->
            bytes[offset + index] = ((value shr (index * 8)) and 0xFFu).toByte()
        }
    }
}
