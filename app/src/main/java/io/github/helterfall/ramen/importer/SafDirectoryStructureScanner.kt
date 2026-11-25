package io.github.helterfall.ramen.importer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.helterfall.ramen.inspection.DirectoryStructureReport
import io.github.helterfall.ramen.inspection.DirectoryStructureScanner
import java.io.IOException
import java.io.InputStream

/**
 * Scans only directory entries through SAF.
 *
 * Each provider query requests just document ID, display name, and MIME type. Files are filtered
 * in the cursor and are never wrapped as nodes, stat'ed for size, opened, or traversed.
 */
class SafDirectoryStructureScanner(
    context: Context,
    private val scanner: DirectoryStructureScanner = DirectoryStructureScanner()
) {
    private val resolver = context.contentResolver

    @Throws(IOException::class)
    fun scan(treeUri: Uri): DirectoryStructureReport {
        val rootDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (error: Exception) {
            throw IOException("Android could not identify the selected folder.", error)
        }

        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)
        val rootMetadata = resolver.query(
            rootUri,
            ROOT_PROJECTION,
            null,
            null,
            null
        ) ?: throw IOException("Android could not read the selected folder.")

        val rootName = rootMetadata.use { cursor ->
            if (!cursor.moveToFirst()) {
                throw IOException("Android returned no information for the selected folder.")
            }
            val mimeTypeIndex = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE)
            val nameIndex = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
            if (mimeTypeIndex < 0 || nameIndex < 0) {
                throw IOException("The folder provider returned incomplete metadata.")
            }
            if (cursor.getString(mimeTypeIndex) != Document.MIME_TYPE_DIR) {
                throw IOException("The selected item is not a folder.")
            }
            cursor.getString(nameIndex)
        }

        return scanner.scan(
            SafDirectoryTreeNode(
                resolver = resolver,
                treeUri = treeUri,
                documentId = rootDocumentId,
                name = rootName
            )
        )
    }

    private class SafDirectoryTreeNode(
        private val resolver: ContentResolver,
        private val treeUri: Uri,
        private val documentId: String,
        override val name: String?
    ) : FolderEntryNode {
        override val sourceId: String
            get() = documentId

        override val isDirectory: Boolean
            get() = true

        override val declaredSizeBytes: Long?
            get() = null

        override fun listChildren(): List<FolderEntryNode> {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val cursor = resolver.query(
                childrenUri,
                CHILDREN_PROJECTION,
                null,
                null,
                null
            ) ?: throw IOException("Android could not enumerate a selected folder.")

            return cursor.use { rows ->
                val documentIdIndex = rows.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
                val displayNameIndex = rows.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
                val mimeTypeIndex = rows.getColumnIndex(Document.COLUMN_MIME_TYPE)
                if (documentIdIndex < 0 || displayNameIndex < 0 || mimeTypeIndex < 0) {
                    throw IOException("The folder provider returned incomplete directory metadata.")
                }

                buildList {
                    while (rows.moveToNext()) {
                        // Ignore files at the provider cursor; never make per-file metadata calls.
                        if (rows.getString(mimeTypeIndex) != Document.MIME_TYPE_DIR) continue

                        val childDocumentId = rows.getString(documentIdIndex)
                        if (childDocumentId.isNullOrBlank()) {
                            throw IOException("The folder provider returned a directory without an ID.")
                        }
                        add(
                            SafDirectoryTreeNode(
                                resolver = resolver,
                                treeUri = treeUri,
                                documentId = childDocumentId,
                                name = rows.getString(displayNameIndex)
                            )
                        )
                    }
                }
            }
        }

        override fun openInputStream(): InputStream {
            throw IOException("Directory structure scanning never opens file contents.")
        }
    }

    companion object {
        private val ROOT_PROJECTION = arrayOf(
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE
        )
        private val CHILDREN_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE
        )
    }
}
