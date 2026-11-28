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
    fun drawFramesDriveOverrapProgressAndReleaseSnapshotsAtDuration() {
        var state = SystemNnnPlayerUiState(
            pendingDrawFrameCount = 1,
            overrapTransitionId = 1L,
            overrapTransitionType = 0,
            overrapTransitionDurationFrames = 60,
            overrapProgress = 0f
        )

        state = state.advanceOverrapForNativeDrawFrame()
        assertEquals(1, state.overrapFramesElapsed)
        assertEquals(1f / 60f, state.overrapProgress, 0.0001f)

        // DRAW(1) completes; the following DRAW(60) is the next native frame block.
        state = state.copy(pendingDrawFrameCount = null).copy(pendingDrawFrameCount = 60)
        repeat(60) { state = state.advanceOverrapForNativeDrawFrame() }

        assertEquals(60, state.overrapFramesElapsed)
        assertEquals(1f, state.overrapProgress, 0f)
        assertEquals(0, state.overrapTransitionDurationFrames)
        assertNull(state.overrapFromBitmap)
        assertNull(state.overrapToBitmap)
        assertFalse(SystemNnnVisualEffectMath.shouldRenderOverrapFrame(
            state.overrapFromBitmap != null,
            state.overrapToBitmap != null
        ))

        assertEquals(
            listOf(1, 6, 7, 8, 9, 12),
            SystemNnnVisualEffectMath.nativeLayerRenderOrder(
                bitmapLayerIndices = listOf(1, 6, 7, 8, 9, 12),
                effectLayers = emptyList()
            )
        )
    }

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
                    nextCommandOffset = offsets[2] + 12L,
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
    fun loadDwqRequestsTheNamedResourceAndMissingArtworkDoesNotStopDialogue() {
        val offsets = recordOffsets()
        val assets = IndexRecordingAssets
        assets.lastRequestedName = null
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.LoadImageLayer(
                    nextCommandOffset = offsets[1],
                    layerIndex = 4,
                    resourceId = "bg\\missing_scene"
                ),
                offsets[1] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[2],
                    textEntryOffset = textOffset(0)
                )
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, assets)

        assertEquals("bg\\missing_scene", assets.lastRequestedName)
        assertEquals("First line", viewModel.uiState.dialogueText)
        assertNull(viewModel.uiState.errorMessage)
        assertTrue(viewModel.uiState.statusMessage!!.contains("missing_scene"))
        assertFalse(viewModel.uiState.isLoading)
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
    fun explicitNativeImageBankRowIsForwardedWithoutUsingTheAggregateIndexSpace() {
        val offsets = recordOffsets()
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.SceneUpdate(
                    nextCommandOffset = offsets[1],
                    backgroundResourceIndex = 17L,
                    backgroundBankNamespace = "sc",
                    backgroundBankSlot = 0
                ),
                offsets[1] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[2],
                    textEntryOffset = textOffset(0)
                )
            )
        )
        val assets = IndexRecordingAssets
        assets.lastNativeBankIndexRequest = null
        assets.lastRequestedIndex = null

        SystemNnnPlayerViewModel().start(testScript(), decoder, assets)

        assertEquals(Triple("sc", 0, 17L), assets.lastNativeBankIndexRequest)
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
    fun scriptTransferPushesCallerAndNextReturnsToItsContinuation() {
        val sysScript = testScript()
        val mainScript = testScript()
        val reader = io.github.helterfall.ramen.parser.SystemNnnCommandStreamReader()
        val sysStream = reader.read(sysScript)
        val mainStream = reader.read(mainScript)
        val sysOffsets = sysStream.records.map { it.offset }
        val mainOffset = mainStream.recordStartOffset
        val sysDecoder = FakeDecoder(
            mapOf(
                sysOffsets[0] to SystemNnnDecodedCommand.ModuleTransfer(
                    nextCommandOffset = sysOffsets[1],
                    targetModuleName = "main",
                    kind = SystemNnnModuleTransferKind.SCRIPT
                ),
                sysOffsets[1] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = sysOffsets[2],
                    textEntryOffset = textOffset(0)
                )
            )
        )
        val mainDecoder = FakeDecoder(mapOf(mainOffset to SystemNnnDecodedCommand.ModuleReturn))
        val programs = listOf(
            SystemNnnPlayerModuleProgram("sys", sysScript, sysStream, sysStream.recordStartOffset),
            SystemNnnPlayerModuleProgram("main", mainScript, mainStream, mainStream.recordStartOffset)
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.startProject(
            modules = programs,
            controlModuleName = "sys",
            assetResolver = EmptyAssets,
            decoderFactory = { program ->
                when (program.name) {
                    "sys" -> sysDecoder
                    "main" -> mainDecoder
                    else -> error("Unexpected module ${program.name}")
                }
            }
        )

        assertEquals("First line", viewModel.uiState.dialogueText)
        assertNull(viewModel.uiState.errorMessage)
        assertEquals(listOf(sysOffsets[0], sysOffsets[1]), sysDecoder.requestedOffsets)
        assertEquals(listOf(mainOffset), mainDecoder.requestedOffsets)
    }

    @Test
    fun subscriptTransferAlsoReturnsToTheSavedCaller() {
        val sysScript = testScript()
        val childScript = testScript()
        val reader = io.github.helterfall.ramen.parser.SystemNnnCommandStreamReader()
        val sysStream = reader.read(sysScript)
        val childStream = reader.read(childScript)
        val sysOffsets = sysStream.records.map { it.offset }
        val childOffset = childStream.recordStartOffset
        val sysDecoder = FakeDecoder(
            mapOf(
                sysOffsets[0] to SystemNnnDecodedCommand.ModuleTransfer(
                    nextCommandOffset = sysOffsets[1],
                    targetModuleName = "child",
                    kind = SystemNnnModuleTransferKind.SUBSCRIPT
                ),
                sysOffsets[1] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = sysOffsets[2],
                    textEntryOffset = textOffset(0)
                )
            )
        )
        val childDecoder = FakeDecoder(mapOf(childOffset to SystemNnnDecodedCommand.ModuleReturn))
        val programs = listOf(
            SystemNnnPlayerModuleProgram("sys", sysScript, sysStream, sysStream.recordStartOffset),
            SystemNnnPlayerModuleProgram("child", childScript, childStream, childStream.recordStartOffset)
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.startProject(
            modules = programs,
            controlModuleName = "sys",
            assetResolver = EmptyAssets,
            decoderFactory = { program -> if (program.name == "sys") sysDecoder else childDecoder }
        )

        assertEquals("First line", viewModel.uiState.dialogueText)
        assertNull(viewModel.uiState.errorMessage)
        assertEquals(listOf(sysOffsets[0], sysOffsets[1]), sysDecoder.requestedOffsets)
        assertEquals(listOf(childOffset), childDecoder.requestedOffsets)
    }

    @Test
    fun scriptJumpReplacesTheCurrentModuleWithoutReturningToTheOldContinuation() {
        val sysScript = testScript()
        val jumpScript = testScript()
        val reader = io.github.helterfall.ramen.parser.SystemNnnCommandStreamReader()
        val sysStream = reader.read(sysScript)
        val jumpStream = reader.read(jumpScript)
        val sysOffsets = sysStream.records.map { it.offset }
        val jumpOffsets = jumpStream.records.map { it.offset }
        val sysDecoder = FakeDecoder(
            mapOf(
                sysOffsets[0] to SystemNnnDecodedCommand.ModuleTransfer(
                    nextCommandOffset = sysOffsets[1],
                    targetModuleName = "jump",
                    kind = SystemNnnModuleTransferKind.SCRIPTJUMP
                ),
                sysOffsets[1] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = sysOffsets[2],
                    textEntryOffset = textOffset(0)
                )
            )
        )
        val jumpDecoder = FakeDecoder(
            mapOf(
                jumpOffsets[0] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = jumpOffsets[1],
                    textEntryOffset = textOffset(2)
                )
            )
        )
        val programs = listOf(
            SystemNnnPlayerModuleProgram("sys", sysScript, sysStream, sysStream.recordStartOffset),
            SystemNnnPlayerModuleProgram("jump", jumpScript, jumpStream, jumpStream.recordStartOffset)
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.startProject(
            modules = programs,
            controlModuleName = "sys",
            assetResolver = EmptyAssets,
            decoderFactory = { program -> if (program.name == "sys") sysDecoder else jumpDecoder }
        )

        assertEquals("Left branch", viewModel.uiState.dialogueText)
        assertNull(viewModel.uiState.errorMessage)
        assertEquals(listOf(sysOffsets[0]), sysDecoder.requestedOffsets)
        assertEquals(listOf(jumpOffsets[0]), jumpDecoder.requestedOffsets)
    }

    @Test
    fun warnsWhenChangeEffectDoesNotNameItsSecondLayer() {
        val offsets = recordOffsets()
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = offsets[1],
                    layerIndex = 0,
                    effectId = 40,
                    parameters = listOf(20)
                ),
                offsets[1] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[2],
                    textEntryOffset = textOffset(0)
                )
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, EmptyAssets)

        assertEquals("First line", viewModel.uiState.dialogueText)
        assertTrue(viewModel.uiState.statusMessage?.contains("(40)") == true)
        assertTrue(viewModel.uiState.statusMessage?.contains("second-layer operand") == true)
    }


    @Test
    fun warnsWhenChangeEffectReferencesItsOwnLayer() {
        val offsets = recordOffsets()
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = offsets[1],
                    layerIndex = 0,
                    effectId = 40,
                    parameters = listOf(20, 0)
                ),
                offsets[1] to SystemNnnDecodedCommand.LoadImageLayer(
                    nextCommandOffset = offsets[2],
                    layerIndex = 0,
                    resourceId = "self-change"
                ),
                offsets[2] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[3],
                    textEntryOffset = textOffset(0)
                )
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, EmptyAssets)

        assertTrue(viewModel.uiState.statusMessage?.contains("same layer") == true)
        assertNull(viewModel.uiState.errorMessage)
    }

    @Test
    fun warnsWhenMoveEffectHasNoDestinationRectangle() {
        val offsets = recordOffsets()
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = offsets[1],
                    layerIndex = 0,
                    effectId = 52
                ),
                offsets[1] to SystemNnnDecodedCommand.LoadImageLayer(
                    nextCommandOffset = offsets[2],
                    layerIndex = 0,
                    resourceId = "character"
                ),
                offsets[2] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[3],
                    textEntryOffset = textOffset(0)
                )
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, EmptyAssets)

        assertEquals("First line", viewModel.uiState.dialogueText)
        assertTrue(viewModel.uiState.statusMessage?.contains("(52)") == true)
        assertTrue(viewModel.uiState.statusMessage?.contains("SETEFFECTRECT") == true)
    }

    @Test
    fun maeContinuesThePreviousNativeEffectAndNativeClearStillHidesThePicture() {
        val lengths = List(8) { 3 }
        val offsets = recordOffsets(lengths)
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.LoadImageLayer(
                    nextCommandOffset = offsets[1],
                    layerIndex = 1,
                    resourceId = "ev0601a"
                ),
                offsets[1] to SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = offsets[2],
                    layerIndex = 1,
                    effectId = 1
                ),
                offsets[2] to SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = offsets[3],
                    layerIndex = 7,
                    effectId = 27,
                    parameters = listOf(1, 32, 32, 16, 0, 10, 5, 1, 12, 81, 4, 120)
                ),
                offsets[3] to SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = offsets[4],
                    layerIndex = 7,
                    effectId = 1
                ),
                offsets[4] to SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = offsets[5],
                    layerIndex = 9,
                    effectId = 8,
                    parameters = listOf(20, 56)
                ),
                offsets[5] to SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = offsets[6],
                    layerIndex = 9,
                    effectId = 1
                ),
                offsets[6] to SystemNnnDecodedCommand.ClearImageLayer(
                    nextCommandOffset = offsets[7],
                    layerIndex = 1
                ),
                offsets[7] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets.last() + 12L,
                    textEntryOffset = testTextStartOffset(lengths)
                )
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(lengths), decoder, EmptyAssets)

        assertTrue(viewModel.uiState.sprites.none { it.layerIndex == 1 })
        assertEquals(27, viewModel.uiState.layerEffects.single { it.layerIndex == 7 }.effectId)
        assertEquals(8, viewModel.uiState.layerEffects.single { it.layerIndex == 9 }.effectId)
        assertNull(viewModel.uiState.errorMessage)
    }

    @Test
    fun flashWithoutAPictureIsPublishedAsAnActiveNativeLayerEffect() {
        val offsets = recordOffsets()
        val parameters = listOf(40, 2, 21, 0, 0, 0)
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = offsets[1],
                    layerIndex = 13,
                    effectId = 17,
                    parameters = parameters
                ),
                offsets[1] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[2],
                    textEntryOffset = textOffset(0)
                )
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, EmptyAssets)

        assertTrue(viewModel.uiState.sprites.none { it.layerIndex == 13 })
        val flash = viewModel.uiState.layerEffects.single { it.layerIndex == 13 }
        assertEquals(17, flash.effectId)
        assertEquals(parameters, flash.parameters)
        assertNull(viewModel.uiState.errorMessage)
    }

    @Test
    fun stretchBltAndCloudOutputsSurviveSourceLayerClearAsBufferedContributions() {
        val store = SystemNnnRetainedLayerBufferStore()
        val stretchParameters = listOf(300, 0, 0, 100, 100, 101, 101, 5, 2, 0, 100, 100, 0, 0)
        val stretch = SystemNnnSpriteReference(
            resourceId = "ev0601a",
            layerIndex = 1,
            nativeEffectId = 65,
            nativeEffectParameters = stretchParameters,
            nativeEffectCommandOffset = 0x0ac0L
        )

        assertTrue(store.retainOnClear(stretch, 65, stretchParameters, 0x0ac0L))
        assertFalse(store.retainOnClear(stretch, 1, emptyList(), null))
        assertEquals(65, store.snapshot()[1]?.nativeEffectId)
        assertEquals(stretchParameters, store.snapshot()[1]?.nativeEffectParameters)

        val cloudParameters = listOf(0, 300, 13, 4, 9_999, 200, 1362, 200, 0, 1, 255, 160, 110, 50)
        val cloud = SystemNnnSpriteReference(
            resourceId = "bgkhy",
            layerIndex = 1,
            nativeEffectId = 82,
            nativeEffectParameters = cloudParameters,
            nativeEffectCommandOffset = 0x13b4L
        )
        assertTrue(store.retainOnClear(cloud, 82, cloudParameters, 0x13b4L))
        assertEquals(82, store.snapshot()[1]?.nativeEffectId)
        assertEquals("bgkhy", store.snapshot()[1]?.resourceId)
        assertEquals(cloudParameters, store.snapshot()[1]?.nativeEffectParameters)

        assertFalse(
            store.retainOnClear(
                cloud.copy(nativeEffectId = 66, nativeEffectParameters = emptyList()),
                66, emptyList(), 0x1400L
            )
        )
        assertEquals(82, store.snapshot()[1]?.nativeEffectId)
    }

    @Test
    fun bitmaplessIntroEffectsStayOnTheirNativeLayersInsteadOfBecomingGlobalOverlays() {
        val lengths = List(6) { 3 }
        val offsets = recordOffsets(lengths)
        val fadeParameters = listOf(60, 0, 0, 0, 0)
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = offsets[1],
                    layerIndex = 0,
                    effectId = 54
                ),
                offsets[1] to SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = offsets[2],
                    layerIndex = 6,
                    effectId = 55,
                    parameters = listOf(20, 0, 0, 800, 600, 5, 1, 21, 19, 9, 200, 80, 30)
                ),
                offsets[2] to SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = offsets[3],
                    layerIndex = 8,
                    effectId = 29,
                    parameters = listOf(0, 1, 10, 30, 5, 60, 0, 0, 800, 600)
                ),
                offsets[3] to SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = offsets[4],
                    layerIndex = 11,
                    effectId = 10,
                    parameters = fadeParameters
                ),
                offsets[4] to SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = offsets[5],
                    layerIndex = 13,
                    effectId = 17,
                    parameters = listOf(40, 2, 21, 0, 0, 0)
                ),
                offsets[5] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets.last() + 12L,
                    textEntryOffset = testTextStartOffset(lengths)
                )
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(lengths), decoder, EmptyAssets)

        assertEquals(
            listOf(0 to 54, 6 to 55, 8 to 29, 11 to 10, 13 to 17),
            viewModel.uiState.layerEffects.map { it.layerIndex to it.effectId }
        )
        assertNull(viewModel.uiState.screenEffectId)
        assertTrue(viewModel.uiState.sprites.isEmpty())
        assertNull(viewModel.uiState.errorMessage)
    }

    @Test
    fun haikeiYureLayerTypeOneStaysOnNativeLayerTenInsteadOfScreenBufferLayerThirtyOne() {
        val lengths = List(3) { 3 }
        val offsets = recordOffsets(lengths)
        val parameters = listOf(9_999, 2, 1, 7, 26, 1, 0, 0, 0, 0, 0, 1, 4, 0)
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = offsets[1],
                    layerIndex = 10,
                    effectId = 100,
                    parameters = parameters
                ),
                offsets[1] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[2],
                    textEntryOffset = testTextStartOffset(lengths)
                )
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(lengths), decoder, EmptyAssets)

        assertEquals(listOf(10 to 100), viewModel.uiState.layerEffects.map { it.layerIndex to it.effectId })
        assertNull(viewModel.uiState.screenEffectId)
        assertNull(viewModel.uiState.screenEffectLayerIndex)
        assertTrue(SystemNnnVisualEffectMath.isInlineLayerStackScreenEffect(100, parameters))
        assertFalse(SystemNnnVisualEffectMath.requiresNativeScreenBufferCapture(100, parameters))
        assertNull(viewModel.uiState.errorMessage)
    }

    @Test
    fun screenLayerFadeIsPublishedAsAViewportEffect() {
        val offsets = recordOffsets()
        val parameters = listOf(24, 16, 32, 48, 0, 0)
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = offsets[1],
                    layerIndex = 31,
                    effectId = 10,
                    parameters = parameters
                ),
                offsets[1] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[2],
                    textEntryOffset = textOffset(0)
                )
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, EmptyAssets)

        assertEquals(10, viewModel.uiState.screenEffectId)
        assertEquals(parameters, viewModel.uiState.screenEffectParameters)
        assertNull(viewModel.uiState.errorMessage)
    }

    @Test
    fun clearingTheScreenLayerRemovesItsActiveViewportEffect() {
        val offsets = recordOffsets()
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = offsets[1],
                    layerIndex = 31,
                    effectId = 11,
                    parameters = listOf(12, 0, 0, 0, 0, 0)
                ),
                offsets[1] to SystemNnnDecodedCommand.ClearImageLayer(
                    nextCommandOffset = offsets[2],
                    layerIndex = 31
                ),
                offsets[2] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[3],
                    textEntryOffset = textOffset(0)
                )
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, EmptyAssets)

        assertNull(viewModel.uiState.screenEffectId)
        assertTrue(viewModel.uiState.screenEffectParameters.isEmpty())
    }

    @Test
    fun clearingAllImageLayersAlsoClearsTheViewportEffect() {
        val offsets = recordOffsets()
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = offsets[1],
                    layerIndex = 31,
                    effectId = 18,
                    parameters = listOf(8, 0, 0)
                ),
                offsets[1] to SystemNnnDecodedCommand.ClearAllImageLayers(
                    nextCommandOffset = offsets[2]
                ),
                offsets[2] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[3],
                    textEntryOffset = textOffset(0)
                )
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(), decoder, EmptyAssets)

        assertNull(viewModel.uiState.screenEffectId)
    }

    @Test
    fun nativeSystemFunctionsUpdateRuntimeStateAndGatePlayerInput() {
        val offsets = recordOffsets(List(12) { 3 })
        val commandMap = linkedMapOf<Long, SystemNnnDecodedCommand>()
        val functions = listOf(
            0x001B to listOf(42, 1),
            0x001C to listOf(42, 3, 1),
            0x0018 to listOf(2, 7, -1),
            0x0057 to listOf(-1),
            0x0058 to listOf(1),
            0x005A to listOf(4),
            0x001E to listOf(2),
            0x0059 to listOf(1),
            0x0017 to emptyList(),
            0x0053 to listOf(3),
            0x0028 to listOf(75)
        )
        functions.forEachIndexed { index, entry ->
            commandMap[offsets[index]] = SystemNnnDecodedCommand.NativeSystemFunction(
                nextCommandOffset = offsets[index + 1],
                functionId = entry.first,
                parameters = entry.second
            )
        }
        val textStart = testTextStartOffset(List(12) { 3 })
        commandMap[offsets[11]] = SystemNnnDecodedCommand.Dialogue(
            nextCommandOffset = offsets.last() + 12L,
            textEntryOffset = textStart
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(
            script = testScript(List(12) { 3 }),
            commandDecoder = FakeDecoder(commandMap),
            assetResolver = EmptyAssets
        )

        val native = viewModel.uiState.nativeRuntime
        assertEquals("First line", viewModel.uiState.dialogueText)
        assertEquals(42, native.lastMessageId)
        assertEquals(3, native.nextMessageType)
        assertEquals(-1, native.nextMessageWindowPrintMode)
        assertTrue(native.messageWindowPrintFlag)
        assertFalse(native.messageHaveVoiceFlag)
        assertEquals(setOf(1 to 6), native.cgFlags)
        assertEquals(0, native.autoMessage)
        assertEquals(1, native.cannotClick)
        assertEquals(1, native.cannotSkip)
        assertEquals(4, native.optionOff)
        assertEquals(0, native.dontPlayMode)
        assertEquals(3, native.configMask)
        assertEquals(75, native.frameTimeMillis)

        viewModel.onScreenTap()
        assertFalse(viewModel.uiState.isTextComplete)
    }

    @Test
    fun startMessageWithoutParametersPreservesPendingMessageWindowMode() {
        val lengths = List(3) { 3 }
        val offsets = recordOffsets(lengths)
        val textStart = testTextStartOffset(lengths)
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.NativeSystemFunction(
                    nextCommandOffset = offsets[1],
                    functionId = 0x001B,
                    parameters = listOf(42, 1)
                ),
                offsets[1] to SystemNnnDecodedCommand.NativeSystemFunction(
                    nextCommandOffset = offsets[2],
                    functionId = 0x001B,
                    parameters = emptyList()
                ),
                offsets[2] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[2] + 12L,
                    textEntryOffset = textStart
                )
            )
        )
        val viewModel = SystemNnnPlayerViewModel()

        viewModel.start(testScript(lengths), decoder, EmptyAssets)

        assertEquals("First line", viewModel.uiState.dialogueText)
        assertFalse(viewModel.uiState.nativeRuntime.messageWindowPrintFlag)
        assertEquals(-1, viewModel.uiState.nativeRuntime.nextMessageWindowPrintMode)
    }

    @Test
    fun autoMessageBlocksManualInputButAdvancesAfterItsNativeTimer() {
        val lengths = List(4) { 3 }
        val offsets = recordOffsets(lengths)
        val textStart = testTextStartOffset(lengths)
        val decoder = FakeDecoder(
            mapOf(
                offsets[0] to SystemNnnDecodedCommand.NativeSystemFunction(
                    nextCommandOffset = offsets[1],
                    functionId = 0x0057,
                    parameters = listOf(1)
                ),
                offsets[1] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[2],
                    textEntryOffset = textStart
                ),
                offsets[2] to SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = offsets[3],
                    textEntryOffset = textStart + 0x0CL
                ),
                offsets[3] to SystemNnnDecodedCommand.End
            )
        )
        val viewModel = SystemNnnPlayerViewModel()
        viewModel.start(testScript(lengths), decoder, EmptyAssets)

        assertEquals(1, viewModel.uiState.nativeRuntime.autoMessage)
        viewModel.onScreenTap()
        assertFalse(viewModel.uiState.isTextComplete)
        viewModel.onTypewriterFinished()
        viewModel.onScreenTap()
        assertEquals("First line", viewModel.uiState.dialogueText)

        viewModel.onAutoMessageTimerElapsed()
        assertEquals("Choose a path", viewModel.uiState.dialogueText)
    }

    @Test
    fun setDemoFlagRuntimeCanToggleTheNativeGlobalAutoParameter() {
        val viewModel = SystemNnnPlayerViewModel()
        viewModel.setNativeAutoMode(true)
        assertEquals(1, viewModel.uiState.nativeRuntime.dontPlayMode)
        viewModel.setNativeAutoMode(false)
        assertEquals(0, viewModel.uiState.nativeRuntime.dontPlayMode)
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
        val requestedOffsets = mutableListOf<Long>()

        override fun decodeNext(
            script: SystemNnnScript,
            commandOffset: Long
        ): SystemNnnDecodedCommand {
            requestedOffsets += commandOffset
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
        var lastNativeBankIndexRequest: Triple<String, Int, Long>? = null

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

        override fun loadBitmapByNativeBankIndex(
            namespace: String,
            slot: Int,
            index: Long
        ): android.graphics.Bitmap? {
            lastNativeBankIndexRequest = Triple(namespace, slot, index)
            return null
        }

        override fun close() = Unit
    }
}
