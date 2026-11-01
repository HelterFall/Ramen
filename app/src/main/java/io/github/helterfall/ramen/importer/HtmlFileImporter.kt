package io.github.helterfall.ramen.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.helterfall.ramen.library.GameRecord
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class HtmlFileImporter(
    private val context: Context,
    private val detector: VisualNovelDetector = VisualNovelDetector(),
    private val streamCopier: SafeHtmlStreamCopier = SafeHtmlStreamCopier()
) {
    fun import(uri: Uri): GameRecord {
        val metadata = readMetadata(uri)
        val fileName = metadata.displayName
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "index.html"
        val mimeType = context.contentResolver.getType(uri)?.lowercase()

        val hasHtmlExtension = fileName.endsWith(".html", ignoreCase = true) ||
            fileName.endsWith(".htm", ignoreCase = true)
        val hasHtmlMimeType = mimeType == "text/html" ||
            mimeType == "application/xhtml+xml"

        if (!hasHtmlExtension && !hasHtmlMimeType) {
            throw IOException("Выбери файл .html или .htm. Для игры с картинками, CSS и JS выбери всю папку.")
        }

        metadata.sizeBytes
            ?.takeIf { it >= 0L }
            ?.let { declaredSize ->
                if (declaredSize > SafeHtmlStreamCopier.MAX_BYTES) {
                    throw IOException("HTML-файл превышает лимит 512 МиБ.")
                }
            }

        val id = UUID.randomUUID().toString()
        val gamesDirectory = File(context.filesDir, "games")
        if (!gamesDirectory.mkdirs() && !gamesDirectory.isDirectory) {
            throw IOException("Could not create the private games directory.")
        }

        val stagingDirectory = File(gamesDirectory, ".$id.importing")
        val finalDirectory = File(gamesDirectory, id)

        try {
            if (!stagingDirectory.mkdirs()) {
                throw IOException("Could not create the temporary game directory.")
            }

            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Android could not read the selected HTML file.")

            val outputFile = File(stagingDirectory, "index.html")
            val copiedBytes = input.use { source ->
                FileOutputStream(outputFile).use { target ->
                    streamCopier.copy(
                        input = source,
                        output = target,
                        declaredSizeBytes = metadata.sizeBytes
                    )
                }
            }

            if (copiedBytes == 0L) {
                throw IOException("The selected HTML file is empty.")
            }

            val detection = detector.detect(stagingDirectory)
            if (detection.engineId != "html5") {
                throw IOException("The selected file could not be imported as a standalone HTML page.")
            }

            if (!stagingDirectory.renameTo(finalDirectory)) {
                throw IOException("Could not finalize the imported HTML file.")
            }

            return GameRecord(
                id = id,
                title = fileName
                    .substringBeforeLast('.', fileName)
                    .filterNot { it.isISOControl() }
                    .trim()
                    .take(MAX_TITLE_LENGTH)
                    .ifBlank { "HTML project" },
                relativeDirectory = "games/$id",
                engineId = "html5",
                engineLabel = "HTML5 · один файл",
                compatibilityStatus = "SUPPORTED_LIMITED",
                evidence = "Импортирован только HTML-файл. Соседние CSS, JS, изображения и звук не копировались.",
                importedAtEpochMillis = System.currentTimeMillis()
            )
        } catch (error: Exception) {
            stagingDirectory.deleteRecursively()
            finalDirectory.deleteRecursively()
            throw error
        }
    }

    private fun readMetadata(uri: Uri): HtmlFileMetadata {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use HtmlFileMetadata(null, null)
                }

                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                HtmlFileMetadata(
                    displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else null,
                    sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex).takeIf { it >= 0L }
                    } else {
                        null
                    }
                )
            } ?: HtmlFileMetadata(null, null)
        }.getOrDefault(HtmlFileMetadata(null, null))
    }

    private data class HtmlFileMetadata(
        val displayName: String?,
        val sizeBytes: Long?
    )

    companion object {
        private const val MAX_TITLE_LENGTH = 100
    }
}

class SafeHtmlStreamCopier(
    private val maxBytes: Long = MAX_BYTES
) {
    init {
        require(maxBytes > 0L) { "The HTML file size limit must be positive." }
    }

    fun copy(
        input: InputStream,
        output: OutputStream,
        declaredSizeBytes: Long? = null
    ): Long {
        if (declaredSizeBytes != null && declaredSizeBytes > maxBytes) {
            throw IOException("The HTML file exceeds the import size limit.")
        }

        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copiedBytes = 0L

        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) break

            if (bytesRead.toLong() > maxBytes - copiedBytes) {
                throw IOException("The HTML file exceeds the import size limit.")
            }

            output.write(buffer, 0, bytesRead)
            copiedBytes += bytesRead
        }

        if (declaredSizeBytes != null && declaredSizeBytes > 0L &&
            copiedBytes != declaredSizeBytes
        ) {
            throw IOException("The HTML file size changed or could not be read completely.")
        }

        return copiedBytes
    }

    companion object {
        const val MAX_BYTES = 512L * 1024L * 1024L
    }
}
