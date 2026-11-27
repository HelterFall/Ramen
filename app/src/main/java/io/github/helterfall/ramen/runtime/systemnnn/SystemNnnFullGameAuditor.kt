package io.github.helterfall.ramen.runtime.systemnnn

import io.github.helterfall.ramen.parser.SystemNnnCommandRecord
import io.github.helterfall.ramen.parser.SystemNnnCommandStream
import io.github.helterfall.ramen.parser.SystemNnnCommandStreamReader
import io.github.helterfall.ramen.parser.SystemNnnLocalSubroutineScanner
import io.github.helterfall.ramen.parser.SystemNnnModuleReferenceScanner
import io.github.helterfall.ramen.parser.SystemNnnProjectCatalog
import io.github.helterfall.ramen.parser.SystemNnnResourceFunctionScanner
import io.github.helterfall.ramen.parser.SystemNnnSceneCatalogReader
import io.github.helterfall.ramen.parser.SystemNnnScript
import io.github.helterfall.ramen.parser.SystemNnnScriptLoader
import io.github.helterfall.ramen.parser.SystemNnnScriptTransferScanner
import io.github.helterfall.ramen.parser.SystemNnnVariableCatalogReader
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.util.Locale
import java.util.TreeMap

/**
 * Read-only whole-install audit. It inventories every valid SPT module found
 * under the imported game directory, independently of the currently played
 * film or of the runtime's current branch. No dialogue text, image bytes,
 * audio bytes, or decoded pool contents are written to the report.
 */
