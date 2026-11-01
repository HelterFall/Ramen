package io.github.helterfall.ramen.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.helterfall.ramen.library.GameRecord
import java.io.File
import java.io.IOException
import java.util.UUID

class ZipGameImporter(
    private val context: Context,
    private val extractor: SafeZipExtractor = SafeZipExtractor(),
    private val detector: VisualNovelDetector = VisualNovelDetector()
) {
    fun import(uri: Uri): GameRecord {
        val id = UUID.randomUUID().toString()
        val gamesDirectory = File(context.filesDir, "games")

        if (!gamesDirectory.mkdirs() && !gamesDirectory.isDirectory) {
            throw IOException("Could not create the private games directory.")
        }

        val stagingDirectory = File(gamesDirectory, ".$id.importing")
        val finalDirectory = File(gamesDirectory, id)

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Android could not open the selected archive.")

            input.use {
                extractor.extract(it, stagingDirectory)
            }

            val detection = detector.detect(stagingDirectory)
            if (!stagingDirectory.renameTo(finalDirectory)) {
                throw IOException("Could not finalize the imported game.")
            }

            return GameRecord(
                id = id,
                title = readTitle(uri),
                relativeDirectory = "games/$id",
                engineId = detection.engineId,
                engineLabel = detection.engineLabel,
                compatibilityStatus = detection.compatibilityStatus,
                evidence = detection.evidence,
                importedAtEpochMillis = System.currentTimeMillis()
            )
        } catch (error: Exception) {
            stagingDirectory.deleteRecursively()
            finalDirectory.deleteRecursively()
            throw error
        }
    }

    private fun readTitle(uri: Uri): String {
        val displayName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else {
                    null
                }
            }
        }.getOrNull()

        val rawTitle = (displayName ?: uri.lastPathSegment ?: "Visual novel")
            .substringAfterLast('/')
            .removeSuffix(".zip")
            .filterNot { it.isISOControl() }
            .trim()
            .take(MAX_TITLE_LENGTH)

        return rawTitle.ifBlank { "Visual novel" }
    }

    companion object {
        private const val MAX_TITLE_LENGTH = 100
    }
}
