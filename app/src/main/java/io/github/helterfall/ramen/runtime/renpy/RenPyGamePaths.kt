package io.github.helterfall.ramen.runtime.renpy

import io.github.helterfall.ramen.library.GameRecord
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Canonical filesystem locations passed to the embedded Ren'Py host.
 * The selected game is copied into app-private storage by the SAF importer.
 */
data class ResolvedRenPyGamePaths(
    val importedRoot: File,
    val gameDirectory: File,
    val dataRoot: File,
    val savesRoot: File,
    val logDirectory: File
)

object RenPyGamePaths {
    private val gameFileExtensions = setOf("rpy", "rpyc", "rpym", "rpymc", "rpa")
    private val safeId = Regex("[A-Za-z0-9-]{1,64}")

    @Throws(IOException::class)
    fun resolve(filesDir: File, game: GameRecord): ResolvedRenPyGamePaths {
        if (game.engineId != "renpy") {
            throw IOException("The selected library entry is not a Ren'Py game.")
        }
        if (!safeId.matches(game.id) || game.relativeDirectory != "games/${game.id}") {
            throw IOException("The imported Ren'Py game has an invalid library path.")
        }

        val privateRoot = filesDir.canonicalFile
        if (!privateRoot.isDirectory) {
            throw IOException("Ramen's private storage directory is unavailable.")
        }

        val gamesRoot = File(privateRoot, "games").canonicalFile
        requireContained(gamesRoot, privateRoot, "The games directory is outside private storage.")

        val importedRoot = File(privateRoot, game.relativeDirectory).canonicalFile
        requireContained(importedRoot, gamesRoot, "The imported game path is outside the games directory.")
        if (!importedRoot.isDirectory || importedRoot == gamesRoot) {
            throw IOException("The imported Ren'Py game folder is missing.")
        }

        val nestedGameDirectory = File(importedRoot, "game").canonicalFile
        val gameDirectory = when {
            nestedGameDirectory.isDirectory && containsRenPyPayload(nestedGameDirectory) ->
                nestedGameDirectory
            containsRenPyPayload(importedRoot) -> importedRoot
            else -> throw IOException("No Ren'Py scripts or resource archives were found in the imported game.")
        }
        if (!gameDirectory.toPath().startsWith(importedRoot.toPath())) {
            throw IOException("The Ren'Py game directory is outside the imported folder.")
        }

        val dataBase = File(privateRoot, "renpy-data").canonicalFile
        requireContained(dataBase, privateRoot, "The Ren'Py data directory is outside private storage.")
        ensureDirectory(dataBase)

        val dataRoot = File(dataBase, game.id).canonicalFile
        requireContained(dataRoot, dataBase, "The Ren'Py game data path is invalid.")
        ensureDirectory(dataRoot)

        val savesRoot = File(dataRoot, "saves").canonicalFile
        requireContained(savesRoot, dataRoot, "The Ren'Py saves path is invalid.")
        ensureDirectory(savesRoot)

        val logDirectory = File(dataRoot, "logs").canonicalFile
        requireContained(logDirectory, dataRoot, "The Ren'Py log path is invalid.")
        ensureDirectory(logDirectory)

        return ResolvedRenPyGamePaths(importedRoot, gameDirectory, dataRoot, savesRoot, logDirectory)
    }

    private fun containsRenPyPayload(directory: File): Boolean =
        directory.walkTopDown()
            .maxDepth(MAX_PAYLOAD_SCAN_DEPTH)
            .any { file ->
                file.isFile && file.extension.lowercase(Locale.ROOT) in gameFileExtensions
            }

    private fun ensureDirectory(directory: File) {
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create a private Ren'Py data directory.")
        }
        if (!directory.canonicalFile.isDirectory) {
            throw IOException("A Ren'Py data path is not a directory.")
        }
    }

    private fun requireContained(child: File, parent: File, message: String) {
        if (!child.toPath().startsWith(parent.toPath()) || child == parent) {
            throw IOException(message)
        }
    }

    private const val MAX_PAYLOAD_SCAN_DEPTH = 12
}
