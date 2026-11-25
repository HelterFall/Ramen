package io.github.helterfall.ramen.importer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.helterfall.ramen.inspection.DirectoryStructureReport
import io.github.helterfall.ramen.inspection.DirectoryStructureScanner
import java.io.IOException
import java.io.InputStream

/** Scans SAF folder metadata directly; it never opens file streams. */
class SafDirectoryStructureScanner(
    private val context: Context,
    private val scanner: DirectoryStructureScanner = DirectoryStructureScanner()
) {
    @Throws(IOException::class)
    fun scan(treeUri: Uri): DirectoryStructureReport {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Android could not open the selected folder.")
        if (!root.isDirectory) throw IOException("The selected item is not a folder.")
        return scanner.scan(SafDocumentTreeNode(context, root))
    }

    private class SafDocumentTreeNode(
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
            get() = document.length().takeIf { it >= 0L }

        override fun listChildren(): List<FolderEntryNode> {
            if (!document.isDirectory) throw IOException("A file cannot be enumerated as a folder.")
            return document.listFiles().map { child ->
                if (!child.isDirectory && !child.isFile) {
                    throw IOException("The selected folder contains an unsupported item.")
                }
                SafDocumentTreeNode(context, child)
            }
        }

        override fun openInputStream(): InputStream =
            context.contentResolver.openInputStream(document.uri)
                ?: throw IOException("Android could not read a selected file.")
    }
}
