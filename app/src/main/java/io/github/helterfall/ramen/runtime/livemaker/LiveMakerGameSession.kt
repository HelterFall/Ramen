package io.github.helterfall.ramen.runtime.livemaker

import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.LiveMakerArchive
import io.github.helterfall.ramen.inspection.LiveMakerArchiveParser
import io.github.helterfall.ramen.inspection.LiveMakerLsb
import io.github.helterfall.ramen.inspection.LiveMakerLsbParser
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.math.min

/**
 * Supplies compiled pages referenced by LiveMaker Jump/Call commands.
 */
interface LiveMakerScriptRepository : Closeable {
    @Throws(IOException::class)
    fun loadPage(pageReference: String): LiveMakerLsb

    override fun close() = Unit
}

/**
 * Signals that a repository does not contain the requested page.
 * I/O failures and malformed page data must remain ordinary [IOException]s.
 */
class LiveMakerPageNotFoundException(pageReference: String) :
    IOException("LiveMaker page '$pageReference' was not found in the configured repository.")

/** Loads compiled .lsb pages from an already-imported game directory. */
class DirectoryLiveMakerScriptRepository(
    rootDirectory: File,
    private val scriptParser: LiveMakerLsbParser = LiveMakerLsbParser()
) : LiveMakerScriptRepository {
    private val root = rootDirectory.canonicalFile

    init {
        require(root.isDirectory) { "LiveMaker game root must be a directory." }
    }

    override fun loadPage(pageReference: String): LiveMakerLsb {
        val relativePath = liveMakerCompiledPagePath(pageReference)
        val file = findCaseInsensitive(relativePath)
        return FileRandomAccessSource(file).use(scriptParser::parse)
    }

    private fun findCaseInsensitive(relativePath: String): File {
        var current = root
        for (segment in relativePath.split('/')) {
            val children = current.listFiles()
            if (children == null) {
                if (!current.isDirectory) {
                    throw LiveMakerPageNotFoundException(relativePath)
                }
                throw IOException("Unable to list LiveMaker game directory '${current.path}'.")
            }
            current = children.firstOrNull { it.name.equals(segment, ignoreCase = true) }
                ?: throw LiveMakerPageNotFoundException(relativePath)
        }

        val canonical = current.canonicalFile
        if (canonical == root || !canonical.toPath().startsWith(root.toPath())) {
            throw IOException("Resolved LiveMaker page escaped the imported game directory.")
        }
        if (!canonical.isFile) {
            throw LiveMakerPageNotFoundException(relativePath)
        }
        return canonical
    }
}

/**
 * Resolves compiled pages stored as entries in a VF archive, including a VF archive
 * embedded in Game.exe. Only the requested .lsb entry is decompressed into a bounded
 * memory buffer; the complete archive is never extracted.
 */
class VfArchiveLiveMakerScriptRepository(
    private val archiveFile: File,
    private val archiveParser: LiveMakerArchiveParser = LiveMakerArchiveParser(),
    private val scriptParser: LiveMakerLsbParser = LiveMakerLsbParser(),
    private val maxScriptBytes: Long = DEFAULT_MAX_SCRIPT_BYTES
) : LiveMakerScriptRepository {
    private val archive: LiveMakerArchive

    init {
        require(maxScriptBytes > 0L) { "maxScriptBytes must be positive." }
        if (!archiveFile.isFile) throw IOException("LiveMaker VF archive file is unavailable.")
        archive = FileRandomAccessSource(archiveFile).use(archiveParser::parse)
    }

    override fun loadPage(pageReference: String): LiveMakerLsb {
        val target = liveMakerCompiledPagePath(pageReference)
        val entry = archive.entries.firstOrNull { candidate ->
            val candidatePath = runCatching {
                archiveParser.safeRelativePath(candidate)
            }.getOrNull()
            candidatePath != null &&
                candidatePath.equals(target, ignoreCase = true) &&
                candidatePath.endsWith(".lsb", ignoreCase = true)
        } ?: throw LiveMakerPageNotFoundException(pageReference)

        val output = ByteArrayOutputStream()
        FileRandomAccessSource(archiveFile).use { source ->
            archiveParser.copyEntryTo(
                source = source,
                archive = archive,
                entry = entry,
                output = output,
                maxOutputBytes = maxScriptBytes
            )
        }

        return ByteArrayRandomAccessSource(output.toByteArray()).use(scriptParser::parse)
    }

    companion object {
        private const val DEFAULT_MAX_SCRIPT_BYTES = 32L * 1024L * 1024L
    }
}

