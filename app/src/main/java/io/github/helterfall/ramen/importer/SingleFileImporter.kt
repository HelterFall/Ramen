package io.github.helterfall.ramen.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.helterfall.ramen.library.GameRecord
import io.github.helterfall.ramen.storage.StorageGuardedOutputStream
import io.github.helterfall.ramen.storage.StorageSpaceGuard
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Imports an arbitrary single file without changing its bytes. */
class SingleFileImporter(
    private val context: Context,
    private val detector: VisualNovelDetector = VisualNovelDetector(),
    private val streamCopier: SafeSingleFileStreamCopier = SafeSingleFileStreamCopier(),
    private val inlineResourceExtractor: HtmlInlineResourceExtractor = HtmlInlineResourceExtractor()
) {
    fun import(uri: Uri): GameRecord {
        val metadata = readMetadata(uri)
        val rawName = metadata.displayName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: DEFAULT_FILE_NAME
        val fileName = validateFileName(rawName)
        val mimeType = context.contentResolver.getType(uri)?.lowercase()
        val isHtml = fileName.endsWith(".html", true) || fileName.endsWith(".htm", true) ||
            mimeType == "text/html" || mimeType == "application/xhtml+xml"

        val id = UUID.randomUUID().toString()
        val gamesDirectory = File(context.filesDir, "games")
        if (!gamesDirectory.mkdirs() && !gamesDirectory.isDirectory) {
            throw IOException("Could not create the private games directory.")
        }
        val stagingDirectory = File(gamesDirectory, ".$id.importing")
        val finalDirectory = File(gamesDirectory, id)

        try {
            if (!stagingDirectory.mkdirs()) throw IOException("Could not create the temporary game directory.")
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Android could not read the selected file.")

            // WebView expects index.html; other standalone files retain their original filename.
            val storedName = if (isHtml) HTML_ENTRY_FILE_NAME else fileName
            val outputFile = File(stagingDirectory, storedName)
            val copiedBytes = input.use { source ->
                StorageGuardedOutputStream(
                    delegate = FileOutputStream(outputFile),
                    guard = StorageSpaceGuard(stagingDirectory)
                ).use { target ->
                    streamCopier.copy(source, target, metadata.sizeBytes)
                }
            }
            if (copiedBytes == 0L) throw IOException("The selected file is empty.")

            val record = if (isHtml) {
                val extracted = inlineResourceExtractor.extract(outputFile, stagingDirectory)
                val detection = detector.detect(stagingDirectory)
                if (detection.engineId != "html5") {
                    throw IOException("The selected HTML document could not be imported as a standalone page.")
                }
                GameRecord(
                    id = id,
                    title = titleFrom(fileName),
                    relativeDirectory = "games/$id",
                    engineId = "html5",
                    engineLabel = "HTML5 · один файл",
                    compatibilityStatus = "SUPPORTED_LIMITED",
                    evidence = if (extracted.extractedCount > 0) {
                        "Импортирован HTML-файл. Извлечено встроенных локальных ресурсов: " +
                            extracted.extractedCount +
                            ". Соседние CSS, JS, изображения и звук не копировались."
                    } else {
                        "Импортирован HTML-файл. Соседние CSS, JS, изображения и звук не копировались."
                    },
                    importedAtEpochMillis = System.currentTimeMillis()
                )
            } else {
                val detection = detector.detect(stagingDirectory)
                GameRecord(
                    id = id,
                    title = titleFrom(fileName),
                    relativeDirectory = "games/$id",
                    engineId = detection.engineId,
                    engineLabel = detection.engineLabel,
                    compatibilityStatus = detection.compatibilityStatus,
                    evidence = "Импортирован одиночный файл «" + fileName + "». " + detection.evidence,
                    importedAtEpochMillis = System.currentTimeMillis()
                )
            }

            if (!stagingDirectory.renameTo(finalDirectory)) {
                throw IOException("Could not finalize the imported file.")
            }
            return record
        } catch (error: Exception) {
            stagingDirectory.deleteRecursively()
            finalDirectory.deleteRecursively()
            throw error
        }
    }

    private fun validateFileName(rawName: String): String {
        val name = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
        if (name.isBlank() || name.length > MAX_FILE_NAME_LENGTH ||
            name == "." || name == ".." || name.any { it.isISOControl() } || name.contains(':')
        ) throw IOException("The selected file has an invalid filename.")
        return name
    }

    private fun titleFrom(fileName: String): String =
        fileName.substringBeforeLast('.', fileName).filterNot { it.isISOControl() }
            .trim().take(MAX_TITLE_LENGTH).ifBlank { "Imported file" }

    private fun readMetadata(uri: Uri): SingleFileMetadata = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use SingleFileMetadata(null, null)
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            SingleFileMetadata(
                displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else null,
                sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex).takeIf { it >= 0L }
                } else null
            )
        } ?: SingleFileMetadata(null, null)
    }.getOrDefault(SingleFileMetadata(null, null))

    private data class SingleFileMetadata(val displayName: String?, val sizeBytes: Long?)

    companion object {
        private const val DEFAULT_FILE_NAME = "imported-file"
        private const val HTML_ENTRY_FILE_NAME = "index.html"
        private const val MAX_FILE_NAME_LENGTH = 255
        private const val MAX_TITLE_LENGTH = 100
    }
}

/** Copies an arbitrary single file unchanged while checking metadata consistency. */
class SafeSingleFileStreamCopier {
    fun copy(input: InputStream, output: OutputStream, declaredSizeBytes: Long? = null): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copiedBytes = 0L
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) break
            if (bytesRead == 0) continue
            output.write(buffer, 0, bytesRead)
            try {
                copiedBytes = Math.addExact(copiedBytes, bytesRead.toLong())
            } catch (overflow: ArithmeticException) {
                throw IOException("The selected file exceeds the supported size range.", overflow)
            }
        }
        if (declaredSizeBytes != null && declaredSizeBytes > 0L && copiedBytes != declaredSizeBytes) {
            throw IOException("The selected file size changed or could not be read completely.")
        }
        return copiedBytes
    }
}