internal class SystemNnnFullGameAuditor(
    private val cacheDirectory: File
) {
    private data class ModulePlan(
        val file: File,
        val moduleName: String,
        val assetRoot: File,
        val projectManifests: MutableSet<String> = linkedSetOf(),
        var isOrphan: Boolean = false
    )

    private data class OpcodeKey(
        val recordKind: String,
        val family: Int,
        val codeId: Int
    )

    private class OpcodeStats {
        var count = 0
        var decoded = 0
        var unsupported = 0
        var decodeErrors = 0
        var runtimeGaps = 0
        val perModule = TreeMap<String, Int>()
        val locations = ArrayList<Map<String, Any?>>()
    }

    private data class EffectKey(val effectId: Int, val layerIndex: Int)

    private class EffectStats {
        var count = 0
        val parameterCounts = TreeMap<Int, Int>()
        val parameterSamples = ArrayList<List<Int>>()
        val contexts = linkedSetOf<String>()
    }

    private data class ReferenceKey(
        val kind: String,
        val resource: String,
        val status: String
    )

    private class ReferenceStats {
        var count = 0
        val locations = ArrayList<Map<String, Any?>>()
    }

    private class IssueStats(val code: String, val message: String) {
        var count = 0
        val locations = ArrayList<Map<String, Any?>>()
    }

    private data class AssetManagerResult(
        val manager: SystemNnnAssetManager?,
        val error: String?
    )

    private val scriptLoader = SystemNnnScriptLoader()
    private val streamReader = SystemNnnCommandStreamReader()
    private val opcodeStats = linkedMapOf<OpcodeKey, OpcodeStats>()
    private val effectStats = linkedMapOf<EffectKey, EffectStats>()
    private val referenceStats = linkedMapOf<ReferenceKey, ReferenceStats>()
    private val issueStats = linkedMapOf<String, IssueStats>()
    private val moduleReports = ArrayList<Map<String, Any?>>()
    private val projectReports = ArrayList<MutableMap<String, Any?>>()
    private val assetManagers = linkedMapOf<String, AssetManagerResult>()
    private val assetCatalogReports = linkedMapOf<String, Map<String, Any?>>()
    private val globalRecordKinds = TreeMap<String, Int>()
    private val globalRuntimeCoverage = TreeMap<String, Int>()
    private val globalTransfers = ArrayList<Map<String, Any?>>()
    private val globalSubroutines = ArrayList<Map<String, Any?>>()
    private val globalGotos = ArrayList<Map<String, Any?>>()
    private val filmCountsByModulePath = linkedMapOf<String, Int>()
    private val maxIssueLocations = 40
    private val maxOpcodeLocations = 200
    private val maxEffectSamples = 6
    private var totalRecords = 0
    private var totalSptCandidates = 0
    private var invalidSptCandidates = 0
    private var fileWalkTruncated = false
    private var fileWalkDepthLimited = false
    private var totalModulesScanned = 0
    private var totalModulesFailed = 0
    private var totalManifests = 0
    private var totalScenes = 0
    private var totalSceneVoiceIds = 0
    private var totalVariables = 0

    fun audit(
        gameDirectory: File,
        onProgress: ((message: String, fraction: Float?) -> Unit)? = null
    ): Map<String, Any?> {
        reset()
        if (!gameDirectory.isDirectory || Files.isSymbolicLink(gameDirectory.toPath())) {
            throw IOException("The imported game directory is unavailable or is a symbolic link.")
        }
        val root = gameDirectory.canonicalFile
        onProgress?.invoke("Обхожу папки и файлы игры…", null)
        val allFiles = discoverFiles(root) { discoveredCount ->
            onProgress?.invoke("Проверено файлов: $discoveredCount", null)
        }
        onProgress?.invoke(
            "Найдено файлов: ${allFiles.size}. Собираю проекты и SPT-модули…",
            0.08f
        )
        val manifestFiles = allFiles.filter {
            it.isFile &&
                it.name.equals("project.xtx", ignoreCase = true) &&
                it.parentFile?.name.equals("spt", ignoreCase = true)
        }.sortedBy { relativePath(root, it) }
        val sptFiles = allFiles.filter {
            it.isFile && it.extension.equals("spt", ignoreCase = true)
        }.sortedBy { relativePath(root, it) }
        totalSptCandidates = sptFiles.size

        val plansByPath = linkedMapOf<String, ModulePlan>()
        val manifestModules = linkedMapOf<String, List<String>>()
        manifestFiles.forEach { manifest ->
            val manifestPath = relativePath(root, manifest)
            try {
                val catalog = SystemNnnProjectCatalog.load(manifest)
                val modules = catalog.resolveModules(manifest)
                val names = modules.map { it.name }
                manifestModules[manifest.canonicalPath] = names
                val assetRoot = resolveAssetRoot(manifest.parentFile ?: root)
                modules.forEach { module ->
                    val canonicalPath = module.scriptFile.canonicalPath
                    val plan = plansByPath.getOrPut(canonicalPath) {
                        ModulePlan(
                            file = module.scriptFile,
                            moduleName = module.name,
                            assetRoot = assetRoot
                        )
                    }
                    plan.projectManifests += manifestPath
                }
                projectReports += linkedMapOf(
                    "manifest" to manifestPath,
                    "moduleCount" to modules.size,
                    "moduleNames" to names,
                    "status" to "parsed"
                )
                totalManifests += 1
            } catch (failure: Exception) {
                addIssue(
                    "project_manifest_error",
                    safeMessage(failure, root),
                    manifestPath,
                    null
                )
                projectReports += linkedMapOf(
                    "manifest" to manifestPath,
                    "moduleCount" to 0,
                    "status" to "error",
                    "error" to safeMessage(failure, root)
                )
            }
        }

        sptFiles.forEach { file ->
            val canonicalPath = runCatching { file.canonicalPath }.getOrNull() ?: return@forEach
            if (canonicalPath in plansByPath) return@forEach
            if (Files.isSymbolicLink(file.toPath())) return@forEach
            if (!SystemNnnScriptLoader.hasValidHeader(file)) {
                invalidSptCandidates += 1
                addIssue("invalid_spt_candidate", "SPT-like file has no valid System-NNN header.",
                    relativePath(root, file), null)
                return@forEach
            }
            val parent = file.parentFile ?: root
            val assetRoot = resolveAssetRoot(parent)
            plansByPath[canonicalPath] = ModulePlan(
                file = file,
                moduleName = file.nameWithoutExtension,
                assetRoot = assetRoot,
                isOrphan = true
            )
        }

        if (manifestFiles.isEmpty() && plansByPath.isEmpty()) {
            addIssue(
                "no_system_nnn_project",
                "No valid project.xtx manifest or standalone System-NNN SPT script was found.",
                null,
                null
            )
        }
        if (fileWalkTruncated) {
            addIssue(
                "file_scan_limit",
                "Directory discovery reached the configured file limit; the report is incomplete.",
                null,
                null
            )
        }
        if (fileWalkDepthLimited) {
            addIssue(
                "file_scan_depth_limit",
                "Directory discovery reached the configured depth limit while deeper folders remained; the report may omit files in those folders.",
                null,
                null
            )
        }

        val managersByModule = linkedMapOf<String, SystemNnnAssetManager?>()
        val modulePlans = plansByPath.values.toList()
        try {
            val moduleProgressStride = (modulePlans.size / 100).coerceAtLeast(1)
            modulePlans.forEachIndexed { moduleIndex, plan ->
                if (moduleIndex == 0 || (moduleIndex + 1) % moduleProgressStride == 0 ||
                    moduleIndex == modulePlans.lastIndex
                ) {
                    val managerProgress = 0.10f + 0.08f *
                        ((moduleIndex + 1).toFloat() / modulePlans.size.coerceAtLeast(1))
                    onProgress?.invoke(
                        "Проверяю каталоги ресурсов: ${moduleIndex + 1}/${modulePlans.size}",
                        managerProgress
                    )
                }
            val assetRoot = plan.assetRoot.canonicalFile
            val key = assetRoot.canonicalPath
            val managerResult = assetManagers.getOrPut(key) {
                try {
                    AssetManagerResult(
                        SystemNnnAssetManager.forGameDirectory(
                            gameDirectory = assetRoot,
                            cacheDirectory = File(
                                cacheDirectory,
                                "full-audit-" + Integer.toHexString(key.hashCode())
                            )
                        ),
                        null
                    )
                } catch (failure: Exception) {
                    AssetManagerResult(null, safeMessage(failure, root))
                }
            }
            managersByModule[plan.file.canonicalPath] = managerResult.manager
            if (managerResult.manager == null) {
                addIssue(
                    "asset_catalog_unavailable",
                    managerResult.error ?: "Asset catalog could not be opened.",
                    relativePath(root, plan.file),
                    null
                )
            } else if (key !in assetCatalogReports) {
                val summary = runCatching {
                    assetCatalogSummary(assetRoot, managerResult.manager)
                }.onFailure { failure ->
                    addIssue(
                        "asset_catalog_inventory_error",
                        safeMessage(failure, root),
                        relativePath(root, plan.file),
                        null
                    )
                }.getOrNull()
                if (summary != null) assetCatalogReports[key] = summary
            }
        }

            val projectNamesByManifest = manifestModules
            modulePlans.forEachIndexed { moduleIndex, plan ->
                if (Thread.currentThread().isInterrupted) {
                    throw IOException("Full game audit was interrupted.")
                }
                val projectNames = plan.projectManifests
                    .firstNotNullOfOrNull { manifestPath ->
                        projectNamesByManifest.entries.firstOrNull {
                            relativePath(root, File(it.key)) == manifestPath
                        }?.value
                    }.orEmpty()
                val moduleFraction = { processed: Int, recordCount: Int ->
                    val withinModule = if (recordCount <= 0) 1f
                    else (processed.toFloat() / recordCount).coerceIn(0f, 1f)
                    val fraction = 0.18f + 0.68f *
                        ((moduleIndex + withinModule) / modulePlans.size.coerceAtLeast(1))
                    onProgress?.invoke(
                        "Модуль ${moduleIndex + 1}/${modulePlans.size}: ${plan.moduleName} — записей $processed/$recordCount",
                        fraction.coerceIn(0.18f, 0.86f)
                    )
                }
                scanModule(
                    root = root,
                    plan = plan,
                    projectModuleNames = projectNames,
                    assetManager = managersByModule[plan.file.canonicalPath],
                    onRecordsProgress = moduleFraction
                )
                if (moduleIndex == 0 || (moduleIndex + 1) % moduleProgressStride == 0 ||
                    moduleIndex == modulePlans.lastIndex
                ) {
                    onProgress?.invoke(
                        "Модуль ${moduleIndex + 1}/${modulePlans.size} обработан: ${plan.moduleName}",
                        (0.18f + 0.68f * ((moduleIndex + 1).toFloat() / modulePlans.size.coerceAtLeast(1)))
                            .coerceIn(0.18f, 0.86f)
                    )
                }
            }

            onProgress?.invoke("Проверяю scene.dat, var.fxf и дополнительные каталоги…", 0.89f)
            scanProjectSidecars(root, modulePlans, manifestFiles) { index, total ->
                val fraction = 0.89f + 0.05f * (index.toFloat() / total.coerceAtLeast(1))
                onProgress?.invoke(
                    "Дополнительные каталоги проекта: $index/$total",
                    fraction.coerceIn(0.89f, 0.94f)
                )
            }
        } finally {
            assetManagers.values.forEach { result ->
                runCatching { result.manager?.close() }
            }
            assetManagers.clear()
        }

        onProgress?.invoke("Собираю итоговую сводку SCAN…", 0.95f)
        val report = linkedMapOf(
            "reportType" to "ramen.system-nnn.full-game-audit",
            "schemaVersion" to 1,
            "generatedAtUtc" to Instant.now().toString(),
            "scope" to linkedMapOf(
                "rootLabel" to root.name,
                "allDiscoveredProjectManifests" to totalManifests,
                "allValidSptModules" to totalModulesScanned,
                "discoveredSptModules" to plansByPath.size,
                "successfullyScannedModules" to totalModulesScanned,
                "orphanSptModules" to plansByPath.values.count { it.isOrphan },
                "invalidSptCandidates" to invalidSptCandidates,
                "fileDiscoveryTruncated" to fileWalkTruncated,
                "fileDiscoveryDepthLimited" to fileWalkDepthLimited,
                "totalPhysicalRecords" to totalRecords,
                "totalSptCandidates" to totalSptCandidates,
                "failedModules" to totalModulesFailed,
                "sceneDatEntries" to totalScenes,
                "sceneVoiceIds" to totalSceneVoiceIds,
                "variableNames" to totalVariables
            ),
            "coverage" to linkedMapOf(
                "projectManifests" to "all discovered spt/project.xtx files",
                "sptModules" to "all valid SPT files referenced by manifests plus valid orphan SPT files, subject to reported file count and depth limits",
                "records" to "all validated physical command records in every scanned module",
                "conditionalPaths" to "both sides are inventoried structurally; runtime-dependent branch outcomes are not guessed",
                "assets" to "asset bank catalog and statically decoded resource references; asset payload bytes are not exported",
                "dialogueContent" to "not exported",
                "assetContent" to "image/audio bytes are not exported"
            ),
            "projects" to projectReports,
            "modules" to moduleReports,
            "recordKinds" to globalRecordKinds,
            "runtimeCoverage" to globalRuntimeCoverage,
            "opcodeInventory" to opcodeStats.entries
                .sortedWith(compareByDescending<Map.Entry<OpcodeKey, OpcodeStats>> { it.value.count }
                    .thenBy { it.key.recordKind }.thenBy { it.key.family }.thenBy { it.key.codeId })
                .map { (key, stats) ->
                    linkedMapOf(
                        "recordKind" to key.recordKind,
                        "family" to key.family,
                        "familyHex" to hex(key.family),
                        "codeId" to key.codeId,
                        "codeIdHex" to hex(key.codeId),
                        "name" to codeName(key.family, key.codeId),
                        "occurrences" to stats.count,
                        "decoded" to stats.decoded,
                        "unsupported" to stats.unsupported,
                        "decodeErrors" to stats.decodeErrors,
                        "runtimeNotApplied" to stats.runtimeGaps,
                        "perModule" to stats.perModule,
                        "locations" to stats.locations,
                        "locationsTruncated" to (stats.count > stats.locations.size && stats.locations.size >= maxOpcodeLocations)
                    )
                },
            "effects" to effectStats.entries
                .sortedWith(compareByDescending<Map.Entry<EffectKey, EffectStats>> { it.value.count }
                    .thenBy { it.key.effectId }.thenBy { it.key.layerIndex })
                .map { (key, stats) ->
                    val expected = SystemNnnVisualEffectMath.observedParameterCount(key.effectId)
                    val layerPath = SystemNnnVisualEffectMath.hasNativeLayerEffectPath(
                        key.effectId,
                        stats.parameterSamples.firstOrNull().orEmpty()
                    )
                    val screenPath = key.layerIndex == SCREEN_EFFECT_LAYER &&
                        key.effectId in SCREEN_BUFFER_EFFECT_IDS
                    linkedMapOf(
                        "effectId" to key.effectId,
                        "effectIdHex" to hex(key.effectId),
                        "name" to SystemNnnVisualEffectMath.observedEffectName(key.effectId),
                        "layerIndex" to key.layerIndex,
                        "context" to if (key.layerIndex == SCREEN_EFFECT_LAYER) "screen_buffer" else "image_layer",
                        "occurrences" to stats.count,
                        "parameterCountDistribution" to stats.parameterCounts,
                        "observedParameterCount" to expected,
                        "hasKnownRendererPath" to (layerPath || screenPath),
                        "rendererPathStatus" to when {
                            layerPath && expected == null -> "path_present_signature_unobserved"
                            layerPath || screenPath -> "path_present"
                            else -> "no_known_path"
                        },
                        "parameterSamples" to stats.parameterSamples,
                        "contexts" to stats.contexts
                    )
                },
            "resourceReferences" to referenceStats.entries
                .sortedWith(compareBy<Map.Entry<ReferenceKey, ReferenceStats>> { it.key.kind }
                    .thenBy { it.key.resource }.thenBy { it.key.status })
                .map { (key, stats) ->
                    linkedMapOf(
                        "kind" to key.kind,
                        "resource" to key.resource,
                        "status" to key.status,
                        "occurrences" to stats.count,
                        "locations" to stats.locations
                    )
                },
            "moduleTransfers" to globalTransfers,
            "localSubroutines" to globalSubroutines,
            "gotos" to globalGotos,
            "assetCatalogs" to assetCatalogReports.values.toList(),
            "issues" to issueStats.values.map { issue ->
                linkedMapOf(
                    "code" to issue.code,
                    "message" to issue.message,
                    "occurrences" to issue.count,
                    "locations" to issue.locations
                )
            },
            "limitations" to listOf(
                "A static scan cannot prove which conditional branches are reached for every save state.",
                "Resource catalog presence is not a visual or audible quality check.",
                "A decoded opcode is not considered implemented unless its runtime handler is connected.",
                "Known effect signatures are reference observations; differing parameter counts are flagged, not automatically declared invalid.",
                "No raw SPT, image, audio, dialogue, or other game payload is included in this JSON report."
            )
        )
        onProgress?.invoke("Анализ игры завершён. JSON подготовлен.", 1f)
        return report
    }

    private fun scanModule(
        root: File,
        plan: ModulePlan,
        projectModuleNames: Collection<String>,
        assetManager: SystemNnnAssetManager?,
        onRecordsProgress: (processed: Int, total: Int) -> Unit
    ) {
        val relative = relativePath(root, plan.file)
        val module = linkedMapOf<String, Any?>(
            "module" to plan.moduleName,
            "file" to relative,
            "projectManifests" to plan.projectManifests.toList(),
            "orphan" to plan.isOrphan,
            "fileSizeBytes" to plan.file.length()
        )
        try {
            val script = scriptLoader.load(plan.file)
            val stream = streamReader.read(script)
            val filmEntries = try {
                streamReader.filmTableEntries(script, stream)
            } catch (failure: Exception) {
                addIssue("film_table_error", safeMessage(failure, root), relative, null)
                emptyList()
            }
            val reservedFilms = script.offsetTables.getOrNull(3)?.entryCount?.toInt() ?: 0
            val films = filmEntries.count { it.filmNumber != null }
            filmCountsByModulePath[plan.file.canonicalPath] = films
            val moduleOpcodeStart = totalRecords
            var moduleUnsupported = 0
            var moduleDecodeErrors = 0
            var moduleRuntimeGaps = 0
            val effectCountBefore = effectStats.values.sumOf { it.count }
            val referencesBefore = referenceStats.values.sumOf { it.count }
            val decoder = SystemNnnCommandDecoderImpl(
                gtbResourceCount = assetManager?.resourceCount ?: 0,
                streamReader = streamReader,
                variables = HashMap(),
                debugLogFile = null,
                diagnosticsEnabled = false
            )

            var lastProgressTimeNanos = 0L
            stream.records.forEachIndexed { recordIndex, record ->
                if (Thread.currentThread().isInterrupted) {
                    throw IOException("Full game audit was interrupted.")
                }
                totalRecords += 1
                if (totalRecords > MAX_TOTAL_RECORDS) {
                    throw IOException("The audit reached the configured physical-record limit.")
                }
                val kind = record.recordKind?.name ?: "UNKNOWN"
                globalRecordKinds[kind] = (globalRecordKinds[kind] ?: 0) + 1
                val key = OpcodeKey(kind, record.codeFamily, record.codeId)
                val stats = opcodeStats.getOrPut(key) { OpcodeStats() }
                stats.count += 1
                stats.perModule[plan.moduleName] = (stats.perModule[plan.moduleName] ?: 0) + 1
                if (stats.locations.size < maxOpcodeLocations) {
                    stats.locations += linkedMapOf(
                        "module" to plan.moduleName,
                        "file" to relative,
                        "offset" to hexLong(record.offset)
                    )
                }

                val decoded = decodePhysicalRecord(script, stream, record, decoder)
                when (decoded.status) {
                    "decoded" -> stats.decoded += 1
                    "unsupported" -> {
                        stats.unsupported += 1
                        moduleUnsupported += 1
                        addIssue(
                            "unsupported_opcode",
                            decoded.reason ?: "Opcode is not implemented by the decoder.",
                            relative,
                            record.offset
                        )
                    }
                    "decode_error" -> {
                        stats.decodeErrors += 1
                        moduleDecodeErrors += 1
                        addIssue(
                            "opcode_decode_error",
                            decoded.reason ?: "Opcode payload could not be decoded.",
                            relative,
                            record.offset
                        )
                    }
                    "known_control" -> stats.decoded += 1
                }

                if (decoded.runtimeGap != null) {
                    stats.runtimeGaps += 1
                    moduleRuntimeGaps += 1
                    globalRuntimeCoverage[decoded.runtimeGap] =
                        (globalRuntimeCoverage[decoded.runtimeGap] ?: 0) + 1
                    addIssue(
                        "decoded_but_not_applied",
                        decoded.runtimeGap,
                        relative,
                        record.offset
                    )
                }

                val command = decoded.command
                if (command != null) {
                    collectCommand(
                        root = root,
                        plan = plan,
                        record = record,
                        command = command,
                        assetManager = assetManager
                    )
                    if (command is SystemNnnDecodedCommand.NativeSystemFunction &&
                        command.functionId != PREPARE_OVERRAP
                    ) {
                        val gap = systemFunctionName(command.functionId) +
                            " (0x" + command.functionId.toString(16) +
                            ") is decoded but currently has no state-changing handler in the player."
                        stats.runtimeGaps += 1
                        moduleRuntimeGaps += 1
                        globalRuntimeCoverage[gap] = (globalRuntimeCoverage[gap] ?: 0) + 1
                        addIssue("decoded_but_not_applied", gap, relative, record.offset)
                    }
                }

                if (record.recordKind == io.github.helterfall.ramen.parser.SystemNnnRecordKind.CONTROL &&
                    record.codeFamily == CONTROL_FAMILY &&
                    record.codeId == CONTROL_GOTO
                ) {
                    val goto = runCatching { decoder.decodeNext(script, record.offset) }.getOrNull()
                    if (goto is SystemNnnDecodedCommand.Jump) {
                        globalGotos += linkedMapOf(
                            "module" to plan.moduleName,
                            "sourceOffset" to hexLong(record.offset),
                            "targetOffset" to hexLong(goto.targetCommandOffset),
                            "targetExists" to (stream.records.binarySearchBy(goto.targetCommandOffset) { it.offset } >= 0)
                        )
                    }
                }
                val progressNow = System.nanoTime()
                val progressIntervalElapsed =
                    progressNow - lastProgressTimeNanos >= 100_000_000L
                if (recordIndex == stream.records.lastIndex ||
                    ((recordIndex + 1) % 128 == 0 && progressIntervalElapsed)
                ) {
                    lastProgressTimeNanos = progressNow
                    onRecordsProgress(recordIndex + 1, stream.records.size)
                }
            }

            val localSubs = try {
                SystemNnnLocalSubroutineScanner(streamReader).scan(plan.moduleName, script, stream)
            } catch (failure: Exception) {
                addIssue("local_subroutine_scan_error", safeMessage(failure, root), relative, null)
                null
            }
            if (localSubs != null) {
                globalSubroutines += linkedMapOf(
                    "module" to plan.moduleName,
                    "candidateCount" to localSubs.candidates.size,
                    "subroutineIdsAreDense" to localSubs.subroutineIdsAreDense,
                    "missingSubroutineIds" to localSubs.missingSubroutineIds,
                    "duplicateSubroutineIds" to localSubs.duplicateSubroutineIds,
                    "unresolvedTargetCount" to localSubs.unresolvedTargetCount,
                    "candidates" to localSubs.candidates.map {
                        mapOf(
                            "commandOffset" to hexLong(it.commandOffset),
                            "subroutineId" to it.subroutineId,
                            "targetTableIndex" to it.targetTableIndex,
                            "targetOffset" to hexLong(it.targetCommandOffset),
                            "targetFilmNumber" to it.targetFilmNumber
                        )
                    }
                )
                if (localSubs.unresolvedTargetCount > 0 || localSubs.duplicateSubroutineIds.isNotEmpty()) {
                    addIssue(
                        "local_subroutine_targets",
                        "One or more local SUB references are unresolved or duplicated.",
                        relative,
                        null
                    )
                }
            }

            val transfers = try {
                SystemNnnScriptTransferScanner(streamReader).scan(
                    plan.moduleName,
                    script,
                    stream,
                    projectModuleNames
                )
            } catch (failure: Exception) {
                addIssue("module_transfer_scan_error", safeMessage(failure, root), relative, null)
                null
            }
            if (transfers != null) {
                globalTransfers += linkedMapOf(
                    "sourceModule" to plan.moduleName,
                    "candidateCount" to transfers.candidates.size,
                    "unresolvedTargetCount" to transfers.unresolvedTargetCount,
                    "edges" to transfers.candidates.map {
                        mapOf(
                            "targetModule" to it.targetModuleName,
                            "kind" to it.kind.name,
                            "commandOffset" to hexLong(it.commandOffset),
                            "sourceFilmNumber" to it.sourceFilmNumber
                        )
                    }
                )
                if (transfers.unresolvedTargetCount > 0) {
                    addIssue(
                        "unresolved_module_transfer",
                        "One or more SCRIPT/SUBSCRIPT/SCRIPTJUMP targets do not resolve to a listed project module.",
                        relative,
                        null
                    )
                }
            }

            val resourceScan = try {
                SystemNnnResourceFunctionScanner(streamReader = streamReader)
                    .scan(plan.moduleName, script, stream)
            } catch (failure: Exception) {
                addIssue("resource_function_scan_error", safeMessage(failure, root), relative, null)
                null
            }

            val moduleReferences = try {
                SystemNnnModuleReferenceScanner(streamReader).scan(
                    plan.moduleName,
                    script,
                    stream,
                    projectModuleNames
                )
            } catch (failure: Exception) {
                addIssue("module_reference_scan_error", safeMessage(failure, root), relative, null)
                emptyList()
            }

            module["status"] = "scanned"
            module["physicalRecordCount"] = stream.records.size
            module["recordKinds"] = stream.records.groupingBy {
                it.recordKind?.name ?: "UNKNOWN"
            }.eachCount()
            module["filmTableEntries"] = filmEntries.size
            module["ordinaryFilms"] = films
            module["reservedFilmEntries"] = reservedFilms
            module["localSubroutineCalls"] = localSubs?.candidates?.size
            module["moduleTransferCandidates"] = transfers?.candidates?.size
            module["resourceFunctionCandidates"] = resourceScan?.candidates?.size
            module["moduleReferenceCandidates"] = moduleReferences.size
            module["unsupportedRecords"] = moduleUnsupported
            module["decodeErrors"] = moduleDecodeErrors
            module["decodedButNotApplied"] = moduleRuntimeGaps
            module["effectOccurrences"] = effectStats.values.sumOf { it.count } - effectCountBefore
            module["resourceReferenceOccurrences"] = referenceStats.values.sumOf { it.count } - referencesBefore
            module["descriptorTables"] = script.offsetTables.map {
                mapOf(
                    "name" to it.name,
                    "entryCount" to it.entryCount.toLong(),
                    "logicalBase" to it.logicalBase.toLong(),
                    "hasPayload" to it.hasPayload
                )
            }
            moduleReports += module
            totalModulesScanned += 1
        } catch (failure: Exception) {
            module["status"] = "error"
            module["error"] = safeMessage(failure, root)
            moduleReports += module
            totalModulesFailed += 1
            addIssue("module_scan_error", safeMessage(failure, root), relative, null)
        }
    }

    private data class DecodeResult(
        val status: String,
        val command: SystemNnnDecodedCommand? = null,
        val reason: String? = null,
        val runtimeGap: String? = null
    )

    private fun decodePhysicalRecord(
        script: SystemNnnScript,
        stream: SystemNnnCommandStream,
        record: SystemNnnCommandRecord,
        decoder: SystemNnnCommandDecoderImpl
    ): DecodeResult {
        if (record.recordKind == io.github.helterfall.ramen.parser.SystemNnnRecordKind.CONTROL) {
            if (record.codeFamily == EXPRESSION_FAMILY &&
                record.codeId in setOf(OPEN_BRACE, CLOSE_BRACE)
            ) return DecodeResult("known_control")
            if (record.codeFamily == CONTROL_FAMILY && record.codeId in SUPPORTED_CONTROL_IDS) {
                return DecodeResult("known_control")
            }
            return DecodeResult(
                "unsupported",
                reason = "Unsupported CONTROL opcode " + hex(record.codeId) +
                    " in family " + hex(record.codeFamily) + "."
            )
        }

        return try {
            val command = decoder.decodeNext(script, record.offset)
            when (command) {
                is SystemNnnDecodedCommand.Skip -> {
                    if (command.unsupported) {
                        DecodeResult("unsupported", command, command.reason)
                    } else if (
                        record.recordKind == io.github.helterfall.ramen.parser.SystemNnnRecordKind.SYSTEM_FUNCTION &&
                        record.codeFamily == SYSTEM_FUNCTION_FAMILY &&
                        record.codeId == PRELOAD_DWQ
                    ) {
                        DecodeResult(
                            "decoded",
                            command,
                            runtimeGap = "PRELOADDWQ is consumed by the decoder but does not populate the image cache."
                        )
                    } else {
                        DecodeResult("decoded", command)
                    }
                }
                else -> DecodeResult("decoded", command)
            }
        } catch (failure: Exception) {
            DecodeResult("decode_error", reason = safeMessage(failure, null))
        }
    }

    private fun collectCommand(
        root: File,
        plan: ModulePlan,
        record: SystemNnnCommandRecord,
        command: SystemNnnDecodedCommand,
        assetManager: SystemNnnAssetManager?
    ) {
        val module = plan.moduleName
        val location = relativePath(root, plan.file)
        when (command) {
            is SystemNnnDecodedCommand.SetImageLayerEffect -> {
                val key = EffectKey(command.effectId, command.layerIndex)
                val stats = effectStats.getOrPut(key) { EffectStats() }
                stats.count += 1
                stats.parameterCounts[command.parameters.size] =
                    (stats.parameterCounts[command.parameters.size] ?: 0) + 1
                if (stats.parameterSamples.size < maxEffectSamples &&
                    command.parameters !in stats.parameterSamples
                ) {
                    stats.parameterSamples += command.parameters
                }
                stats.contexts += if (command.layerIndex == SCREEN_EFFECT_LAYER) "screen_buffer" else "image_layer"
                val nativePath = if (command.layerIndex == SCREEN_EFFECT_LAYER) {
                    command.effectId in SCREEN_BUFFER_EFFECT_IDS
                } else {
                    SystemNnnVisualEffectMath.hasNativeLayerEffectPath(command.effectId, command.parameters)
                }
                if (!nativePath) {
                    addIssue(
                        "effect_without_known_renderer_path",
                        "Effect " + SystemNnnVisualEffectMath.observedEffectName(command.effectId) +
                            " (id=" + command.effectId + ") has no known renderer path for layer " +
                            command.layerIndex + ".",
                        location,
                        record.offset
                    )
                }
                val expected = SystemNnnVisualEffectMath.observedParameterCount(command.effectId)
                if (expected != null && expected != command.parameters.size) {
                    addIssue(
                        "effect_parameter_signature_difference",
                        "Effect " + command.effectId + " has " + command.parameters.size +
                            " parameters; the recorded reference signature has " + expected + ".",
                        location,
                        record.offset
                    )
                }
            }
            is SystemNnnDecodedCommand.LoadImageLayer -> {
                checkImageReference(module, location, record.offset, command.resourceId, assetManager)
            }
            is SystemNnnDecodedCommand.SceneUpdate -> {
                command.backgroundResourceId?.let {
                    checkImageReference(module, location, record.offset, it, assetManager)
                }
                command.backgroundResourceIndex?.let {
                    checkImageIndexReference(module, location, record.offset, it, assetManager)
                }
                command.sprites.forEach { sprite ->
                    checkImageReference(module, location, record.offset, sprite.resourceId, assetManager)
                }
            }
            is SystemNnnDecodedCommand.Dialogue -> {
                command.backgroundResourceId?.let {
                    checkImageReference(module, location, record.offset, it, assetManager)
                }
                command.backgroundResourceIndex?.let {
                    checkImageIndexReference(module, location, record.offset, it, assetManager)
                }
                command.sprites.forEach { sprite ->
                    checkImageReference(module, location, record.offset, sprite.resourceId, assetManager)
                }
                command.voiceResourceId?.let {
                    checkVoiceReference(module, location, record.offset, it, assetManager)
                }
            }
            is SystemNnnDecodedCommand.Voice -> {
                command.resourceId?.let {
                    checkVoiceReference(module, location, record.offset, it, assetManager)
                }
            }
            is SystemNnnDecodedCommand.Music -> {
                if (command.musicNumber > 0) {
                    val name = assetManager?.musicResourceName(command.musicNumber)
                    addReference(
                        "music",
                        command.musicNumber.toString(),
                        when {
                            assetManager == null -> "asset_catalog_unavailable"
                            name == null -> "name_list_entry_missing"
                            else -> "name_list_entry_resolved"
                        },
                        module,
                        location,
                        record.offset
                    )
                    if (name == null) {
                        addIssue(
                            "music_name_missing",
                            "MUSIC references track " + command.musicNumber +
                                " but no matching bgmlist.xtx entry was resolved.",
                            location,
                            record.offset
                        )
                    }
                }
            }
            is SystemNnnDecodedCommand.Sound -> {
                if (command.soundNumber >= 0) {
                    val name = assetManager?.soundResourceName(command.soundNumber)
                    addReference(
                        "sound",
                        command.soundNumber.toString(),
                        when {
                            assetManager == null -> "asset_catalog_unavailable"
                            name == null -> "name_list_entry_missing"
                            else -> "name_list_entry_resolved"
                        },
                        module,
                        location,
                        record.offset
                    )
                    if (name == null) {
                        addIssue(
                            "sound_name_missing",
                            "SOUND references SE slot " + command.soundNumber +
                                " but no matching selist.xtx entry was resolved.",
                            location,
                            record.offset
                        )
                    }
                }
            }
            is SystemNnnDecodedCommand.NativeSystemFunction -> {
                if (command.functionId == SET_CG && command.parameters.size >= 2) {
                    val cgNumber = command.parameters[1]
                    val manager = assetManager
                    val status = when {
                        cgNumber <= 0 -> "invalid_one_based_index"
                        manager == null -> "asset_catalog_unavailable"
                        manager.catalog.imageBanks.size == 1 &&
                            cgNumber <= manager.catalog.totalIndexedImageResources -> "within_single_bank_range"
                        manager.catalog.imageBanks.isEmpty() -> "no_image_bank"
                        else -> "bank_mapping_ambiguous"
                    }
                    addReference("cg_index", cgNumber.toString(), status, module, location, record.offset)
                    if (status == "invalid_one_based_index" || status == "no_image_bank") {
                        addIssue(
                            "cg_index_unresolved",
                            "SETCG references CG index " + cgNumber + " but it cannot be resolved in the image catalog.",
                            location,
                            record.offset
                        )
                    }
                }
            }
            else -> Unit
        }
    }

    private fun checkImageReference(
        module: String,
        file: String,
        offset: Long,
        resource: String,
        manager: SystemNnnAssetManager?
    ) {
        val status = when {
            manager == null -> "asset_catalog_unavailable"
            else -> try {
                if (manager.imageMetadata(resource) != null) "resolved" else "not_found"
            } catch (failure: Exception) {
                "ambiguous_or_lookup_error"
            }
        }
        addReference("image", resource, status, module, file, offset)
        if (status == "not_found" || status == "ambiguous_or_lookup_error") {
            addIssue(
                "image_reference_unresolved",
                "Image resource reference '" + resource + "' could not be resolved uniquely.",
                file,
                offset
            )
        }
    }

    private fun checkImageIndexReference(
        module: String,
        file: String,
        offset: Long,
        index: Long,
        manager: SystemNnnAssetManager?
    ) {
        val status = when {
            manager == null -> "asset_catalog_unavailable"
            else -> try {
                if (manager.imageMetadataByIndex(index) != null) "resolved" else "not_found_or_ambiguous"
            } catch (failure: Exception) {
                "not_found_or_ambiguous"
            }
        }
        addReference("image_index", index.toString(), status, module, file, offset)
        if (status == "not_found_or_ambiguous") {
            addIssue(
                "image_index_unresolved",
                "Image index " + index + " is missing or ambiguous across image banks.",
                file,
                offset
            )
        }
    }

    private fun checkVoiceReference(
        module: String,
        file: String,
        offset: Long,
        resource: String,
        manager: SystemNnnAssetManager?
    ) {
        val status = when {
            manager == null -> "asset_catalog_unavailable"
            manager.findResource(resource) != null -> "resolved_indexed_audio"
            manager.catalog.looseAudioResources.any {
                it.id.equals(resource, true) || it.fileName.equals(resource, true) ||
                    it.id.substringAfterLast('/').equals(resource, true)
            } -> "resolved_loose_audio"
            else -> "not_found_in_validated_audio_catalog"
        }
        addReference("voice", resource, status, module, file, offset)
        if (status == "not_found_in_validated_audio_catalog") {
            addIssue(
                "voice_reference_unresolved",
                "VOICE resource '" + resource + "' is not present in the validated indexed or loose audio catalog.",
                file,
                offset
            )
        }
    }

    private fun scanProjectSidecars(
        root: File,
        plans: List<ModulePlan>,
        manifests: List<File>,
        onProgress: (index: Int, total: Int) -> Unit
    ) {
        manifests.forEachIndexed { manifestIndex, manifest ->
            onProgress(manifestIndex + 1, manifests.size)
            val sptDirectory = manifest.parentFile ?: return@forEach
            val project = projectReports.firstOrNull {
                it["manifest"] == relativePath(root, manifest)
            } ?: return@forEach
            val moduleNames = project["moduleNames"] as? List<*> ?: emptyList<Any?>()
            val assetRoot = resolveAssetRoot(sptDirectory)
            val sceneDataFile = File(sptDirectory, "scene.dat")
            var sceneDataSummary: Map<String, Any?>? = null
            if (sceneDataFile.isFile) {
                try {
                    val sceneData = SystemNnnSceneCatalogReader().readSceneData(sceneDataFile)
                    totalScenes += sceneData.recordCount
                    var missingModuleRoutes = 0
                    var missingFilmRoutes = 0
                    sceneData.characters.forEach { character ->
                        character.scenes.forEach { scene ->
                            val moduleName = moduleNames.getOrNull(scene.moduleIndex)?.toString()
                            if (moduleName == null) {
                                missingModuleRoutes += 1
                                return@forEach
                            }
                            val modulePlan = plans.firstOrNull {
                                it.moduleName.equals(moduleName, true) &&
                                    relativePath(root, it.file).startsWith(
                                        relativePath(root, sptDirectory).trimEnd('/') + "/",
                                        true
                                    )
                            }
                            if (modulePlan == null) {
                                missingModuleRoutes += 1
                            } else {
                                val filmCount = filmCountsByModulePath[modulePlan.file.canonicalPath] ?: 0
                                if (scene.filmIndices.any { it !in 0 until filmCount }) {
                                    missingFilmRoutes += 1
                                }
                            }
                        }
                    }
                    sceneDataSummary = linkedMapOf(
                        "characterCount" to sceneData.characterCount,
                        "sceneCount" to sceneData.recordCount,
                        "missingModuleRoutes" to missingModuleRoutes,
                        "missingFilmRoutes" to missingFilmRoutes
                    )
                    if (missingModuleRoutes > 0 || missingFilmRoutes > 0) {
                        addIssue(
                            "scene_dat_route_unresolved",
                            "scene.dat contains module or film references that do not resolve to scanned modules.",
                            relativePath(root, sceneDataFile),
                            null
                        )
                    }
                } catch (failure: Exception) {
                    addIssue("scene_dat_error", safeMessage(failure, root), relativePath(root, sceneDataFile), null)
                }
            }

            val variableFile = File(sptDirectory, "var.fxf")
            if (variableFile.isFile) {
                try {
                    val variables = SystemNnnVariableCatalogReader().read(variableFile)
                    totalVariables += variables.names.size
                } catch (failure: Exception) {
                    addIssue("variable_catalog_error", safeMessage(failure, root), relativePath(root, variableFile), null)
                }
            }

            val sceneVoiceFile = File(assetRoot, "nya/scenevoice.xtx")
            var sceneVoiceCount: Int? = null
            if (sceneVoiceFile.isFile) {
                try {
                    val voiceCatalog = SystemNnnSceneCatalogReader().readSceneVoiceCatalog(sceneVoiceFile)
                    sceneVoiceCount = voiceCatalog.sceneIds.size
                    totalSceneVoiceIds += voiceCatalog.sceneIds.size
                } catch (failure: Exception) {
                    addIssue("scene_voice_catalog_error", safeMessage(failure, root), relativePath(root, sceneVoiceFile), null)
                }
            }
            project["sidecars"] = linkedMapOf(
                "sceneDat" to sceneDataSummary,
                "sceneVoiceIdCount" to sceneVoiceCount,
                "variableNamesScanned" to if (variableFile.isFile) "yes" else "no"
            )
        }
    }

    private fun assetCatalogSummary(
        root: File,
        manager: SystemNnnAssetManager
    ): Map<String, Any?> {
        val catalog = manager.catalog
        val imageResources = manager.imageResourceInventory()
        val audioResources = manager.audioResourceInventory()
        val musicNames = manager.musicNameListInventory()
        val soundNames = manager.soundNameListInventory()
        return linkedMapOf(
            "rootLabel" to root.name,
            "imageBanks" to catalog.imageBanks.map {
                mapOf(
                    "id" to it.id,
                    "type" to it.type.name,
                    "indexPath" to it.indexPath,
                    "archivePath" to it.archivePath,
                    "resourceCount" to it.resourceCount,
                    "nativeNamespace" to it.nativeNamespace,
                    "bankSlot" to it.bankSlot
                )
            },
            "audioBanks" to catalog.audioBanks.map {
                mapOf(
                    "id" to it.id,
                    "type" to it.type.name,
                    "indexPath" to it.indexPath,
                    "archivePath" to it.archivePath,
                    "resourceCount" to it.resourceCount
                )
            },
            "totalIndexedImageResources" to catalog.totalIndexedImageResources,
            "totalIndexedAudioResources" to catalog.totalIndexedAudioResources,
            "looseValidatedAudioResources" to catalog.looseAudioResources.size,
            "looseAudioFormats" to catalog.looseAudioResources.groupingBy { it.mediaFormat }.eachCount(),
            "looseAudioResourceInventory" to catalog.looseAudioResources.take(MAX_ASSET_INVENTORY_ENTRIES).map {
                mapOf(
                    "resourceName" to it.fileName,
                    "relativeId" to it.id,
                    "extension" to it.extension,
                    "packType" to it.packType,
                    "mediaFormat" to it.mediaFormat,
                    "sourceSizeBytes" to it.sourceSizeBytes,
                    "payloadOffsetBytes" to it.payloadOffsetBytes,
                    "payloadSizeBytes" to it.payloadSizeBytes
                )
            },
            "indexedImageResourceCount" to imageResources.size,
            "indexedImageResourcesTruncated" to (imageResources.size > MAX_ASSET_INVENTORY_ENTRIES),
            "indexedImageResources" to imageResources.take(MAX_ASSET_INVENTORY_ENTRIES).map {
                mapOf(
                    "bankId" to it.bankId,
                    "rowIndex" to it.rowIndex,
                    "resourceName" to it.resourceName,
                    "format" to it.format,
                    "blockOffsetBytes" to it.blockOffsetBytes,
                    "blockSizeBytes" to it.blockSizeBytes,
                    "payloadOffsetBytes" to it.payloadOffsetBytes,
                    "payloadSizeBytes" to it.payloadSizeBytes,
                    "widthPixels" to it.widthPixels,
                    "heightPixels" to it.heightPixels,
                    "packType" to it.packType,
                    "hasAlphaMask" to it.hasAlphaMask,
                    "isValid" to it.isValid,
                    "validationError" to it.validationError
                )
            },
            "indexedAudioResourceCount" to audioResources.size,
            "indexedAudioResourcesTruncated" to (audioResources.size > MAX_ASSET_INVENTORY_ENTRIES),
            "indexedAudioResources" to audioResources.take(MAX_ASSET_INVENTORY_ENTRIES).map {
                mapOf(
                    "bankId" to it.bankId,
                    "rowIndex" to it.rowIndex,
                    "resourceName" to it.resourceName,
                    "blockOffsetBytes" to it.blockOffsetBytes,
                    "blockSizeBytes" to it.blockSizeBytes,
                    "oggPayloadOffsetBytes" to it.oggPayloadOffsetBytes,
                    "oggPayloadSizeBytes" to it.oggPayloadSizeBytes,
                    "sampleRateHz" to it.sampleRateHz,
                    "channels" to it.channels,
                    "bitsPerSample" to it.bitsPerSample,
                    "decodedPcmSizeBytes" to it.decodedPcmSizeBytes,
                    "oggPageCount" to it.oggPageCount
                )
            },
            "musicNameListCount" to musicNames.size,
            "musicNameListTruncated" to (musicNames.size > MAX_ASSET_INVENTORY_ENTRIES),
            "musicNameList" to musicNames.take(MAX_ASSET_INVENTORY_ENTRIES).map {
                mapOf("slot" to it.first, "resourceName" to it.second)
            },
            "soundNameListCount" to soundNames.size,
            "soundNameListTruncated" to (soundNames.size > MAX_ASSET_INVENTORY_ENTRIES),
            "soundNameList" to soundNames.take(MAX_ASSET_INVENTORY_ENTRIES).map {
                mapOf("slot" to it.first, "resourceName" to it.second)
            },
            "unindexedVawFiles" to catalog.unindexedVawFiles,
            "unindexedVpkFiles" to catalog.unindexedVpkFiles,
            "unindexedMmlFiles" to catalog.unindexedMmlFiles,
            "unindexedWgqFiles" to catalog.unindexedWgqFiles,
            "warnings" to catalog.warnings
        )
    }

    private fun discoverFiles(root: File, onProgress: (Int) -> Unit): List<File> {
        var visitedFiles = 0
        val rootDepth = root.toPath().nameCount
        val discovered = root.walkTopDown()
            .onEnter { directory ->
                val isSymbolicLink = Files.isSymbolicLink(directory.toPath())
                val ignored = directory != root && isIgnoredDirectory(directory.name)
                if (!isSymbolicLink && !ignored) {
                    val depth = directory.toPath().nameCount - rootDepth
                    if (depth >= MAX_SCAN_DEPTH - 1) {
                        val hasDeeperDirectory = directory.listFiles()?.any { child ->
                            child.isDirectory && !Files.isSymbolicLink(child.toPath()) &&
                                !isIgnoredDirectory(child.name)
                        } == true
                        if (hasDeeperDirectory) fileWalkDepthLimited = true
                    }
                }
                !isSymbolicLink && !ignored
            }
            .maxDepth(MAX_SCAN_DEPTH)
            .filter { file -> file.isFile && !Files.isSymbolicLink(file.toPath()) }
            .onEach {
                visitedFiles += 1
                if (visitedFiles % 64 == 0) onProgress(visitedFiles)
            }
            .take(MAX_DISCOVERED_FILES + 1)
            .toList()
        onProgress(visitedFiles)
        if (discovered.size > MAX_DISCOVERED_FILES) {
            fileWalkTruncated = true
        }
        return discovered.take(MAX_DISCOVERED_FILES)
            .sortedBy { relativePath(root, it) }
    }

    private fun isIgnoredDirectory(name: String): Boolean {
        val value = name.lowercase(Locale.ROOT)
        return value in IGNORED_DIRECTORY_NAMES ||
            value.startsWith("test_") || value.startsWith("test-") ||
            value.endsWith("_test") || value.endsWith("-test")
    }

    private fun resolveAssetRoot(sptDirectory: File): File {
        val parent = sptDirectory.parentFile ?: return sptDirectory
        return if (parent.name.equals(ALTERNATE_PROJECT_DIRECTORY, true)) {
            parent.parentFile ?: parent
        } else {
            parent
        }
    }

    private fun addReference(
        kind: String,
        resource: String,
        status: String,
        module: String,
        file: String,
        offset: Long
    ) {
        val key = ReferenceKey(kind, resource, status)
        val stats = referenceStats.getOrPut(key) { ReferenceStats() }
        stats.count += 1
        if (stats.locations.size < maxIssueLocations) {
            stats.locations += mapOf(
                "module" to module,
                "file" to file,
                "offset" to hexLong(offset)
            )
        }
    }

    private fun addIssue(
        code: String,
        message: String,
        file: String?,
        offset: Long?
    ) {
        val safe = message.replace('\n', ' ').replace('\r', ' ').take(400)
        val key = code + "|" + safe
        val stats = issueStats.getOrPut(key) { IssueStats(code, safe) }
        stats.count += 1
        if (stats.locations.size < maxIssueLocations) {
            stats.locations += linkedMapOf(
                "file" to file,
                "offset" to offset?.let(::hexLong)
            )
        }
    }

    private fun safeMessage(failure: Throwable, root: File?): String {
        val message = failure.message ?: failure.javaClass.simpleName
        val safe = if (root != null) {
            message.replace(root.canonicalPath, "<game-root>")
        } else {
            message
        }
        return safe.replace('\n', ' ').replace('\r', ' ').take(400)
    }

    private fun relativePath(root: File, file: File): String = runCatching {
        file.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath
    }.getOrDefault(file.name)

    private fun reset() {
        opcodeStats.clear()
        effectStats.clear()
        referenceStats.clear()
        issueStats.clear()
        moduleReports.clear()
        projectReports.clear()
        assetManagers.values.forEach { result ->
            runCatching { result.manager?.close() }
        }
        assetManagers.clear()
        assetCatalogReports.clear()
        globalRecordKinds.clear()
        globalRuntimeCoverage.clear()
        globalTransfers.clear()
        globalSubroutines.clear()
        globalGotos.clear()
        filmCountsByModulePath.clear()
        totalRecords = 0
        totalSptCandidates = 0
        invalidSptCandidates = 0
        fileWalkTruncated = false
        fileWalkDepthLimited = false
        totalModulesScanned = 0
        totalModulesFailed = 0
        totalManifests = 0
        totalScenes = 0
        totalSceneVoiceIds = 0
        totalVariables = 0
    }

    private fun codeName(family: Int, codeId: Int): String = when (family) {
        SYSTEM_FUNCTION_FAMILY -> systemFunctionName(codeId)
        SYSTEM_COMMAND_FAMILY -> when (codeId) {
            0x0001 -> "PRINT"
            0x0002 -> "LPRINT"
            0x0003 -> "APPEND"
            0x0004 -> "DRAW"
            0x0005 -> "OVERRAP"
            0x0006 -> "SELECT"
            else -> "UNKNOWN_SYSTEM_COMMAND"
        }
        CONTROL_FAMILY -> when (codeId) {
            0x0001 -> "NOP"
            0x0002 -> "IF"
            0x0003 -> "ELSIF"
            0x0004 -> "ELSE"
            0x0005 -> "END"
            0x0006 -> "NEXT"
            0x0007 -> "RETURN"
            0x0008 -> "CASE"
            0x0009 -> "SUB"
            0x000A -> "SCRIPT"
            0x000B -> "EXIT"
            0x000C -> "ENDFILM"
            0x000E -> "ENDKOMA"
            0x0013 -> "ENDIF"
            0x0015 -> "SCRIPTJUMP"
            0x0016 -> "SUBSCRIPT"
            0x0020 -> "GOTO"
            else -> "UNKNOWN_CONTROL"
        }
        EXPRESSION_FAMILY -> when (codeId) {
            OPEN_BRACE -> "OPEN_BRACE"
            CLOSE_BRACE -> "CLOSE_BRACE"
            else -> "EXPRESSION_TOKEN"
        }
        else -> "UNKNOWN"
    }

    private fun systemFunctionName(codeId: Int): String = when (codeId) {
        0x0007 -> "MUSIC"
        0x0008 -> "VOICE"
        0x0009 -> "SOUND"
        0x0011 -> "PREPAREOVERRAP"
        0x0012 -> "CLEARALLEFFECT"
        0x0013 -> "SETEFFECT"
        0x0014 -> "CLEAREFFECTLAYER"
        0x0015 -> "LOADDWQ"
        0x0016 -> "SETEFFECTRECT"
        0x0017 -> "NOMESSAGEWINDOW"
        0x0018 -> "SETCG"
        0x0019 -> "SETSCENE"
        0x001A -> "SETFILM"
        0x001B -> "STARTMESSAGE"
        0x001C -> "STARTKOMA"
        0x001D -> "STARTFILM"
        0x001E -> "SETDEMOFLAG"
        0x001F -> "PRELOADDWQ"
        0x0028 -> "FRAMECONTROL"
        0x0029 -> "SETDEFAULTFRAME"
        0x0042 -> "DEBUG"
        0x0050 -> "FILMNAME"
        0x0051 -> "STORYNAME"
        0x0052 -> "FILMTYPETIME"
        0x0053 -> "CONFIGMASK"
        0x0054 -> "WINDOWNUMBER"
        0x0055 -> "MOUSENUMBER"
        0x0056 -> "CURSORNUMBER"
        0x0057 -> "AUTOMESSAGE"
        0x0058 -> "CANNOTCLICK"
        0x0059 -> "CANNOTSKIP"
        0x005A -> "OPTIONOFF"
        0x005B -> "CUTIN"
        0x005C -> "MESSAGEEXPSTATUS"
        0x005D -> "FILMENDENABLE"
        0x005E -> "FILMEXPSTATUS"
        0x005F -> "CHANGEMESSAGEFONTSIZETYPE"
        0x0060 -> "RENAMELAYER"
        0x0061 -> "SETCGBYVAR"
        0x0062 -> "SETVAR"
        0x0063 -> "VARCONTROLLAYER"
        0x0064 -> "FACE"
        0x0065 -> "MUSTFACE"
        0x0066 -> "NEXTFADE_SE"
        0x0067 -> "NEXTFADE_VOICE"
        0x0068 -> "VOLUMEONLY_SE"
        0x0069 -> "VOLUMEONLY_VOICE"
        0x006A -> "MUSICVOLUMEONLY"
        0x006B -> "SETACHIEVEMENT"
        0x006C -> "SETVOICEFLAG"
        0x006D -> "SETTERM"
        0x006E -> "MESSAGEEFFECT"
        0x006F -> "MESSAGEEFFECTTIME"
        else -> "UNKNOWN_SYSTEM_FUNCTION"
    }

    private fun hex(value: Int): String =
        "0x" + value.toString(16).uppercase(Locale.ROOT).padStart(4, '0')

    private fun hexLong(value: Long): String =
        "0x" + value.toString(16).uppercase(Locale.ROOT)

    private companion object {
        const val MAX_ASSET_INVENTORY_ENTRIES = 100_000
        const val MAX_SCAN_DEPTH = 12
        const val MAX_DISCOVERED_FILES = 10_000
        const val MAX_TOTAL_RECORDS = 2_000_000
        const val ALTERNATE_PROJECT_DIRECTORY = "!eng files"
        const val SYSTEM_FUNCTION_FAMILY = 0x3333
        const val SYSTEM_COMMAND_FAMILY = 0x2222
        const val CONTROL_FAMILY = 0x5556
        const val EXPRESSION_FAMILY = 0xA000
        const val PRELOAD_DWQ = 0x001F
        const val PREPARE_OVERRAP = 0x0011
        const val SET_CG = 0x0018
        const val SCREEN_EFFECT_LAYER = 31
        const val OPEN_BRACE = 0x007B
        const val CLOSE_BRACE = 0x007D
        const val CONTROL_GOTO = 0x0020
        val SUPPORTED_CONTROL_IDS = setOf(
            0x0001, 0x0002, 0x0003, 0x0004, 0x0005, 0x0006, 0x0007,
            0x0008, 0x0009, 0x000A, 0x000B, 0x000C, 0x000E, 0x0013,
            0x0015, 0x0016, 0x0020
        )
        val SCREEN_BUFFER_EFFECT_IDS = setOf(
            2, 10, 11, 18, 20, 22, 29, 31, 43, 50, 54, 55, 69, 71, 73, 74, 100
        )
        val IGNORED_DIRECTORY_NAMES = setOf(
            ".git", ".gradle", "cache", "caches", "tmp", "temp",
            "test", "tests", "testdata", "__tests__", "fixtures",
            "local-test", "local-tests", "local_test", "local_tests"
        )
    }
}