/** Tries a loose-file repository first, followed by one or more packed VF archives. */
class CompositeLiveMakerScriptRepository(
    private val repositories: List<LiveMakerScriptRepository>
) : LiveMakerScriptRepository {
    override fun loadPage(pageReference: String): LiveMakerLsb {
        var lastMissing: LiveMakerPageNotFoundException? = null
        for (repository in repositories) {
            try {
                return repository.loadPage(pageReference)
            } catch (missing: LiveMakerPageNotFoundException) {
                lastMissing = missing
            }
        }
        throw lastMissing ?: LiveMakerPageNotFoundException(pageReference)
    }

    override fun close() {
        repositories.forEach { runCatching { it.close() } }
    }
}

sealed interface LiveMakerSessionEvent {
    data class EngineEvent(val event: LiveMakerVmEvent) : LiveMakerSessionEvent

    data class PageEntered(
        val pageReference: String,
        val targetCommandIndex: Long,
        val commandIndex: Int,
        val isCall: Boolean,
        val callArguments: List<io.github.helterfall.ramen.inspection.LiveMakerExpression>
    ) : LiveMakerSessionEvent

    /** The host can supply a page from another source by calling provideResolvedPage(). */
    data class PageResolutionRequired(
        val pageReference: String,
        val targetCommandIndex: Long,
        val isCall: Boolean,
        val returnVariable: String?,
        val callArguments: List<io.github.helterfall.ramen.inspection.LiveMakerExpression>
    ) : LiveMakerSessionEvent
}

/**
 * Coordinates a LiveMaker VM with loose-file and VF archive page repositories.
 *
 * In compiled LSB, LabelReference stores a zero-based command-array index. LineNo is only
 * source/debug metadata and is not used to resolve Jump/Call destinations.
 */
