package io.github.helterfall.ramen.runtime.hsp3

import io.github.helterfall.ramen.inspection.DpmxCipherKey
import io.github.helterfall.ramen.inspection.DpmxContainer
import io.github.helterfall.ramen.inspection.DpmxContainerParser
import io.github.helterfall.ramen.inspection.DpmxDecryptingInputStream
import io.github.helterfall.ramen.inspection.DpmxDecryptor
import io.github.helterfall.ramen.inspection.DpmxFileEntry
import io.github.helterfall.ramen.inspection.DpmxKeyResolution
import io.github.helterfall.ramen.inspection.DpmxKeyResolver
import io.github.helterfall.ramen.inspection.DpmxResourceValidationReport
import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import io.github.helterfall.ramen.parser.Hsp3BytecodeLoader
import io.github.helterfall.ramen.parser.Hsp3Program
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.FileInputStream
import java.io.FilterInputStream
import java.util.Locale
import java.io.RandomAccessFile

fun interface Hsp3InstructionDecoder {
    /**
     * Converts validated CS tokens and their DS/OT references into the
     * renderer-neutral HSP instruction IR. Implementations must reject
     * unsupported token/argument forms instead of guessing operand widths.
     */
    fun decode(program: Hsp3Program): List<Hsp3Instruction>
}

data class Hsp3PreparedGame(
    val executable: File,
    val container: DpmxContainer,
    val scriptEntry: DpmxFileEntry?,
    val scriptKey: DpmxKeyResolution?,
    val program: Hsp3Program,
    /** Non-null when the program was loaded from an unpacked local start.ax. */
    val unpackedScriptFile: File? = null,
    val resourceValidation: DpmxResourceValidationReport = DpmxResourceValidationReport(
        totalEntries = 0,
        unencryptedEntries = 0,
        signatureValidatedEntries = 0,
        keyDerivedEntries = 0,
        unresolvedEncryptedEntries = 0,
        archiveChecksumValid = null
    )
)

