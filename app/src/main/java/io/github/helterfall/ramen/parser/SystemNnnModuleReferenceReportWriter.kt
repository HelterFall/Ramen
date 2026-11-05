package io.github.helterfall.ramen.parser

import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale

/** One project manifest and the read-only module-name references found in its SPT files. */
data class SystemNnnModuleReferenceProjectReport(
    val projectPath: String,
    val moduleCount: Int,
    val candidates: List<SystemNnnModuleReferenceCandidate>,
    val warnings: List<String> = emptyList(),
    val scriptTransfers: List<SystemNnnScriptTransferCandidate> = emptyList(),
    val unresolvedScriptTransferTargetCount: Int = 0,
    val localSubroutineScan: SystemNnnLocalSubroutineScanResult? = null,
    val resourceFunctionCandidates: List<SystemNnnResourceFunctionCandidate> = emptyList()
)

/**
 * Deterministic UTF-8 export of candidate module-name references.
 *
 * The report contains module identifiers, offsets, record codes and bounded
 * opaque DWORD previews only. It never writes Pool 1 text, Pool 2 text other
 * than exact project module names, or resource payloads. The caller owns the
 * output stream; this writer flushes but does not close it.
 */
class SystemNnnModuleReferenceReportWriter(
    private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES
) {
    init {
        require(maxCandidates > 0) { "maxCandidates must be positive." }
    }

    @Throws(IOException::class)
    fun write(
        reports: Collection<SystemNnnModuleReferenceProjectReport>,
        output: OutputStream
    ) {
        val totalCandidates = reports.sumOf {
            it.candidates.size.toLong() +
                it.scriptTransfers.size.toLong() +
                (it.localSubroutineScan?.candidates?.size ?: 0).toLong() +
                it.resourceFunctionCandidates.size.toLong()
        }
        if (totalCandidates > maxCandidates.toLong()) {
            throw IOException("The module-reference report exceeds the candidate limit.")
        }

        val totalTransfers = reports.sumOf { it.scriptTransfers.size.toLong() }
        val unresolvedTransfers = reports.sumOf { it.unresolvedScriptTransferTargetCount.toLong() }
        val totalLocalSubroutines = reports.sumOf {
            it.localSubroutineScan?.candidates?.size?.toLong() ?: 0L
        }
        val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))
        writer.appendLine("SYSTEM-NNN SCRIPT TRANSFERS, LOCAL SUBROUTINES AND STRING REFERENCES")
        writer.appendLine(
            "Projects: ${reports.size} · script transfers: $totalTransfers · local SUB calls: $totalLocalSubroutines · " +
                "unclassified references: ${reports.sumOf { it.candidates.size }} · unresolved transfer targets: $unresolvedTransfers"
        )
        writer.appendLine(
            "SCRIPT/SCRIPTJUMP/SUBSCRIPT names are cross-title NNNLib engine context. " +
                "Targets below exactly match project.xtx module names; runtime return/startup behavior is not device-verified."
        )
        writer.appendLine(
            "Only record metadata and numeric DWORD previews are included; scenario text, names and resource data are omitted."
        )
        writer.appendLine(
            "Resource/audio SYSTEM_FUNCTION labels are cross-title NNNLib context only; they are candidates, not target-confirmed command semantics."
        )

        reports.sortedBy { it.projectPath }.forEach { report ->
            writer.appendLine()
            writer.appendLine("PROJECT: ${report.projectPath}")
            writer.appendLine(
                "Modules: ${report.moduleCount} · transfers: ${report.scriptTransfers.size} · " +
                    "unclassified references: ${report.candidates.size}"
            )

            if (report.scriptTransfers.isNotEmpty()) {
                writer.appendLine()
                writer.appendLine("SCRIPT TRANSFER RECORDS")
                report.scriptTransfers
                    .groupBy { it.sourceModuleName }
                    .toSortedMap()
                    .forEach { (sourceModule, transfers) ->
                        writer.appendLine("SOURCE MODULE: $sourceModule")
                        transfers.sortedBy { it.commandOffset }.forEach { transfer ->
                            val filmLabel = when {
                                transfer.sourceFilmTableIndex == null -> "outside indexed film intervals"
                                transfer.sourceFilmNumber == null -> "reserved table entry ${transfer.sourceFilmTableIndex}"
                                else -> "film ${transfer.sourceFilmNumber}"
                            }
                            writer.appendLine(
                                "  ${transfer.kind.name} @${formatOffset(transfer.commandOffset)} " +
                                    "($filmLabel) -> ${transfer.targetModuleName} " +
                                    "pool2=${transfer.pool2Index} " +
                                    "lengthWords=${transfer.recordLengthWords} " +
                                    "followingLengthWord=${transfer.followingRecordLengthWord ?: "—"}"
                            )
                            writer.appendLine("    PREV: ${formatContext(transfer.previousRecord)}")
                            writer.appendLine("    NEXT: ${formatContext(transfer.nextRecord)}")
                        }
                    }
            }

            if (report.resourceFunctionCandidates.isNotEmpty()) {
                writer.appendLine()
                writer.appendLine("RESOURCE/AUDIO SYSTEM_FUNCTION CANDIDATES (FAMILY REFERENCE LABELS ONLY)")
                report.resourceFunctionCandidates
                    .sortedWith(compareBy<SystemNnnResourceFunctionCandidate> { it.sourceModuleName }
                        .thenBy { it.commandOffset })
                    .forEach { candidate ->
                        val leadingCount = candidate.leadingCountWord?.toString() ?: "missing"
                        val comparison = when (candidate.countWordEqualsRemainingPayloadWordCount) {
                            true -> "equal"
                            false -> "different"
                            null -> "unavailable"
                        }
                        val preview = candidate.payloadPreview.joinToString(",") { formatDword(it) }
                        writer.appendLine(
                            "  MODULE=${candidate.sourceModuleName} ${candidate.kind.familyReferenceName} " +
                                "@${formatOffset(candidate.commandOffset)} " +
                                "code=${formatCode(0x3333, candidate.kind.codeId)} " +
                                "lengthWords=${candidate.recordLengthWords} " +
                                "countWord=$leadingCount remainingPayloadWords=${candidate.remainingPayloadWordCount} " +
                                "countVsPayload=$comparison payload=[$preview]"
                        )
                    }
            }

            if (report.localSubroutineScan != null) {
                val localScan = report.localSubroutineScan
                writer.appendLine()
                writer.appendLine("LOCAL SUBROUTINE TABLE TARGETS")
                writer.appendLine(
                    "SUB IDs dense=${localScan.subroutineIdsAreDense} · " +
                        "missing=${localScan.missingSubroutineIds.joinToString(",").ifEmpty { "none" }} · " +
                        "duplicates=${localScan.duplicateSubroutineIds.joinToString(",").ifEmpty { "none" }} · " +
                        "unresolved targets=${localScan.unresolvedTargetCount}"
                )
                localScan.candidates
                    .sortedWith(compareBy<SystemNnnLocalSubroutineCandidate> { it.sourceModuleName }
                        .thenBy { it.subroutineId })
                    .forEach { subroutine ->
                        val targetFilm = subroutine.targetFilmNumber?.let { "film=$it" } ?: "reserved"
                        writer.appendLine(
                            "  MODULE=${subroutine.sourceModuleName} SUB id=${subroutine.subroutineId} @${formatOffset(subroutine.commandOffset)} " +
                                "-> descriptor3[${subroutine.targetTableIndex}] " +
                                "@${formatOffset(subroutine.targetCommandOffset)} " +
                                "function=0x3333/${subroutine.targetFunctionCodeId.toString(16).uppercase(Locale.ROOT)} " +
                                "($targetFilm)"
                        )
                        writer.appendLine("    PREV: ${formatContext(subroutine.previousRecord)}")
                        writer.appendLine("    NEXT: ${formatContext(subroutine.nextRecord)}")
                    }
            }

            if (report.unresolvedScriptTransferTargetCount > 0) {
                writer.appendLine(
                    "UNRESOLVED SCRIPT CONTROL TARGETS: ${report.unresolvedScriptTransferTargetCount}"
                )
            }

            writer.appendLine()
            writer.appendLine("UNCLASSIFIED POOL 2 MODULE-NAME REFERENCES")

            val groups = report.candidates
                .groupBy { candidate ->
                    SourceFilmGroup(
                        sourceModuleName = candidate.sourceModuleName,
                        sourceFilmTableIndex = candidate.sourceFilmTableIndex,
                        sourceFilmNumber = candidate.sourceFilmNumber,
                        sourceFilmStartOffset = candidate.sourceFilmStartOffset,
                        sourceFilmEndOffsetExclusive = candidate.sourceFilmEndOffsetExclusive
                    )
                }
                .entries
                .sortedWith(
                    compareBy<Map.Entry<SourceFilmGroup, List<SystemNnnModuleReferenceCandidate>>> {
                        it.key.sourceModuleName
                    }.thenBy { it.key.sourceFilmTableIndex ?: Int.MAX_VALUE }
                        .thenBy { it.key.sourceFilmStartOffset ?: Long.MAX_VALUE }
                )

            groups.forEach { (group, candidates) ->
                val filmLabel = when {
                    group.sourceFilmTableIndex == null -> "film=unresolved"
                    group.sourceFilmNumber == null -> "reserved"
                    else -> "film=${group.sourceFilmNumber}"
                }
                writer.appendLine()
                writer.appendLine(
                    "SOURCE: ${group.sourceModuleName} · $filmLabel · tableIndex=${group.sourceFilmTableIndex ?: "—"}"
                )
                writer.appendLine(
                    "INTERVAL: ${formatOffset(group.sourceFilmStartOffset)} .. ${formatOffset(group.sourceFilmEndOffsetExclusive)}"
                )

                candidates.sortedBy { it.commandOffset }.forEach { candidate ->
                    writer.appendLine(
                        "  REFERENCE @${formatOffset(candidate.commandOffset)} " +
                            "type=${candidate.identifierType} " +
                            "code=${formatCode(candidate.codeFamily, candidate.codeId)} " +
                            "target=${candidate.targetModuleName} " +
                            "pool2=${candidate.pool2Index} " +
                            "lengthWords=${candidate.recordLengthWords}"
                    )
                    writer.appendLine("    PREV: ${formatContext(candidate.previousRecord)}")
                    writer.appendLine("    NEXT: ${formatContext(candidate.nextRecord)}")
                }
            }

            if (report.warnings.isNotEmpty()) {
                writer.appendLine()
                writer.appendLine("WARNINGS:")
                report.warnings.forEach { warning ->
                    writer.appendLine("  - ${warning.replace('\n', ' ').replace('\r', ' ')}")
                }
            }
        }

        writer.flush()
    }

    private fun formatContext(context: SystemNnnRecordContext?): String {
        if (context == null) return "none within the validated source-film interval"
        val payload = context.payloadPreview.joinToString(",") { formatDword(it) }
        return "@${formatOffset(context.commandOffset)} " +
            "type=${context.identifierType} " +
            "code=${formatCode(context.codeFamily, context.codeId)} " +
            "lengthWords=${context.recordLengthWords} " +
            "payload=[$payload]"
    }

    private fun formatCode(family: Int, id: Int): String =
        "0x${family.toString(16).uppercase(Locale.ROOT).padStart(4, '0')}/" +
            "0x${id.toString(16).uppercase(Locale.ROOT).padStart(4, '0')}"

    private fun formatDword(value: UInt): String =
        "0x${value.toString(16).uppercase(Locale.ROOT).padStart(8, '0')}"

    private fun formatOffset(offset: Long?): String =
        offset?.let { "0x${it.toString(16).uppercase(Locale.ROOT)}" } ?: "—"

    private data class SourceFilmGroup(
        val sourceModuleName: String,
        val sourceFilmTableIndex: Int?,
        val sourceFilmNumber: Int?,
        val sourceFilmStartOffset: Long?,
        val sourceFilmEndOffsetExclusive: Long?
    )

    companion object {
        private const val DEFAULT_MAX_CANDIDATES = 20_000
    }
}
