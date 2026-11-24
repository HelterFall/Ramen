package io.github.helterfall.ramen.runtime.livemaker

import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.LiveMakerArchive
import io.github.helterfall.ramen.inspection.LiveMakerArchiveEntry
import io.github.helterfall.ramen.inspection.LiveMakerArchiveParser
import io.github.helterfall.ramen.inspection.LiveMakerLsbParser
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset

data class LiveMakerStartupSession(
    val session: LiveMakerGameSession,
    val pageReference: String,
    val scriptVersion: Int,
    val commandCount: Int
)

/** Locates the conventional start page in loose files or VF archives. */
class LiveMakerStartupScriptResolver(
    private val gameDirectory: File,
    private val archiveParser: LiveMakerArchiveParser = LiveMakerArchiveParser(),
    private val scriptParser: LiveMakerLsbParser = LiveMakerLsbParser()
) {
    @Throws(IOException::class)
    fun openSession(
        systemEnvironment: LiveMakerSystemEnvironment = LiveMakerSystemEnvironment(),
        textHistoryHost: LiveMakerTextHistoryHostAdapter = LiveMakerTextHistoryHostAdapter()
    ): LiveMakerStartupSession {
        val root = gameDirectory.canonicalFile
        if (!root.isDirectory) throw IOException("LiveMaker game directory is unavailable.")

        val repositories = mutableListOf<LiveMakerScriptRepository>()
        repositories += DirectoryLiveMakerScriptRepository(root, scriptParser)
        val archiveFiles = root.walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .filter { it.isFile && it.extension.lowercase() in ARCHIVE_EXTENSIONS }
            .take(MAX_DISCOVERED_FILES)
            .toList()
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }

        for (archiveFile in archiveFiles) {
            val repository = try {
                VfArchiveLiveMakerScriptRepository(archiveFile, archiveParser, scriptParser)
            } catch (_: Exception) {
                continue
            }
            if (repository.pageReferences().isNotEmpty()) repositories += repository
            else repository.close()
        }

        val composite = CompositeLiveMakerScriptRepository(repositories)
        val pageReference = selectStartupPage(composite.pageReferences())
        if (pageReference == null) {
            composite.close()
            throw IOException("В импортированной папке не найден корректный файл LiveMaker .lsb.")
        }

        val suppliedFileResolver = systemEnvironment.fileExistsResolver
        systemEnvironment.fileExistsResolver = { requested ->
            val normalized = requested.trim().replace('\\', '/').trimStart('/')
            val safeRelative = normalized.isNotEmpty() &&
                normalized.split('/').none { it.isBlank() || it == "." || it == ".." }
            val looseFileExists = if (safeRelative) {
                runCatching {
                    val candidate = File(root, normalized).canonicalFile
                    candidate.toPath().startsWith(root.toPath()) && candidate.isFile
                }.getOrDefault(false)
            } else {
                false
            }
            looseFileExists ||
                composite.pageReferences().any { it.equals(normalized, ignoreCase = true) } ||
                runCatching { suppliedFileResolver(requested) }.getOrDefault(false)
        }

        val suppliedTextFileResolver = systemEnvironment.readTextFileResolver
        systemEnvironment.readTextFileResolver = { requested ->
            val normalized = requested.trim().replace('\\', '/').trimStart('/')
            val safeRelative = normalized.isNotEmpty() &&
                normalized.split('/').none { it.isBlank() || it == "." || it == ".." }
            val looseText = if (safeRelative) {
                runCatching {
                    val candidate = File(root, normalized).canonicalFile
                    if (candidate.toPath().startsWith(root.toPath()) &&
                        candidate.isFile && candidate.length() <= MAX_TEXT_FILE_BYTES
                    ) {
                        val decoded = String(candidate.readBytes(), Charset.forName("windows-31j"))
                        decoded.replace("\\r\\n", "\\n").replace('\\r', '\\n').split('\\n')
                    } else {
                        null
                    }
                }.getOrNull()
            } else {
                null
            }
            looseText ?: runCatching { suppliedTextFileResolver(requested) }.getOrNull()
        }

        return try {
            val script = composite.loadPage(pageReference)
            LiveMakerStartupSession(
                LiveMakerGameSession(
                    script,
                    pageReference,
                    composite,
                    systemEnvironment = systemEnvironment,
                    textHistoryHost = textHistoryHost
                ),
                pageReference,
                script.version,
                script.commands.size
            )
        } catch (error: Exception) {
            composite.close()
            if (error is IOException) throw error
            throw IOException("Не удалось загрузить стартовый LiveMaker LSB.", error)
        }
    }

    companion object {
        private const val MAX_SCAN_DEPTH = 12
        private const val MAX_DISCOVERED_FILES = 10_000
        private const val MAX_TEXT_FILE_BYTES = 8L * 1024L * 1024L
        private val ARCHIVE_EXTENSIONS = setOf("exe", "dat", "vf", "ext")

        /** Prefer ゲームメイン.lsb, then ノベルシステム/START.lsb, then the first LSB. */
        fun selectStartupPage(pageReferences: List<String>): String? {
            val candidates = pageReferences
                .map { it.replace('\\', '/') }
                .filter { it.endsWith(".lsb", ignoreCase = true) }
                .distinct()
            return candidates.minWithOrNull(
                compareBy<String> { path ->
                    when {
                        path.substringAfterLast('/').equals("ゲームメイン.lsb", ignoreCase = true) -> 0
                        path.equals("ノベルシステム/START.lsb", ignoreCase = true) -> 1
                        else -> 2
                    }
                }.thenBy { it.lowercase() }.thenBy { it }
            )
        }
    }
}

