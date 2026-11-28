package io.github.helterfall.ramen.nscripter

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class FileNscripterSaveStore(directory: File) : NscripterSaveStore {
    private val saveDirectory = directory.apply { mkdirs() }

    override fun exists(slot: Int): Boolean = slot in 0..99 && saveFile(slot).isFile

    override fun save(slot: Int, savedGame: NscripterSavedGame) {
        require(slot in 0..99) { "Save slot must be between 0 and 99." }
        val target = saveFile(slot)
        val temporary = File(saveDirectory, target.name + ".tmp")
        ObjectOutputStream(FileOutputStream(temporary)).use { it.writeObject(savedGame) }
        if (target.exists() && !target.delete()) {
            temporary.delete()
            throw IllegalStateException("Unable to replace NScripter save slot " + slot)
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IllegalStateException("Unable to commit NScripter save slot " + slot)
        }
    }

    override fun load(slot: Int): NscripterSavedGame? {
        if (slot !in 0..99) return null
        val file = saveFile(slot)
        if (!file.isFile) return null
        return runCatching {
            ObjectInputStream(FileInputStream(file)).use { it.readObject() as? NscripterSavedGame }
        }.getOrNull()
    }

    private fun saveFile(slot: Int) =
        File(saveDirectory, "slot-" + slot.toString().padStart(2, '0') + ".sav")
}