class LiveMakerGameSession(
    initialScript: LiveMakerLsb,
    initialPageReference: String,
    private val pageRepository: LiveMakerScriptRepository,
    maxInstructionsPerStep: Int = 10_000
) : Closeable {
    private val vm = LiveMakerVirtualMachine(
        script = initialScript,
        maxInstructionsPerStep = maxInstructionsPerStep,
        initialScriptIdentifier = initialPageReference
    )
    private var pendingTransfer: LiveMakerVmEvent.ScriptTransfer? = null

    init {
        require(initialPageReference.isNotBlank())
        require(initialScript.isComplete) {
            "Cannot start a LiveMaker session with a partial script parse."
        }
    }

    fun variablesSnapshot(): Map<String, Any?> = vm.variablesSnapshot()

    fun currentPageReference(): String? = vm.currentScriptIdentifier()

    fun currentCommandIndex(): Int = vm.currentCommandIndex()

    fun setVariable(name: String, value: Any?) = vm.setVariable(name, value)

    fun evaluateExpression(
        expression: io.github.helterfall.ramen.inspection.LiveMakerExpression
    ): Any? = vm.evaluateExpression(expression)

    fun resume(nextCommandIndex: Int? = null) = vm.resume(nextCommandIndex)

    fun returnFromCall(returnValue: Any? = null): Boolean = vm.returnFromCall(returnValue)

    /**
     * Executes the VM until a platform event or page transition is reached.
     * Page transitions are resolved automatically when the repository contains the page.
     */
    @Throws(IOException::class)
    fun runUntilEvent(): LiveMakerSessionEvent {
        val event = vm.runUntilEvent()
        if (event !is LiveMakerVmEvent.ScriptTransfer) {
            return LiveMakerSessionEvent.EngineEvent(event)
        }

        val pageReference = event.page.ifBlank {
            vm.currentScriptIdentifier().orEmpty()
        }
        val normalizedTransfer = event.copy(page = pageReference)

        if (pageReference.isBlank()) {
            pendingTransfer = normalizedTransfer
            return pageResolutionRequired(normalizedTransfer)
        }

        val destination = try {
            pageRepository.loadPage(pageReference)
        } catch (_: LiveMakerPageNotFoundException) {
            null
        }

        if (destination == null) {
            pendingTransfer = normalizedTransfer
            return pageResolutionRequired(normalizedTransfer)
        }

        return enterTransferredPage(normalizedTransfer, destination)
    }

    /** Supplies a page when configured repositories do not contain the requested target. */
    fun provideResolvedPage(script: LiveMakerLsb): LiveMakerSessionEvent.PageEntered {
        val transfer = pendingTransfer
            ?: throw IllegalStateException("The session is not waiting for a page resolution.")
        return enterTransferredPage(transfer, script)
    }

    private fun pageResolutionRequired(
        transfer: LiveMakerVmEvent.ScriptTransfer
    ) = LiveMakerSessionEvent.PageResolutionRequired(
        pageReference = transfer.page,
        targetCommandIndex = transfer.labelIndex,
        isCall = transfer.isCall,
        returnVariable = transfer.returnVariable,
        callArguments = transfer.arguments
    )

    private fun enterTransferredPage(
        transfer: LiveMakerVmEvent.ScriptTransfer,
        destination: LiveMakerLsb
    ): LiveMakerSessionEvent.PageEntered {
        pendingTransfer = transfer
        if (!destination.isComplete) {
            throw IOException("The destination LiveMaker page has an incomplete LSB parse.")
        }
        val commandIndex = resolveCommandIndex(destination, transfer.labelIndex, transfer.page)
        vm.loadTransferredScript(
            newScript = destination,
            entryCommandIndex = commandIndex,
            isCall = transfer.isCall,
            returnVariable = transfer.returnVariable,
            scriptIdentifier = transfer.page
        )
        pendingTransfer = null
        return LiveMakerSessionEvent.PageEntered(
            pageReference = transfer.page,
            targetCommandIndex = transfer.labelIndex,
            commandIndex = commandIndex,
            isCall = transfer.isCall,
            callArguments = transfer.arguments
        )
    }

    private fun resolveCommandIndex(
        script: LiveMakerLsb,
        commandIndex: Long,
        pageReference: String
    ): Int {
        if (commandIndex < 0L ||
            commandIndex > Int.MAX_VALUE.toLong() ||
            commandIndex > script.commands.size.toLong()
        ) {
            throw IOException(
                "LiveMaker page '$pageReference' has out-of-range command index $commandIndex."
            )
        }
        return commandIndex.toInt()
    }

    override fun close() {
        pageRepository.close()
    }
}

/**
 * LSB files are length-prefixed binary records; this adapter lets the same bounded parser
 * read a single archive entry without staging it on disk.
 */
private class ByteArrayRandomAccessSource(
    private val bytes: ByteArray
) : ReadOnlyRandomAccessSource {
    override val sizeBytes: Long
        get() = bytes.size.toLong()

    override fun readAt(
        position: Long,
        target: ByteArray,
        targetOffset: Int,
        length: Int
    ): Int {
        require(position >= 0L)
        require(targetOffset >= 0 && targetOffset <= target.size)
        require(length >= 0 && length <= target.size - targetOffset)
        if (length == 0) return 0
        if (position >= bytes.size.toLong()) return -1
        val count = min(length, bytes.size - position.toInt())
        System.arraycopy(bytes, position.toInt(), target, targetOffset, count)
        return count
    }

    override fun close() = Unit
}

private fun liveMakerCompiledPagePath(pageReference: String): String {
    val normalized = pageReference.replace('\\', '/')
    if (normalized.isBlank() ||
        normalized.startsWith('/') ||
        '\u0000' in normalized ||
        normalized.any { it.isISOControl() } ||
        DRIVE_PREFIX.matches(normalized)
    ) {
        throw IOException("LiveMaker page reference is empty or absolute.")
    }

    val segments = normalized.split('/')
    if (segments.any { it.isBlank() || it == "." || it == ".." }) {
        throw IOException("LiveMaker page reference contains an unsafe path segment.")
    }

    val name = segments.last()
    return when {
        name.endsWith(".lsb", ignoreCase = true) -> normalized
        name.endsWith(".lsc", ignoreCase = true) -> normalized.dropLast(4) + ".lsb"
        else -> normalized + ".lsb"
    }
}

private val DRIVE_PREFIX = Regex("^[A-Za-z]:")