/** Opens one DPMX resource at a time and decrypts it directly into the consumer. */
class DpmxAssetRepository(
    private val executable: File,
    private val container: DpmxContainer,
    private val keyResolver: DpmxKeyResolver,
    assetRoot: File? = null
) : Closeable, Hsp3FileStorage {
    private val executableDirectory: File = executable.canonicalFile.parentFile
        ?: throw IOException("The HSP executable directory is unavailable.")
    private val gameRoot: File = (assetRoot ?: executableDirectory).canonicalFile
    private val localSearchRoots: List<File> = listOf(gameRoot, executableDirectory)
        .distinctBy { it.path }

    init {
        if (!gameRoot.isDirectory) throw IOException("The imported HSP game directory is unavailable.")
        if (!gameRoot.toPath().startsWith(executableDirectory.toPath()) &&
            !executableDirectory.toPath().startsWith(gameRoot.toPath())
        ) {
            throw IOException("The executable and HSP resource directory are outside the same imported game.")
        }
    }

    fun open(name: String): InputStream? {
        val normalized = normalizeRelativeName(name)
        for (root in localSearchRoots) {
            val local = resolveExistingFile(root, normalized)
            if (local?.isFile == true) {
                return BoundedInputStream(FileInputStream(local), MAX_FILE_BYTES)
            }
        }

        val entry = container.entries.firstOrNull {
            normalizeArchiveName(it.name).equals(normalized, ignoreCase = true)
        } ?: return null
        val source = FileRandomAccessSource(executable)
        return try {
            val resolution = keyResolver.resolve(source, container, entry)
                ?: throw IOException("DPMX key resolution failed for HSP asset '" + entry.name + "'.")
            val decodedStream = if (
                resolution.method == DpmxKeyResolution.Method.UNENCRYPTED_ENTRY
            ) {
                object : FilterInputStream(container.openEntryStream(source, entry)) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            source.close()
                        }
                    }
                }
            } else {
                DpmxDecryptingInputStream(source, entry, resolution.key)
            }
            BoundedInputStream(decodedStream, MAX_FILE_BYTES)
        } catch (error: Exception) {
            source.close()
            throw error
        }
    }

    override fun size(name: String): Long? {
        val normalized = normalizeRelativeName(name)
        for (root in localSearchRoots) {
            val local = resolveExistingFile(root, normalized)
            if (local?.isFile == true) return local.length()
        }
        return container.entries.firstOrNull {
            normalizeArchiveName(it.name).equals(normalized, ignoreCase = true)
        }?.sizeBytes
    }

    override fun read(name: String): ByteArray? {
        val stream = open(name) ?: return null
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_FILE_BYTES) {
                    throw IOException("HSP file read exceeds the configured size limit.")
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    override fun write(name: String, offset: Int, bytes: ByteArray) {
        if (offset < 0 || bytes.size > MAX_FILE_BYTES ||
            offset.toLong() + bytes.size.toLong() > MAX_FILE_BYTES
        ) {
            throw IOException("HSP bsave request exceeds the configured file bounds.")
        }
        val normalized = normalizeRelativeName(name)
        val target = resolveWritableFile(normalized)
        val parent = target.parentFile ?: throw IOException("HSP save path has no parent directory.")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create the HSP save directory.")
        }
        val checkedTarget = target.canonicalFile
        if (!checkedTarget.toPath().startsWith(gameRoot.toPath())) {
            throw IOException("HSP save path escapes the imported game directory.")
        }
        if (offset > 0 && !checkedTarget.isFile) {
            throw IOException("HSP bsave with a non-zero file offset requires an existing file.")
        }
        RandomAccessFile(checkedTarget, "rw").use { output ->
            if (offset == 0) output.setLength(0L)
            output.seek(offset.toLong())
            output.write(bytes)
            if (output.length() > MAX_FILE_BYTES) {
                throw IOException("HSP save file exceeds the configured size limit.")
            }
        }
    }

    private fun resolveExistingFile(root: File, normalized: String): File? {
        var current = root
        for (segment in normalized.split('/')) {
            val exact = File(current, segment)
            val child = if (exact.exists()) {
                exact
            } else {
                current.listFiles()
                    ?.asSequence()
                    ?.filter { it.name.equals(segment, ignoreCase = true) }
                    ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                    ?.firstOrNull()
                    ?: return null
            }
            val canonical = child.canonicalFile
            if (!canonical.toPath().startsWith(root.toPath())) {
                throw IOException("HSP file path escapes the imported game directory.")
            }
            current = canonical
        }
        if (current == root) throw IOException("An HSP asset path resolved to the game directory.")
        return current
    }

    private fun resolveWritableFile(normalized: String): File {
        val target = File(gameRoot, normalized.replace('/', File.separatorChar)).canonicalFile
        if (target == gameRoot || !target.toPath().startsWith(gameRoot.toPath())) {
            throw IOException("HSP save path escapes the imported game directory.")
        }
        return target
    }

    private fun normalizeRelativeName(name: String): String {
        val value = name.replace(92.toChar(), '/').trim()
        if (value.isBlank() || value.startsWith("/") || value.contains(':') ||
            value.indexOf(0.toChar()) >= 0
        ) {
            throw IOException("Invalid HSP relative file name.")
        }
        val parts = value.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." || it.indexOf(0.toChar()) >= 0 }) {
            throw IOException("Invalid HSP relative file name.")
        }
        return parts.joinToString("/")
    }

    private fun normalizeArchiveName(name: String): String =
        name.replace(92.toChar(), '/').trim().trimStart('/').split('/')
            .filter { it.isNotEmpty() && it != "." }.joinToString("/")

    override fun close() = Unit

    private class BoundedInputStream(
        input: InputStream,
        private val maximumBytes: Long
    ) : FilterInputStream(input) {
        private var consumedBytes = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) account(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) account(count.toLong())
            return count
        }

        private fun account(count: Long) {
            if (count > maximumBytes - consumedBytes) {
                throw IOException(
                    "HSP asset exceeds the configured read limit of " +
                        (maximumBytes / (1024 * 1024)) + " MiB."
                )
            }
            consumedBytes += count
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 32L * 1024L * 1024L
    }
}

