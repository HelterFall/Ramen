package io.github.helterfall.ramen.importer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.helterfall.ramen.library.GameRecord
import java.io.File
import java.io.IOException
import java.util.UUID

class SafFolderImporter(
    private val context: Context,
    private val copier: SafeFolderCopier = SafeFolderCopier(),
    private val detector: VisualNovelDetector = VisualNovelDetector()
) {
    fun import(treeUri: Uri): GameRecord {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Android could not open the selected folder.")

        if (!root.isDirectory) {
            throw IOException("The selected item is not a folder.")
        }

        val id = UUID.randomUUID().toString()
        val gamesDirectory = File(context.filesDir, "games")
        if (!gamesDirectory.mkdirs() && !gamesDirectory.isDirectory) {
            throw IOException("Could not create the private games directory.")
        }

        val stagingDirectory = File(gamesDirectory, ".$id.importing")
        val finalDirectory = File(gamesDirectory, id)

        try {
            copier.copyContents(
                root = SafFolderEntryNode(context, root),
                destination = stagingDirectory
            )

            val detection = detector.detect(stagingDirectory)
            if (!stagingDirectory.renameTo(finalDirectory)) {
                throw IOException("Could not finalize the imported folder.")
            }

            val title = root.name
                ?.filterNot { it.isISOControl() }
                ?.trim()
                ?.take(MAX_TITLE_LENGTH)
                ?.takeIf { it.isNotBlank() }
                ?: "Visual novel"

            return GameRecord(
                id = id,
                title = title,
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

    private class SafFolderEntryNode(
        private val context: Context,
        private val document: DocumentFile
    ) : FolderEntryNode {
        override val name: String?
            get() = document.name

        override val sourceId: String
            get() = document.uri.toString()

        override val isDirectory: Boolean
            get() = document.isDirectory

        override val declaredSizeBytes: Long?
            get() = document.length().takeIf { it > 0L }

        override fun listChildren(): List<FolderEntryNode> {
            if (!document.isDirectory) {
                throw IOException("A file cannot be enumerated as a folder.")
            }

            return document.listFiles().map { child ->
                if (!child.isDirectory && !child.isFile) {
                    throw IOException("The selected folder contains an unsupported item.")
                }
                SafFolderEntryNode(context, child)
            }
        }

        override fun openInputStream() =
            context.contentResolver.openInputStream(document.uri)
                ?: throw IOException("Android could not read a selected file.")
    }

    companion object {
        private const val MAX_TITLE_LENGTH = 100
    }
}