/** A bounded media asset from a loose file or an indexed LiveMaker VF archive. */
data class LiveMakerMediaAsset internal constructor(
    val id: String,
    val path: String,
    val extension: String,
    val storageDescription: String
)

/**
 * Visible scan counters make archive-indexing failures diagnosable on-device without
 * extracting or opening the media payloads.
 */
data class LiveMakerMediaIndexDiagnostics(
    val filesScanned: Int,
    val scanLimitReached: Boolean,
    val archiveCandidates: Int,
    val archivesParsed: Int,
    val archiveEntriesVisited: Int,
    val rejectedArchivePaths: Int,
    val archiveParseFailures: Int,
    val looseAssetCount: Int,
    val archiveAssetCount: Int,
    val indexedAssetCount: Int,
    val indexedByExtension: Map<String, Int>,
    val firstProblem: String?
)

/**
 * Indexes GAL/PNG and OGG/WAV without extracting an archive wholesale.
 * All reads, including archive decompression, are bounded by maxAssetBytes.
 */
class LiveMakerMediaAssetRepository(
    gameDirectory: File,
    private val maxAssetBytes: Long = DEFAULT_MAX_ASSET_BYTES,
    private val archiveParser: LiveMakerArchiveParser = LiveMakerArchiveParser()
) : Closeable {
    private data class AssetSource(
        val file: File,
        val archive: LiveMakerArchive? = null,
        val entry: LiveMakerArchiveEntry? = null
    )

    private val root = gameDirectory.canonicalFile
    private val sources = linkedMapOf<String, AssetSource>()
    private val assets = mutableListOf<LiveMakerMediaAsset>()
    val diagnostics: LiveMakerMediaIndexDiagnostics

    init {
        require(maxAssetBytes > 0L)
        if (!root.isDirectory) throw IOException("LiveMaker game directory is unavailable.")

        val files = root.walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .filter { it.isFile }
            .take(MAX_DISCOVERED_FILES)
            .toList()
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }

        var archiveCandidates = 0
        var archivesParsed = 0
        var archiveEntriesVisited = 0
        var rejectedArchivePaths = 0
        var archiveParseFailures = 0
        var looseAssetCount = 0
        var archiveAssetCount = 0
        var firstProblem: String? = null
        val indexedByExtension = linkedMapOf<String, Int>()

        fun recordIndexedAsset(extension: String, archive: Boolean) {
            indexedByExtension[extension] = (indexedByExtension[extension] ?: 0) + 1
            if (archive) archiveAssetCount += 1 else looseAssetCount += 1
        }

        for (file in files) {
            val extension = file.extension.lowercase()
            if (extension !in MEDIA_EXTENSIONS) continue
            val canonical = file.canonicalFile
            if (!canonical.toPath().startsWith(root.toPath()) || canonical.length() > maxAssetBytes) {
                continue
            }
            val relative = canonical.relativeTo(root).invariantSeparatorsPath
            val id = "file:" + canonical.absolutePath
            val previousSize = assets.size
            addAsset(
                LiveMakerMediaAsset(id, relative, extension, "Файл"),
                AssetSource(canonical)
            )
            if (assets.size != previousSize) recordIndexedAsset(extension, archive = false)
        }

        for (archiveFile in files) {
            if (archiveFile.extension.lowercase() !in ARCHIVE_EXTENSIONS) continue
            archiveCandidates += 1
            val parsed = try {
                FileRandomAccessSource(archiveFile).use(archiveParser::parse)
            } catch (error: Exception) {
                archiveParseFailures += 1
                if (firstProblem == null) {
                    firstProblem = archiveFile.name + ": " +
                        (error.message ?: error.javaClass.simpleName)
                }
                continue
            }
            archivesParsed += 1
            for (entry in parsed.entries) {
                archiveEntriesVisited += 1
                val relative = try {
                    archiveParser.safeRelativePath(entry)
                } catch (error: IOException) {
                    rejectedArchivePaths += 1
                    if (firstProblem == null) {
                        firstProblem = entry.name + ": " +
                            (error.message ?: "unsafe archive path")
                    }
                    continue
                }
                val extension = relative.substringAfterLast('.', "").lowercase()
                if (extension !in MEDIA_EXTENSIONS) continue
                val id = "archive:" + archiveFile.absolutePath + "!/" + relative
                val previousSize = assets.size
                addAsset(
                    LiveMakerMediaAsset(id, relative, extension, archiveFile.name + " · VF"),
                    AssetSource(archiveFile.canonicalFile, parsed, entry)
                )
                if (assets.size != previousSize) recordIndexedAsset(extension, archive = true)
            }
        }

        diagnostics = LiveMakerMediaIndexDiagnostics(
            filesScanned = files.size,
            scanLimitReached = files.size >= MAX_DISCOVERED_FILES,
            archiveCandidates = archiveCandidates,
            archivesParsed = archivesParsed,
            archiveEntriesVisited = archiveEntriesVisited,
            rejectedArchivePaths = rejectedArchivePaths,
            archiveParseFailures = archiveParseFailures,
            looseAssetCount = looseAssetCount,
            archiveAssetCount = archiveAssetCount,
            indexedAssetCount = assets.size,
            indexedByExtension = indexedByExtension.toMap(),
            firstProblem = firstProblem
        )
    }

    fun listAssets(): List<LiveMakerMediaAsset> = assets.toList()

    /**
     * Resolves an engine resource name against indexed loose/VF assets.
     * Ambiguous basename-only matches are rejected instead of picking arbitrarily.
     */
    fun findAsset(resourceName: String): LiveMakerMediaAsset? {
        val normalized = resourceName.trim().replace('\\', '/').trimStart('/')
        if (normalized.isEmpty()) return null
        val exact = assets.filter { it.path.equals(normalized, ignoreCase = true) }
        if (exact.size == 1) return exact.single()
        if (exact.size > 1) return null

        val suffix = assets.filter {
            it.path.endsWith("/" + normalized, ignoreCase = true)
        }
        if (suffix.size == 1) return suffix.single()
        if (suffix.size > 1) return null

        val requestedName = normalized.substringAfterLast('/')
        val byName = assets.filter {
            it.path.substringAfterLast('/').equals(requestedName, ignoreCase = true)
        }
        if (byName.size == 1) return byName.single()
        if (byName.size > 1) return null

        val requestedStem = requestedName.substringBeforeLast('.', requestedName)
        val byStem = assets.filter {
            it.path.substringAfterLast('/').substringBeforeLast('.') .equals(
                requestedStem,
                ignoreCase = true
            )
        }
        return byStem.singleOrNull()
    }

    @Throws(IOException::class)
    fun readAsset(asset: LiveMakerMediaAsset): ByteArray {
        val source = sources[asset.id]
            ?: throw IOException("LiveMaker media asset does not belong to this repository.")
        if (source.archive == null || source.entry == null) {
            if (!source.file.isFile || source.file.length() > maxAssetBytes) {
                throw IOException("LiveMaker media file is unavailable or exceeds the size limit.")
            }
            return source.file.inputStream().use { input ->
                ByteArrayOutputStream().use { output ->
                    copyBounded(input, output, maxAssetBytes)
                    output.toByteArray()
                }
            }
        }

        val output = ByteArrayOutputStream()
        FileRandomAccessSource(source.file).use { randomAccess ->
            archiveParser.copyEntryTo(
                source = randomAccess,
                archive = source.archive,
                entry = source.entry,
                output = output,
                maxOutputBytes = maxAssetBytes
            )
        }
        return output.toByteArray()
    }

    /** Stages one selected OGG/WAV file; the caller owns and deletes the returned file. */
    @Throws(IOException::class)
    fun stageAudio(asset: LiveMakerMediaAsset, cacheDirectory: File): File {
        if (asset.extension !in AUDIO_EXTENSIONS) {
            throw IOException("Only OGG and WAV assets can be staged for audio playback.")
        }
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw IOException("Could not create the LiveMaker audio cache directory.")
        }
        val staged = File.createTempFile("livemaker-audio-", "." + asset.extension, cacheDirectory)
        try {
            staged.outputStream().use { it.write(readAsset(asset)) }
            return staged
        } catch (error: Exception) {
            staged.delete()
            if (error is IOException) throw error
            throw IOException("Could not stage the LiveMaker audio asset.", error)
        }
    }

    private fun addAsset(asset: LiveMakerMediaAsset, source: AssetSource) {
        if (sources.putIfAbsent(asset.id, source) == null) assets += asset
    }

    override fun close() = Unit

    private fun copyBounded(input: java.io.InputStream, output: OutputStream, limit: Long) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (total > limit - count.toLong()) {
                throw IOException("LiveMaker media asset exceeds the configured size limit.")
            }
            output.write(buffer, 0, count)
            total += count.toLong()
        }
    }

    companion object {
        private const val DEFAULT_MAX_ASSET_BYTES = 64L * 1024L * 1024L
        private const val MAX_SCAN_DEPTH = 12
        private const val MAX_DISCOVERED_FILES = 10_000
        private const val COPY_BUFFER_BYTES = 16 * 1024
        private val ARCHIVE_EXTENSIONS = setOf("exe", "dat", "vf", "ext")
        private val MEDIA_EXTENSIONS = setOf("gal", "png", "ogg", "wav")
        private val AUDIO_EXTENSIONS = setOf("ogg", "wav")
    }
}
