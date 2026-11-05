package io.github.helterfall.ramen.parser

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnModuleReferenceReportWriterTest {

    @Test
    fun writesCandidatesDeterministicallyGroupedBySourceFilmWithoutScenarioText() {
        val first = candidate(
            sourceModule = "03",
            targetModule = "yamiko",
            filmNumber = 1,
            tableIndex = 2,
            offset = 0xE0L
        )
        val second = candidate(
            sourceModule = "03",
            targetModule = "akane",
            filmNumber = 0,
            tableIndex = 1,
            offset = 0xA4L
        )
        val transfer = SystemNnnScriptTransferCandidate(
            sourceModuleName = "sys",
            targetModuleName = "op1",
            kind = SystemNnnScriptTransferKind.SCRIPT,
            commandOffset = 0x50L,
            pool2Index = 1u,
            recordLengthWords = 4u,
            sourceFilmTableIndex = null,
            sourceFilmNumber = null,
            sourceFilmStartOffset = null,
            sourceFilmEndOffsetExclusive = null,
            followingRecordLengthWord = 4u,
            previousRecord = null,
            nextRecord = null
        )
        val localScan = SystemNnnLocalSubroutineScanResult(
            candidates = listOf(
                SystemNnnLocalSubroutineCandidate(
                    sourceModuleName = "01",
                    commandOffset = 0x70L,
                    subroutineId = 0,
                    targetTableIndex = 1,
                    targetCommandOffset = 0x90L,
                    targetFunctionCodeId = 0x50,
                    targetFilmNumber = 0,
                    sourceFilmTableIndex = 1,
                    sourceFilmNumber = 0,
                    sourceFilmStartOffset = 0x60L,
                    sourceFilmEndOffsetExclusive = 0xA0L,
                    previousRecord = null,
                    nextRecord = null
                )
            ),
            subroutineIdsAreDense = true,
            missingSubroutineIds = emptyList(),
            duplicateSubroutineIds = emptyList(),
            unresolvedTargetCount = 0
        )
        val output = ByteArrayOutputStream()

        SystemNnnModuleReferenceReportWriter().write(
            listOf(
                SystemNnnModuleReferenceProjectReport(
                    projectPath = "spt/project.xtx",
                    moduleCount = 20,
                    candidates = listOf(first, second),
                    scriptTransfers = listOf(transfer),
                    localSubroutineScan = localScan
                )
            ),
            output
        )

        val report = output.toString(Charsets.UTF_8.name())
        assertTrue(report.indexOf("film=0") < report.indexOf("film=1"))
        assertTrue(report.contains("target=akane"))
        assertTrue(report.contains("target=yamiko"))
        assertTrue(report.contains("code=0x2222/0x0004"))
        assertTrue(report.contains("PREV:"))
        assertTrue(report.contains("NEXT:"))
        assertTrue(report.contains("SCRIPT TRANSFER RECORDS"))
        assertTrue(report.contains("LOCAL SUBROUTINE TABLE TARGETS"))
        assertTrue(report.contains("SUB id=0"))
        assertTrue(report.contains("descriptor3[1]"))
        assertTrue(report.contains("SCRIPT @0x50"))
        assertTrue(report.contains("-> op1"))
        assertTrue(report.contains("UNCLASSIFIED POOL 2 MODULE-NAME REFERENCES"))
        assertTrue(report.contains("Targets below exactly match project.xtx module names"))
        assertFalse(report.contains("Содержимое реплики"))
        assertFalse(report.contains("dialogue body"))
    }

    @Test
    fun includesResourceFunctionMetadataAndNumericPayloadOnly() {
        val candidate = SystemNnnResourceFunctionCandidate(
            sourceModuleName = "03",
            kind = SystemNnnResourceFunctionKind.LOAD_DWQ,
            commandOffset = 0xA0L,
            recordLengthWords = 5u,
            leadingCountWord = 1u,
            remainingPayloadWordCount = 1,
            countWordEqualsRemainingPayloadWordCount = true,
            payloadPreview = listOf(1u, 9u),
            sourceFilmTableIndex = 2,
            sourceFilmNumber = 1,
            sourceFilmStartOffset = 0x80L,
            sourceFilmEndOffsetExclusive = 0xC0L,
            previousRecord = SystemNnnRecordContext(
                commandOffset = 0x90L,
                identifierType = 2,
                codeFamily = 0x5556,
                codeId = 0x0001,
                recordLengthWords = 3u,
                payloadPreview = emptyList()
            ),
            nextRecord = SystemNnnRecordContext(
                commandOffset = 0xB0L,
                identifierType = 7,
                codeFamily = 0x3333,
                codeId = 0x0009,
                recordLengthWords = 4u,
                payloadPreview = listOf(1u)
            )
        )
        val output = ByteArrayOutputStream()

        SystemNnnModuleReferenceReportWriter().write(
            listOf(
                SystemNnnModuleReferenceProjectReport(
                    projectPath = "spt/project.xtx",
                    moduleCount = 20,
                    candidates = emptyList(),
                    resourceFunctionCandidates = listOf(candidate)
                )
            ),
            output
        )

        val report = output.toString(Charsets.UTF_8.name())
        assertTrue(report.contains("RESOURCE/AUDIO SYSTEM_FUNCTION CANDIDATES"))
        assertTrue(report.contains("SHAPE SUMMARY BY FUNCTION CODE"))
        assertTrue(report.contains("LOADDWQ count=1 lengthWords={5:1} remainingPayloadWords={1:1}"))
        assertTrue(report.contains("leadingWord={0x00000001:1}"))
        assertTrue(report.contains("MODULE=03 LOADDWQ @0xA0"))
        assertTrue(report.contains("resource/audio functions: 1"))
        assertTrue(report.contains("count/payload differences: 0"))
        assertTrue(report.contains("countWord=1 remainingPayloadWords=1 countVsPayload=equal"))
        assertTrue(report.contains("payload=[0x00000001,0x00000009]"))
        assertTrue(report.contains("cross-title NNNLib context only"))
        assertTrue(report.contains("SOURCE FILM: film 1 (tableIndex=2)"))
        assertTrue(report.contains("PREV: @0x90"))
        assertTrue(report.contains("NEXT: @0xB0"))
        assertTrue(report.contains("code=0x3333/0x0009"))
    }

    @Test
    fun flushesButDoesNotCloseTheCallerOwnedStream() {
        val output = CloseTrackingOutputStream()
        val candidate = candidate(
            sourceModule = "03",
            targetModule = "yamiko",
            filmNumber = 0,
            tableIndex = 1,
            offset = 0xA4L
        )

        SystemNnnModuleReferenceReportWriter().write(
            listOf(SystemNnnModuleReferenceProjectReport("project.xtx", 1, listOf(candidate))),
            output
        )

        assertTrue(output.flushed)
        assertFalse(output.closed)
        assertTrue(output.size() > 0)
    }

    @Test
    fun includesResourceFunctionsInTheConfiguredCandidateLimit() {
        val candidate = SystemNnnResourceFunctionCandidate(
            sourceModuleName = "03",
            kind = SystemNnnResourceFunctionKind.VOICE,
            commandOffset = 0xA0L,
            recordLengthWords = 4u,
            leadingCountWord = 0u,
            remainingPayloadWordCount = 0,
            countWordEqualsRemainingPayloadWordCount = true,
            payloadPreview = emptyList(),
            sourceFilmTableIndex = null,
            sourceFilmNumber = null,
            sourceFilmStartOffset = null,
            sourceFilmEndOffsetExclusive = null,
            previousRecord = null,
            nextRecord = null
        )
        val writer = SystemNnnModuleReferenceReportWriter(maxCandidates = 1)
        val reports = listOf(
            SystemNnnModuleReferenceProjectReport(
                projectPath = "project.xtx",
                moduleCount = 1,
                candidates = emptyList(),
                resourceFunctionCandidates = listOf(candidate, candidate)
            )
        )

        org.junit.Assert.assertThrows(IOException::class.java) {
            writer.write(reports, ByteArrayOutputStream())
        }
    }

    @Test
    fun rejectsReportsAboveTheConfiguredCandidateLimit() {
        val writer = SystemNnnModuleReferenceReportWriter(maxCandidates = 1)
        val candidate = candidate(
            sourceModule = "03",
            targetModule = "yamiko",
            filmNumber = 0,
            tableIndex = 1,
            offset = 0xA4L
        )
        val reports = listOf(
            SystemNnnModuleReferenceProjectReport("project.xtx", 1, listOf(candidate, candidate))
        )

        org.junit.Assert.assertThrows(IOException::class.java) {
            writer.write(reports, ByteArrayOutputStream())
        }
    }

    private fun candidate(
        sourceModule: String,
        targetModule: String,
        filmNumber: Int,
        tableIndex: Int,
        offset: Long
    ) = SystemNnnModuleReferenceCandidate(
        sourceModuleName = sourceModule,
        targetModuleName = targetModule,
        commandOffset = offset,
        identifierType = 6,
        codeFamily = 0x2222,
        codeId = 0x0004,
        pool2Index = 3u,
        recordLengthWords = 5u,
        sourceFilmTableIndex = tableIndex,
        sourceFilmNumber = filmNumber,
        sourceFilmStartOffset = offset - 0x28L,
        sourceFilmEndOffsetExclusive = offset + 0x28L,
        previousRecord = SystemNnnRecordContext(
            commandOffset = offset - 0x14L,
            identifierType = 7,
            codeFamily = 0x3333,
            codeId = 0x0052,
            recordLengthWords = 5u,
            payloadPreview = listOf(1u, 0x2222u)
        ),
        nextRecord = null
    )

    private class CloseTrackingOutputStream : ByteArrayOutputStream() {
        var closed: Boolean = false
        var flushed: Boolean = false

        override fun close() {
            closed = true
            super.close()
        }

        override fun flush() {
            flushed = true
            super.flush()
        }
    }
}
