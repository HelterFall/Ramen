package io.github.helterfall.ramen.runtime.systemnnn

import io.github.helterfall.ramen.parser.SystemNnnOffsetTable
import io.github.helterfall.ramen.parser.SystemNnnScript
import io.github.helterfall.ramen.parser.SystemNnnTextEntry
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnPlayerViewModelTest {
    @Test
    fun waitsForTypewriterThenShowsChoiceAndBranchesToSelectedTarget() {
        val script = testScript()
        val decoder = FakeDecoder(
            mapOf(
                0xC5BAL to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = 0xC5BB,
                    textEntryOffset = 0x32B64,
                    speakerName = "Guide"
                ),
                0xC5BBL to SystemNnnDecodedCommand.Choice(
                    promptTextEntryOffset = 0x32B70,
                    options = listOf(
                        SystemNnnChoiceOption("left", "Take the left path", 0xC5BD),
                        SystemNnnChoiceOption("right", "Take the right path", 0xC5BE)
                    )
                ),
                0xC5BDL to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = 0xC5C0,
                    textEntryOffset = 0x32B7F
                ),
                0xC5BEL to SystemNnnDecodedCommand.End
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(script, decoder, EmptyAssets)

        assertEquals("First line", viewModel.uiState.dialogueText)
        assertFalse(viewModel.uiState.isTextComplete)

        viewModel.onScreenTap()
        assertTrue(viewModel.uiState.isTextComplete)
        assertEquals("First line".length, viewModel.uiState.visibleCharacterCount)

        viewModel.onScreenTap()
        assertEquals(2, viewModel.uiState.choices.size)
        assertEquals("Choose a path", viewModel.uiState.dialogueText)

        viewModel.onChoiceSelected("left")
        assertTrue(viewModel.uiState.choices.isEmpty())
        assertEquals("Left branch", viewModel.uiState.dialogueText)
        assertEquals("left", decoder.lastRequestedBranch)
    }

    @Test
    fun skipsUnmappedSystemWordAndContinuesToExactTextPoolEntry() {
        val decoder = FakeDecoder(
            mapOf(
                0xC5BAL to SystemNnnDecodedCommand.Skip(nextCommandOffset = 0xC5BEL),
                0xC5BEL to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = 0xC5C2,
                    textEntryOffset = 0x32B64
                )
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, EmptyAssets)

        assertEquals("First line", viewModel.uiState.dialogueText)
        assertEquals(null, viewModel.uiState.errorMessage)
        assertFalse(viewModel.uiState.isLoading)
    }

    @Test
    fun requestsSceneBitmapUsingGtbIndexRatherThanRawStringIdentifier() {
        val decoder = FakeDecoder(
            mapOf(
                0xC5BAL to SystemNnnDecodedCommand.SceneUpdate(
                    nextCommandOffset = 0xC5BE,
                    backgroundResourceIndex = 3L
                )
            )
        )
        val assets = IndexRecordingAssets
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, assets)

        assertEquals(3L, assets.lastRequestedIndex)
        assertEquals(null, assets.lastRequestedName)
    }

    @Test
    fun missingDecoderDoesNotPretendToInterpretRawBytesAsOpcodes() {
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), null, EmptyAssets)

        assertNotNull(viewModel.uiState.errorMessage)
        assertTrue(viewModel.uiState.errorMessage!!.contains("декодер команд"))
        assertFalse(viewModel.uiState.isLoading)
    }

    @Test
    fun invalidChoiceTargetIsRejected() {
        val decoder = FakeDecoder(
            mapOf(
                0xC5BAL to SystemNnnDecodedCommand.Choice(
                    promptTextEntryOffset = 0x32B70,
                    options = listOf(SystemNnnChoiceOption("bad", "Bad target", 0x90000))
                )
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, EmptyAssets)

        assertNotNull(viewModel.uiState.errorMessage)
        assertTrue(viewModel.uiState.errorMessage!!.contains("outside"))
    }

    private fun testScript() = SystemNnnScript(
        fileSizeBytes = 0x5F17C,
        firstHeaderWord = 20u,
        offsetTables = listOf(
            SystemNnnOffsetTable("main", 1299u, 0xC5BAu),
            SystemNnnOffsetTable("secondary", 1124u, 0x167F2u),
            SystemNnnOffsetTable("tertiary", 69u, 0xC390u),
            SystemNnnOffsetTable("auxiliary", 2u, 0u)
        ),
        commandStartOffset = 0xC5BA,
        commandEndOffset = 0x32B20,
        textPoolOffset = 0x32B64,
        textPool = listOf(
            SystemNnnTextEntry(0x32B64, "First line"),
            SystemNnnTextEntry(0x32B70, "Choose a path"),
            SystemNnnTextEntry(0x32B7F, "Left branch")
        ),
        commandBytes = ByteArray(0x32B20 - 0xC5BA)
    )

    private class FakeDecoder(
        private val commands: Map<Long, SystemNnnDecodedCommand>
    ) : SystemNnnCommandDecoder {
        var lastRequestedBranch: String? = null

        override fun decodeNext(
            script: SystemNnnScript,
            commandOffset: Long
        ): SystemNnnDecodedCommand {
            if (commandOffset == 0xC5BDL) lastRequestedBranch = "left"
            return commands[commandOffset] ?: throw IOException("No fake command at " + commandOffset)
        }
    }

    private object EmptyAssets : SystemNnnSceneAssetResolver {
        override fun loadBitmap(resourceId: String) = null
        override fun close() = Unit
    }

    private object IndexRecordingAssets : SystemNnnSceneAssetResolver {
        var lastRequestedIndex: Long? = null
        var lastRequestedName: String? = null

        override fun loadBitmap(resourceId: String): android.graphics.Bitmap? {
            lastRequestedName = resourceId
            return null
        }

        override fun loadBitmapByIndex(index: Long): android.graphics.Bitmap? {
            lastRequestedIndex = index
            return null
        }

        override fun close() = Unit
    }
}