/**
 * Ramen-side HSP3 adapter boundary for PE32+DPMX executables.
 *
 * prepare() identifies and decrypts start.ax in a bounded in-memory script
 * buffer, then hands that bytecode to Hsp3BytecodeLoader. createSession()
 * defaults to the OpenHSP CS decoder; an alternate decoder can still be
 * injected for validation or future compatibility profiles.
 */
class Hsp3RuntimeAdapter(
    private val containerParser: DpmxContainerParser = DpmxContainerParser(),
    private val keyResolver: DpmxKeyResolver = DpmxKeyResolver(),
    private val bytecodeLoader: Hsp3BytecodeLoader = Hsp3BytecodeLoader(),
    private val maxScriptSizeBytes: Long = DEFAULT_MAX_SCRIPT_SIZE_BYTES
) {
    init {
        require(maxScriptSizeBytes >= Hsp3BytecodeLoader.HSPHED_SIZE_BYTES)
        require(maxScriptSizeBytes <= Int.MAX_VALUE.toLong())
    }

    @Throws(IOException::class)
    fun prepare(executable: File, assetRoot: File? = null): Hsp3PreparedGame =
        prepareInternal(executable, assetRoot, validateRuntimeProfile = true)

    /**
     * Loads start.ax for analysis only. An unpacked local start.ax is preferred
     * when present; otherwise the encrypted DPMX entry is resolved.
     */
    @Throws(IOException::class)
    fun prepareForStaticScan(
        executable: File,
        assetRoot: File? = null
    ): Hsp3PreparedGame =
        prepareInternal(executable, assetRoot, validateRuntimeProfile = false)

    private fun prepareInternal(
        executable: File,
        assetRoot: File?,
        validateRuntimeProfile: Boolean
    ): Hsp3PreparedGame {
        if (!executable.isFile) throw IOException("The HSP3 executable is not a regular file.")
        val canonicalExecutable = executable.canonicalFile
        val localScript = findLocalStartAx(canonicalExecutable, assetRoot)
        FileRandomAccessSource(canonicalExecutable).use { source ->
            val container = try {
                containerParser.parse(source)
            } catch (error: Exception) {
                if (localScript == null) {
                    throw IOException(
                        "PE/DPMX parsing failed and no unpacked start.ax is available: " +
                            "${error.message ?: error.javaClass.simpleName}",
                        error
                    )
                }
                emptyDpmxContainer()
            }

            val archiveEntry = container.findEntry(START_SCRIPT_NAME)
            var localScriptFailure: Exception? = null
            if (localScript != null) {
                val localProgram = try {
                    if (localScript.length() > maxScriptSizeBytes) {
                        throw IOException("The unpacked start.ax exceeds the configured size limit.")
                    }
                    bytecodeLoader.load(localScript)
                } catch (error: Exception) {
                    localScriptFailure = error
                    null
                }
                if (localProgram != null) {
                    validateRuntimeProfile(localProgram, validateRuntimeProfile)
                    keyResolver.preferHspCipherVersion(container, localProgram.header.version)
                    val resourceValidation = keyResolver.verifyArchiveResources(source, container)
                    return Hsp3PreparedGame(
                        executable = canonicalExecutable,
                        container = container,
                        scriptEntry = archiveEntry,
                        scriptKey = null,
                        program = localProgram,
                        unpackedScriptFile = localScript,
                        resourceValidation = resourceValidation
                    )
                }
            }

            val entry = archiveEntry ?: throw IOException(
                localScriptFailure?.let {
                    "The game has no DPMX start.ax and its unpacked start.ax is invalid: ${it.message}"
                } ?: "Neither a DPMX start.ax entry nor a valid unpacked start.ax was found."
            )
            if (entry.sizeBytes > maxScriptSizeBytes) {
                throw IOException("DPMX start.ax size validation failed: the decoded script exceeds the configured limit.")
            }
            val resolution = try {
                keyResolver.resolve(source, container, entry)
                    ?: throw IOException("A verified DPMX decryption key was not found.")
            } catch (error: Exception) {
                throw IOException("DPMX start.ax key resolution failed: ${error.message ?: error.javaClass.simpleName}", error)
            }

            val program = try {
                val scriptBytes = if (
                    resolution.method == DpmxKeyResolution.Method.UNENCRYPTED_ENTRY
                ) {
                    val size = entry.sizeBytes.toInt()
                    ByteArray(size).also { source.readFullyAt(entry.absoluteOffset, it) }
                } else {
                    ByteArrayOutputStream(entry.sizeBytes.toInt()).also { output ->
                        DpmxDecryptor.decrypt(source, entry, resolution.key, output)
                    }.toByteArray()
                }
                bytecodeLoader.load(ByteArrayRandomAccessSource(scriptBytes))
            } catch (error: Exception) {
                throw IOException("HSPHED/CS/DS/OT/DI loading failed: ${error.message ?: error.javaClass.simpleName}", error)
            }

            validateRuntimeProfile(program, validateRuntimeProfile)
            val resourceValidation = keyResolver.verifyArchiveResources(source, container)
            return Hsp3PreparedGame(
                executable = canonicalExecutable,
                container = container,
                scriptEntry = entry,
                scriptKey = resolution,
                program = program,
                resourceValidation = resourceValidation
            )
        }
    }

    private fun findLocalStartAx(executable: File, assetRoot: File?): File? {
        val executableDirectory = executable.canonicalFile.parentFile
            ?: throw IOException("The HSP executable directory is unavailable.")
        val gameRoot = (assetRoot ?: executableDirectory).canonicalFile
        if (!gameRoot.isDirectory) throw IOException("The imported HSP game directory is unavailable.")
        if (!gameRoot.toPath().startsWith(executableDirectory.toPath()) &&
            !executableDirectory.toPath().startsWith(gameRoot.toPath())
        ) {
            throw IOException("The executable and unpacked start.ax are outside the same imported game.")
        }

        for (root in listOf(gameRoot, executableDirectory).distinctBy { it.path }) {
            val exact = File(root, START_SCRIPT_NAME)
            val candidate = if (exact.exists()) {
                exact
            } else {
                root.listFiles()?.firstOrNull {
                    it.name.equals(START_SCRIPT_NAME, ignoreCase = true)
                } ?: continue
            }
            if (!candidate.isFile) continue
            val canonical = candidate.canonicalFile
            if (!canonical.toPath().startsWith(root.toPath())) {
                throw IOException("The unpacked start.ax resolves outside the imported game directory.")
            }
            return canonical
        }
        return null
    }

    private fun validateRuntimeProfile(program: Hsp3Program, enabled: Boolean) {
        if (enabled && (
                program.header.libraryInfoSizeBytes != 0L ||
                    program.header.functionInfoSizeBytes != 0L ||
                    program.header.moduleInfoSizeBytes != 0L ||
                    program.header.functionInfo2SizeBytes != 0L ||
                    program.header.hasExternalPluginData
                )
        ) {
            throw IOException(
                "HSP3 external-library validation failed: this runtime profile does not load DLL/HPI plugin segments."
            )
        }
    }

    @Throws(IOException::class)
    fun createSession(
        executable: File,
        instructionDecoder: Hsp3InstructionDecoder = Hsp3BytecodeDecoder(),
        input: Hsp3InputProvider = Hsp3InputProvider { 0 },
        assetRoot: File? = null,
        audio: Hsp3AudioPlayer = Hsp3UnavailableAudioPlayer
    ): Hsp3RuntimeSession {
        val prepared = prepare(executable, assetRoot)
        val assets = DpmxAssetRepository(prepared.executable, prepared.container, keyResolver, assetRoot)
        try {
            val decodedInstructions = instructionDecoder.decode(prepared.program)
            val renderer = Hsp3CanvasRenderer(openAsset = assets::open)
            val vm = Hsp3VirtualMachine(
                renderer, input, fileStorage = assets, audio = audio
            )
            return Hsp3RuntimeSession(prepared, decodedInstructions, assets, renderer, vm, audio)
        } catch (error: Exception) {
            assets.close()
            audio.close()
            throw error
        }
    }

    private fun emptyDpmxContainer() = DpmxContainer(
        peOffset = 0L,
        imageBase = 0L,
        sections = emptyList(),
        overlayOffset = 0L,
        dataOffset = 0L,
        dataAbsoluteOffset = 0L,
        fileCount = 0,
        optionalNameLength = 0,
        entries = emptyList()
    )

    companion object {
        const val ENGINE_ID = "hsp3-dpmx"
        const val ENGINE_LABEL = "Hot Soup Processor 3.1 (DPMX)"
        const val START_SCRIPT_NAME = "start.ax"
        private const val DEFAULT_MAX_SCRIPT_SIZE_BYTES = 16L * 1024L * 1024L
    }
}

