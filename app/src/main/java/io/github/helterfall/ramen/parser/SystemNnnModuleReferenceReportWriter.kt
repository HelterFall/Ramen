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
    val warnings: List<String> = emptyList()
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
        val totalCandidates = reports.sumOf { it.candidates.size.toLong() }
        if (totalCandidates > maxCandidates.toLong()) {
            throw IOException("The module-reference report exceeds the candidate limit.")
        }

        val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))
        writer.appendLine("SYSTEM-NNN MODULE-REFERENCE CANDIDATES")
        writer.appendLine("Projects: ${reports.size} · candidates: $totalCandidates")
        writer.appendLine(
            "NOTICE: these are exact Pool 2 module-name references, not proven calls, jumps, or startup routes."
        )
        writer.appendLine(
            "Only record metadata and opaque payload previews are included; scenario text and resource data are omitted."
        )

        reports.sortedBy { it.projectPath }.forEach { report ->
            writer.appendLine()
            writer.appendLine("PROJECT: ${report.projectPath}")
            writer.appendLine("Modules: ${report.moduleCount} · candidates: ${report.candidates.size}")

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
                        "  CANDIDATE @${formatOffset(candidate.commandOffset)} " +
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
