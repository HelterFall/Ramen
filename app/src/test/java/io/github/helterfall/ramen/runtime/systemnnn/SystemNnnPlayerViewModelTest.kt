package io.github.helterfall.ramen.runtime.systemnnn

import io.github.helterfall.ramen.parser.SystemNnnScript
import io.github.helterfall.ramen.parser.SystemNnnTextEntry
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnPlayerViewModelTest {

    @Test
    fun waitsForTypewriterThenShowsChoiceAndBranchesToSelectedTarget() {
        val offsets = recordOffsets()
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[1],
                    textEntryOffset = textOffset(0),
                    speakerName = "Guide"
                ),
                offsets[1] to SystemNnnDecodedCommand.Choice(
                    promptTextEntryOffset = textOffset(1),
                    options = listOf(
                        SystemNnnChoiceOption("left", "Take the left path", offsets[2]),
                        SystemNnnChoiceOption("right", "Take the right path", offsets[3])
                    )
                ),
                offsets[2] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[3],
                    textEntryOffset = textOffset(2)
                ),
                offsets[3] to SystemNnnDecodedCommand.End
            ),
            branchOffset = offsets[2]
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, EmptyAssets)

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
    fun skipsUnmappedRecordAndContinuesToExactTextPoolEntry() {
        val offsets = recordOffsets()
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.Skip(nextCommandOffset = offsets[1]),
                offsets[1] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[2],
                    textEntryOffset = textOffset(0)
                )
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, EmptyAssets)

        assertEquals("First line", viewModel.uiState.dialogueText)
        assertNull(viewModel.uiState.errorMessage)
        assertFalse(viewModel.uiState.isLoading)
    }

    @Test
    fun preservesTheCurrentFilmNumberWhenTheNextDialogueIsPublished() {
        val offsets = recordOffsets()
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.SetFilm(
                    nextCommandOffset = offsets[1],
                    filmNumber = 7
                ),
                offsets[1] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[2],
                    textEntryOffset = textOffset(0)
                )
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, EmptyAssets)

        assertEquals(7, viewModel.uiState.currentFilmNumber)
        assertEquals("First line", viewModel.uiState.dialogueText)
    }

    @Test
    fun rejectsSkipThatTargetsTheMiddleOfARecord() {
        val offsets = recordOffsets()
        val decoder = FakeDecoder(
            mapOf(offsets[0] to SystemNnnDecodedCommand.Skip(nextCommandOffset = offsets[0] + 4L))
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, EmptyAssets)

        assertNotNull(viewModel.uiState.errorMessage)
        assertTrue(viewModel.uiState.errorMessage!!.contains("record boundary"))
    }

    @Test
    fun acceptsEndReportedAtAValidatedRecordBoundary() {
        val offsets = recordOffsets()
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(
            testScript(),
            FakeDecoder(mapOf(offsets[0] to SystemNnnDecodedCommand.End)),
            EmptyAssets
        )

        assertTrue(viewModel.uiState.isEnded)
        assertNull(viewModel.uiState.errorMessage)
    }

    @Test
    fun acceptsVariableLengthRecordsAndTheirExactBoundaries() {
        val lengths = listOf(3, 5, 4)
        val offsets = recordOffsets(lengths)
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.Skip(offsets[1]),
                offsets[1] to SystemNnnDecodedCommand.Skip(offsets[2]),
                offsets[2] to SystemNnnDecodedCommand.End
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(lengths), decoder, EmptyAssets)

        assertTrue(viewModel.uiState.isEnded)
        assertNull(viewModel.uiState.errorMessage)
    }

    @Test
    fun requestsSceneBitmapUsingGtbIndexRatherThanRawStringIdentifier() {
        val offsets = recordOffsets()
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.SceneUpdate(
                    nextCommandOffset = offsets[1],
                    backgroundResourceIndex = 3L
                ),
                offsets[1] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[2],
                    textEntryOffset = textOffset(0)
                )
            )
        )
        val assets = IndexRecordingAssets
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, assets)

        assertEquals(3L, assets.lastRequestedIndex)
        assertNull(assets.lastRequestedName)
    }

    @Test
    fun explicitNativeImageBankIdentityIsForwardedWithoutInventingGlobalIndexes() {
        val offsets = recordOffsets()
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.SceneUpdate(
                    nextCommandOffset = offsets[1],
                    backgroundResourceId = "bg-token-01",
                    backgroundBankNamespace = "bg",
                    backgroundBankSlot = 0
                ),
                offsets[1] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[2],
                    textEntryOffset = textOffset(0)
                )
            )
        )
        val assets = IndexRecordingAssets
        assets.lastNativeBankRequest = null
        assets.lastRequestedIndex = null

        SystemNnnPlayerViewModel().start(testScript(), decoder, assets)

        assertEquals(Triple("bg", 0, "bg-token-01"), assets.lastNativeBankRequest)
        assertNull(assets.lastRequestedIndex)
    }

    @Test
    fun dialogueLoadsItsSynchronizedGtbBackgroundIndex() {
        val offsets = recordOffsets()
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[1],
                    textEntryOffset = textOffset(0),
                    backgroundResourceIndex = 4L,
                    dialogueStepIndex = 4L
                )
            )
        )
        val assets = IndexRecordingAssets
        assets.lastRequestedIndex = null

        SystemNnnPlayerViewModel().start(testScript(), decoder, assets)

        assertEquals(4L, assets.lastRequestedIndex)
    }

    @Test
    fun decodesOnlyTheLastSceneBackgroundBeforeTheVisibleDialogue() {
        val offsets = recordOffsets()
        val assets = RecordingAssets
        assets.requestedIndexes.clear()
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.SceneUpdate(
                    nextCommandOffset = offsets[1],
                    backgroundResourceIndex = 1L
                ),
                offsets[1] to SystemNnnDecodedCommand.SceneUpdate(
                    nextCommandOffset = offsets[2],
                    backgroundResourceIndex = 2L
                ),
                offsets[2] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[3],
                    textEntryOffset = textOffset(0)
                )
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, assets)

        assertEquals(listOf(2L), assets.requestedIndexes)
        assertTrue(viewModel.uiState.errorMessage!!.contains("index 2"))
    }

    @Test
    fun startupDoesNotLoadUnreferencedGtbRows() {
        val offsets = recordOffsets()
        val assets = RecordingAssets
        assets.requestedIndexes.clear()
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[1],
                    textEntryOffset = textOffset(0)
                )
            )
        )

        SystemNnnPlayerViewModel().start(testScript(), decoder, assets)

        assertTrue(assets.requestedIndexes.isEmpty())
    }

    @Test
    fun sequentialTextPoolAdvancesOnlyAfterTheVisibleLineIsCompletedAndTappedAgain() {
        val offsets = recordOffsets()
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[1],
                    textEntryOffset = textOffset(0)
                ),
                offsets[1] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[2],
                    textEntryOffset = textOffset(1)
                ),
                offsets[2] to SystemNnnDecodedCommand.End
            )
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
    fun missingDecoderShowsAnInformationalStatusInsteadOfAnError() {
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), null, EmptyAssets)

        assertNotNull(viewModel.uiState.statusMessage)
        assertTrue(viewModel.uiState.statusMessage!!.contains("декодер команд"))
        assertTrue(viewModel.uiState.statusMessage!!.contains("командных записей"))
        assertNull(viewModel.uiState.errorMessage)
        assertFalse(viewModel.uiState.isLoading)
    }

    @Test
    fun malformedCommandStreamIsReportedAsAStartupError() {
        val malformed = SystemNnnScript(
            fileSizeBytes = 0x60L,
            firstHeaderWord = 0u,
            offsetTables = emptyList(),
            commandStartOffset = 0x40L,
            commandEndOffset = 0x48L,
            textPoolOffset = 0x48L,
            textPool = emptyList(),
            commandBytes = ByteArray(8)
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(malformed, null, EmptyAssets)

        assertFalse(viewModel.uiState.isLoading)
        assertNull(viewModel.uiState.statusMessage)
        assertNotNull(viewModel.uiState.errorMessage)
    }

    @Test
    fun startupFailuresRemainErrorsRatherThanInformationalStatuses() {
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.showStartupError("Не удалось открыть папку игры.")

        assertEquals("Не удалось открыть папку игры.", viewModel.uiState.errorMessage)
        assertNull(viewModel.uiState.statusMessage)
    }

    @Test
    fun invalidChoiceTargetIsRejected() {
        val offsets = recordOffsets()
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.Choice(
                    promptTextEntryOffset = textOffset(1),
                    options = listOf(SystemNnnChoiceOption("bad", "Bad target", 0x90000L))
                )
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, EmptyAssets)

        assertNotNull(viewModel.uiState.errorMessage)
        assertTrue(viewModel.uiState.errorMessage!!.contains("outside"))
    }

    private fun recordOffsets(lengths: List<Int> = List(6) { 3 }): List<Long> {
        var cursor = 0x50L
        return lengths.map { length ->
            val offset = cursor
            cursor += length * 4L
            offset
        }
    }

    private fun textOffset(index: Int): Long =
        testTextStartOffset() + index * 0x0CL

    private fun testTextStartOffset(recordWordLengths: List<Int> = List(6) { 3 }): Long {
        val commandByteCount = 16L + recordWordLengths.sumOf { it.toLong() * 4L } + 16L
        return 0x40L + commandByteCount + 0x10L
    }

    private fun testScript(recordWordLengths: List<Int> = List(6) { 3 }): SystemNnnScript {
        val words = ArrayList<UInt>()
        repeat(4) { words += 0u }
        recordWordLengths.forEach { length ->
            require(length >= 3)
            words += length.toUInt()
            words += 0x66660002u
            words += 0x55560001u
            repeat(length - 3) { words += 0u }
        }
        repeat(4) { words += 0u }

        val commandBytes = ByteArray(words.size * 4)
        words.forEachIndexed { wordIndex, word ->
            repeat(4) { byteIndex ->
                commandBytes[wordIndex * 4 + byteIndex] =
                    ((word shr (byteIndex * 8)) and 0xFFu).toByte()
            }
        }

        val commandStart = 0x40L
        val commandEnd = commandStart + commandBytes.size
        val firstTextOffset = commandEnd + 0x10L
        return SystemNnnScript(
            fileSizeBytes = firstTextOffset + 0x40L,
            firstHeaderWord = 20u,
            offsetTables = emptyList(),
            commandStartOffset = commandStart,
            commandEndOffset = commandEnd,
            textPoolOffset = firstTextOffset,
            textPool = listOf(
                SystemNnnTextEntry(firstTextOffset, "First line"),
                SystemNnnTextEntry(firstTextOffset + 0x0CL, "Choose a path"),
                SystemNnnTextEntry(firstTextOffset + 0x18L, "Left branch")
            ),
            commandBytes = commandBytes
        )
    }

    private class FakeDecoder(
        private val commands: Map<Long, SystemNnnDecodedCommand>,
        private val branchOffset: Long? = null
    ) : SystemNnnCommandDecoder {
        var lastRequestedBranch: String? = null

        override fun decodeNext(
            script: SystemNnnScript,
            commandOffset: Long
        ): SystemNnnDecodedCommand {
            if (commandOffset == branchOffset) lastRequestedBranch = "left"
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
        var lastNativeBankRequest: Triple<String, Int, String>? = null

        override fun loadBitmap(resourceId: String): android.graphics.Bitmap? {
            lastRequestedName = resourceId
            return null
        }

        override fun loadBitmapByIndex(index: Long): android.graphics.Bitmap? {
            lastRequestedIndex = index
            return null
        }

        override fun loadBitmapFromNativeBank(
            namespace: String,
            slot: Int,
            resourceId: String
        ): android.graphics.Bitmap? {
            lastNativeBankRequest = Triple(namespace, slot, resourceId)
            return null
        }

        override fun close() = Unit
    }
}