/** Owns the renderer and VM for one Android presentation session. */
class Hsp3RuntimeSession internal constructor(
    val preparedGame: Hsp3PreparedGame,
    val instructions: List<Hsp3Instruction>,
    private val assets: DpmxAssetRepository,
    val renderer: Hsp3CanvasRenderer,
    val virtualMachine: Hsp3VirtualMachine,
    private val audio: Hsp3AudioPlayer
) : Closeable {
    fun runSlice(
        instructionBudget: Int = 10_000,
        includeState: Boolean = true
    ): Hsp3VmSnapshot = virtualMachine.runSlice(
        instructions, instructionBudget, includeState
    )

    override fun close() {
        renderer.close()
        assets.close()
        audio.close()
    }
}

/**
 * HSP3 token type and graphics command IDs observed in OpenHSP's dispatcher.
 * Values are identifiers only; parameter decoding is deliberately separate.
 */
object Hsp3CommandIds {
    const val TYPE_EXTCMD = 9
    const val TYPE_PROGCMD = 15

    const val MMLOAD = 0x08
    const val MMPLAY = 0x09
    const val MMSTOP = 0x0A
    const val MES = 0x0F
    const val TITLE = 0x10
    const val POS = 0x11
    const val CLS = 0x13
    const val FONT = 0x14
    const val PICLOAD = 0x17
    const val COLOR = 0x18
    const val PALCOLOR = 0x19
    const val PALETTE = 0x1A
    const val REDRAW = 0x1B
    const val WIDTH = 0x1C
    const val GSEL = 0x1D
    const val GCOPY = 0x1E
    const val GZOOM = 0x1F
    const val GMODE = 0x20
    const val BUFFER = 0x29
    const val SCREEN = 0x2A
    const val BOXF = 0x31
    const val STICK = 0x34
    const val GRECT = 0x35
    const val GETKEY = 0x23
    const val CELLLOAD = 0x3C
    const val CELDIV = 0x3D
    const val CELPUT = 0x3E
    const val GROTATE = 0x36
}

private class ByteArrayRandomAccessSource(
    private val bytes: ByteArray
) : ReadOnlyRandomAccessSource {
    private var closed = false

    override val sizeBytes: Long
        get() = bytes.size.toLong()

    override fun readAt(
        position: Long,
        target: ByteArray,
        targetOffset: Int,
        length: Int
    ): Int {
        check(!closed) { "The in-memory HSP source is closed." }
        require(position >= 0L)
        require(targetOffset >= 0 && targetOffset <= target.size)
        require(length >= 0 && length <= target.size - targetOffset)
        if (length == 0) return 0
        if (position >= bytes.size.toLong()) return -1
        val count = minOf(length.toLong(), bytes.size.toLong() - position).toInt()
        bytes.copyInto(
            destination = target,
            destinationOffset = targetOffset,
            startIndex = position.toInt(),
            endIndex = position.toInt() + count
        )
        return count
    }

    override fun close() {
        closed = true
    }
}
