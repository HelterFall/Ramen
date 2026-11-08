package io.github.helterfall.ramen.runtime.renpy

import android.app.Activity
import android.content.Context
import android.content.Intent
import io.github.helterfall.ramen.library.GameRecord

/**
 * Launch bridge for the adapted official Ren'Py 8.5.3 PythonSDLActivity.
 * The native engine and Activity are not fabricated in this module.
 */
object RenPyRuntimeLauncher {
    const val EXTRA_GAME_ID = "io.github.helterfall.ramen.extra.RENPY_GAME_ID"
    const val EXTRA_GAME_DIR = "io.github.helterfall.ramen.extra.RENPY_GAME_DIR"
    const val EXTRA_SAVES_ROOT = "io.github.helterfall.ramen.extra.RENPY_SAVES_ROOT"
    const val EXTRA_LOG_DIR = "io.github.helterfall.ramen.extra.RENPY_LOG_DIR"

    fun launch(context: Context, game: GameRecord): String? {
        val availability = RenPyRuntimeAvailabilityProbe.inspect(context)
        if (!availability.isReady) {
            return availability.message()
        }

        return runCatching {
            val paths = RenPyGamePaths.resolve(context.filesDir, game)
            val intent = Intent().setClassName(
                context.packageName,
                RenPyRuntimeAvailabilityProbe.ACTIVITY_CLASS
            ).apply {
                putExtra(EXTRA_GAME_ID, game.id)
                putExtra(EXTRA_GAME_DIR, paths.gameDirectory.canonicalPath)
                putExtra(EXTRA_SAVES_ROOT, paths.savesRoot.canonicalPath)
                putExtra(EXTRA_LOG_DIR, paths.logDirectory.canonicalPath)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            null
        }.getOrElse { error ->
            error.message?.takeIf { it.isNotBlank() }
                ?: "Не удалось передать игру встроенному Ren'Py Runtime."
        }
    }
}
