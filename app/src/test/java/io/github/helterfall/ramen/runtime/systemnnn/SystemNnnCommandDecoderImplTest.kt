package io.github.helterfall.ramen.runtime.systemnnn

import io.github.helterfall.ramen.parser.SystemNnnOffsetTable
import io.github.helterfall.ramen.parser.SystemNnnScript
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnCommandDecoderImplTest {

    @Test
    fun refusesToInterpretUnverifiedVmBytes() {
        val script = SystemNnnScript(
            fileSizeBytes = 0x80L,
            firstHeaderWord = 20u,
            offsetTables = listOf(
                SystemNnnOffsetTable("main", 1u, 0xC5BAu),
                SystemNnnOffsetTable("secondary", 1u, 0x167F2u),
                SystemNnnOffsetTable("tertiary", 0u, 0u),
                SystemNnnOffsetTable("auxiliary", 0u, 0u)
            ),
            commandStartOffset = 0x40L,
            commandEndOffset = 0x70L,
            textPoolOffset = 0x70L,
            textPool = emptyList(),
            commandBytes = ByteArray(0x30)
        )

        val failure = runCatching {
            SystemNnnCommandDecoderImpl(gtbResourceCount = 8).decodeNext(script, 0x40L)
        }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
        assertTrue(failure?.message?.contains("not implemented") == true)
    }
}
