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
