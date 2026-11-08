package io.github.helterfall.ramen.importer

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
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
        val rootDocument = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Android could not open the selected folder.")

        val root = SafFolderEntryNode.load(context, treeUri, rootDocument.uri)
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
            copier.copyContents(root, stagingDirectory)

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

    /**
     * Each node stores one metadata snapshot. Directory enumeration retrieves
     * document ID, name, MIME type and size for every child in one provider
     * query, rather than calling DocumentFile.name/isDirectory/isFile/length
     * separately for every item.
     */
    private class SafFolderEntryNode private constructor(
        private val context: Context,
        private val treeUri: Uri,
        private val documentUri: Uri,
        private val metadata: DocumentMetadata
    ) : FolderEntryNode {
        override val name: String?
            get() = metadata.name

        override val sourceId: String
            get() = documentUri.toString()

        override val isDirectory: Boolean
            get() = metadata.isDirectory

        override val declaredSizeBytes: Long?
            get() = metadata.sizeBytes

        override fun listChildren(): List<FolderEntryNode> {
            if (!isDirectory) {
                throw IOException("A file cannot be enumerated as a folder.")
            }

            val documentId = try {
                DocumentsContract.getDocumentId(documentUri)
            } catch (error: Exception) {
                throw IOException("Android returned an invalid folder document ID.", error)
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val cursor = context.contentResolver.query(
                childrenUri,
                METADATA_PROJECTION,
                null,
                null,
                null
            ) ?: throw IOException("Android could not enumerate a selected folder.")

            cursor.use {
                val documentIdIndex = it.requiredColumnIndex(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                )
                val children = ArrayList<FolderEntryNode>()
                while (it.moveToNext()) {
                    val childId = it.getString(documentIdIndex)
                        ?.takeIf(String::isNotBlank)
                        ?: throw IOException("Android returned a folder entry without a document ID.")
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    val childMetadata = DocumentMetadata.fromCursor(it)
                    children += SafFolderEntryNode(context, treeUri, childUri, childMetadata)
                }
                return children
            }
        }

        override fun openInputStream() =
            context.contentResolver.openInputStream(documentUri)
                ?: throw IOException("Android could not read a selected file.")

        companion object {
            private val METADATA_PROJECTION = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )

            fun load(context: Context, treeUri: Uri, documentUri: Uri): SafFolderEntryNode {
                val cursor = context.contentResolver.query(
                    documentUri,
                    METADATA_PROJECTION,
                    null,
                    null,
                    null
                ) ?: throw IOException("Android could not read folder metadata.")

                val metadata = cursor.use {
                    if (!it.moveToFirst()) {
                        throw IOException("Android returned no metadata for the selected folder.")
                    }
                    DocumentMetadata.fromCursor(it)
                }
                return SafFolderEntryNode(context, treeUri, documentUri, metadata)
            }
        }
    }

    private data class DocumentMetadata(
        val name: String?,
        val isDirectory: Boolean,
        val sizeBytes: Long?
    ) {
        companion object {
            fun fromCursor(cursor: Cursor): DocumentMetadata {
                val nameIndex = cursor.requiredColumnIndex(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                )
                val mimeTypeIndex = cursor.requiredColumnIndex(
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                val mimeType = cursor.getString(mimeTypeIndex)
                    ?.takeIf(String::isNotBlank)
                    ?: throw IOException("The selected folder contains an item without a MIME type.")
                val sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex).takeIf { value -> value >= 0L }
                } else {
                    null
                }

                return DocumentMetadata(
                    name = cursor.getString(nameIndex),
                    isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                    sizeBytes = sizeBytes
                )
            }
        }
    }

    companion object {
        private const val MAX_TITLE_LENGTH = 100
    }
}

private fun Cursor.requiredColumnIndex(columnName: String): Int {
    val index = getColumnIndex(columnName)
    if (index < 0) {
        throw IOException("The selected folder provider omitted required metadata: $columnName.")
    }
    return index
}
