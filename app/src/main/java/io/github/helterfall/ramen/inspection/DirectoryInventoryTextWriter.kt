package io.github.helterfall.ramen.inspection

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Writes a readable UTF-8 folder/file tree for a visual-novel project.
 *
 * The report contains names and sizes only. It never writes file contents, dialogue, hashes,
 * or extracted game resources. Output streams and writers remain owned by the caller.
 */
class DirectoryInventoryTextWriter {
    fun write(report: DirectoryInventoryReport, output: OutputStream) {
        val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
        write(report, writer)
        writer.flush()
    }

    fun write(report: DirectoryInventoryReport, writer: Writer) {
        val root = buildTree(report)
        calculateTotals(root)
        val folderCount = countDirectories(root)

        writer.write("RAMEN — СТРУКТУРА ВИЗУАЛЬНОЙ НОВЕЛЛЫ\n")
        writer.write("Корневая папка: ${safeName(report.rootName)}\n\n")
        writer.write("СВОДКА\n")
        writer.write("Папок в дереве: $folderCount (обнаружено: ${report.discoveredDirectories})\n")
        writer.write("Файлов в отчёте: ${report.entries.size} (обнаружено: ${report.discoveredEntries})\n")
        writer.write(
            "Общий объём файлов в отчёте: ${report.totalBytesObserved} байт " +
                "(${formatBytes(report.totalBytesObserved)})\n"
        )
        if (report.truncated) {
            writer.write("Полнота: ОТЧЁТ ОГРАНИЧЕН — см. предупреждения ниже.\n")
        } else {
            writer.write("Полнота: обход завершён без срабатывания ограничений.\n")
        }
        writer.write("\nДЕРЕВО ФАЙЛОВ И ПАПОК\n")
        writer.write(
            "${safeName(root.name)}/  [КОРЕНЬ · ${root.fileCount} файлов · " +
                "${formatBytes(root.sizeBytes)}]\n"
        )
        writeChildren(writer, root, prefix = "")
        if (report.warnings.isNotEmpty() || report.entries.any { it.warnings.isNotEmpty() }) {
            writer.write("\nПРЕДУПРЕЖДЕНИЯ\n")
            report.warnings.forEach { writer.write("- ${safeName(it)}\n") }
            report.entries.forEach { entry ->
                entry.warnings.forEach { warning ->
                    writer.write("- ${safeName(entry.relativePath)}: ${safeName(warning)}\n")
                }
            }
        }
        writer.write("\nПримечание: отчёт содержит только структуру, имена и размеры файлов.\n")
        writer.write("Содержимое файлов и текст сценариев в отчёт не копируются.\n")
        writer.flush()
    }

    private fun buildTree(report: DirectoryInventoryReport): TreeNode {
        val root = TreeNode(name = report.rootName, isDirectory = true)

        report.directoryPaths
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareBy<String>({ it.count { character -> character == '/' } }, { it }))
            .forEach { path ->
                ensureDirectory(root, path.split('/'))
            }

        report.entries.forEach { entry ->
            val segments = entry.relativePath.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return@forEach
            val parent = ensureDirectory(root, segments.dropLast(1))
            parent.children += TreeNode(
                name = segments.last(),
                isDirectory = false,
                sizeBytes = entry.sizeBytes,
                fileCount = 1
            )
        }
        return root
    }

    private fun ensureDirectory(parent: TreeNode, segments: List<String>): TreeNode {
        var current = parent
        segments.forEach { segment ->
            val existing = current.children.firstOrNull {
                it.isDirectory && it.name == segment
            }
            current = existing ?: TreeNode(segment, isDirectory = true).also {
                current.children += it
            }
        }
        return current
    }

    private fun calculateTotals(node: TreeNode) {
        if (!node.isDirectory) return
        node.children.forEach { child ->
            if (child.isDirectory) calculateTotals(child)
            node.sizeBytes = saturatedAdd(node.sizeBytes, child.sizeBytes)
            node.fileCount += child.fileCount
        }
    }

    private fun countDirectories(node: TreeNode): Int =
        node.children.filter { it.isDirectory }.sumOf { 1 + countDirectories(it) }

    private fun writeChildren(writer: Writer, parent: TreeNode, prefix: String) {
        val children = parent.children.sortedWith(
            compareBy<TreeNode>(
                { if (it.isDirectory) 0 else 1 },
                { it.name.lowercase(Locale.ROOT) },
                { it.name }
            )
        )
        children.forEachIndexed { index, child ->
            val isLast = index == children.lastIndex
            val branch = if (isLast) "└── " else "├── "
            if (child.isDirectory) {
                writer.write(
                    prefix + branch + safeName(child.name) + "/  [ПАПКА · " +
                        child.fileCount + " файлов · " + formatBytes(child.sizeBytes) + "]\n"
                )
                val nextPrefix = prefix + if (isLast) "    " else "│   "
                writeChildren(writer, child, nextPrefix)
            } else {
                writer.write(
                    prefix + branch + safeName(child.name) + "  [ФАЙЛ · " +
                        child.sizeBytes + " байт · " + formatBytes(child.sizeBytes) + "]\n"
                )
            }
        }
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024L) return "$value Б"
        val units = listOf("КиБ", "МиБ", "ГиБ", "ТиБ")
        var scaled = value.toDouble()
        var unitIndex = -1
        do {
            scaled /= 1024.0
            unitIndex += 1
        } while (scaled >= 1024.0 && unitIndex < units.lastIndex)
        return String.format(Locale.ROOT, "%.2f %s", scaled, units[unitIndex])
    }

    private fun safeName(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.isISOControl()) append('?') else append(character)
            }
        }
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private data class TreeNode(
        val name: String,
        val isDirectory: Boolean,
        val children: MutableList<TreeNode> = mutableListOf(),
        var sizeBytes: Long = 0L,
        var fileCount: Int = 0
    )
}
