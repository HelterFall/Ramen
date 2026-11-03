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
                    nextCommandOffset = 0xC5BE,
                    textEntryOffset = 0x32B64,
                    speakerName = "Guide"
                ),
                0xC5BEL to SystemNnnDecodedCommand.Choice(
                    promptTextEntryOffset = 0x32B70,
                    options = listOf(
                        SystemNnnChoiceOption("left", "Take the left path", 0xC5C2),
                        SystemNnnChoiceOption("right", "Take the right path", 0xC5C6)
                    )
                ),
                0xC5C2L to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = 0xC5C6,
                    textEntryOffset = 0x32B7F
                ),
                0xC5C6L to SystemNnnDecodedCommand.End
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
    fun pausesOnDialogueAfterSkipAndResumesOnlyAfterExplicitNextTap() {
        val dialogueOffset = 0xC5BAL + 1_300L * 4L
        val commandEnd = 0x32B20L
        val commands = buildMap<Long, SystemNnnDecodedCommand> {
            var offset = 0xC5BAL
            while (offset < dialogueOffset) {
                put(offset, SystemNnnDecodedCommand.Skip(nextCommandOffset = offset + 4L))
                offset += 4L
            }
            put(
                dialogueOffset,
                SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = commandEnd,
                    textEntryOffset = 0x32B64
                )
            )
            put(commandEnd, SystemNnnDecodedCommand.End)
        }
        val decoder = FakeDecoder(commands)
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, EmptyAssets)

        assertEquals("First line", viewModel.uiState.dialogueText)
        assertEquals(NovelState.PlaybackWaiting, viewModel.uiState.novelState)
        assertEquals(1_301, decoder.requestedOffsets.size)
        assertEquals(dialogueOffset, decoder.requestedOffsets.last())

        // First tap completes the typewriter line without advancing bytecode.
        viewModel.onNextTap()
        assertTrue(viewModel.uiState.isTextComplete)
        assertEquals(NovelState.PlaybackWaiting, viewModel.uiState.novelState)
        assertEquals(1_301, decoder.requestedOffsets.size)
        assertEquals(dialogueOffset, decoder.requestedOffsets.last())

        // Only the next explicit tap resumes command execution.
        viewModel.onNextTap()
        assertEquals(1_302, decoder.requestedOffsets.size)
        assertEquals(commandEnd, decoder.requestedOffsets.last())
        assertEquals(NovelState.Ended, viewModel.uiState.novelState)
        assertTrue(viewModel.uiState.isEnded)
    }

    @Test
    fun rejectsDecoderEndBeforeTheDeclaredCommandRegionBoundary() {
        val decoder = FakeDecoder(
            mapOf(0xC5BAL to SystemNnnDecodedCommand.End)
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, EmptyAssets)

        assertFalse(viewModel.uiState.isEnded)
        assertEquals(NovelState.Failed, viewModel.uiState.novelState)
        assertTrue(viewModel.uiState.errorMessage!!.contains("before the declared"))
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
    fun startupTriesFirstTwoGtbRowsBeforeAnySceneCommand() {
        val assets = RecordingAssets
        val decoder = FakeDecoder(
            mapOf(
                0xC5BAL to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = 0xC5BE,
                    textEntryOffset = 0x32B64
                )
            )
        )

        SystemNnnPlayerViewModel().start(testScript(), decoder, assets)

        assertEquals(listOf(0L, 1L), assets.requestedIndexes)
    }

    @Test
    fun sequentialTextPoolAdvancesOnlyAfterTheVisibleLineIsCompletedAndTappedAgain() {
        val decoder = FakeDecoder(
            commands = mapOf(
                0xC5BAL to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = 0xC5BE,
                    textEntryOffset = 0x32B64
                ),
                0xC5BEL to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = 0xC5C2,
                    textEntryOffset = 0x32B70
                ),
                0xC5C2L to SystemNnnDecodedCommand.End
            ),
            usesSequentialTextPool = true
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, EmptyAssets)
        assertEquals("First line", viewModel.uiState.dialogueText)

        viewModel.onScreenTap()
        assertEquals("First line", viewModel.uiState.dialogueText)
        assertTrue(viewModel.uiState.isTextComplete)

        viewModel.onScreenTap()
        assertEquals("Choose a path", viewModel.uiState.dialogueText)
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
        private val commands: Map<Long, SystemNnnDecodedCommand>,
        override val usesSequentialTextPool: Boolean = false
    ) : SystemNnnCommandDecoder {
        var lastRequestedBranch: String? = null
        val requestedOffsets = mutableListOf<Long>()

        override fun decodeNext(
            script: SystemNnnScript,
            commandOffset: Long
        ): SystemNnnDecodedCommand {
            requestedOffsets += commandOffset
            if (commandOffset == 0xC5C2L) lastRequestedBranch = "left"
            return commands[commandOffset] ?: throw IOException("No fake command at " + commandOffset)
        }
    }

    private object EmptyAssets : SystemNnnSceneAssetResolver {
        override fun loadBitmap(resourceId: String) = null
        override fun close() = Unit
    }

    private object RecordingAssets : SystemNnnSceneAssetResolver {
        val requestedIndexes = mutableListOf<Long>()

        override fun loadBitmap(resourceId: String): android.graphics.Bitmap? = null

        override fun loadBitmapByIndex(index: Long): android.graphics.Bitmap? {
            requestedIndexes += index
            return null
        }

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
