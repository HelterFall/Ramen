package io.github.helterfall.ramen.runtime.renpy

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import io.github.helterfall.ramen.library.GameRecord

/**
 * Launch bridge for the adapted official Ren'Py 8.5.3 PythonSDLActivity.
 * The native engine and Activity are embedded in the Ramen APK.
 */
object RenPyRuntimeLauncher {
    const val EXTRA_GAME_ID = "io.github.helterfall.ramen.extra.RENPY_GAME_ID"
    const val EXTRA_GAME_DIR = "io.github.helterfall.ramen.extra.RENPY_GAME_DIR"
    const val EXTRA_SAVES_ROOT = "io.github.helterfall.ramen.extra.RENPY_SAVES_ROOT"
    const val EXTRA_LOG_DIR = "io.github.helterfall.ramen.extra.RENPY_LOG_DIR"

    fun launch(context: Context, game: GameRecord): String? {
        val activity = context.findRamenActivity()
            ?: return "Не удалось получить Activity Ramen. Запуск остановлен, чтобы не открывать игру отдельной задачей Android."

        val availability = RenPyRuntimeAvailabilityProbe.inspect(activity)
        if (!availability.isReady) {
            return availability.message()
        }

        return runCatching {
            val paths = RenPyGamePaths.resolve(activity.filesDir, game)
            val intent = Intent(activity, org.renpy.android.PythonSDLActivity::class.java).apply {
                putExtra(EXTRA_GAME_ID, game.id)
                putExtra(EXTRA_GAME_DIR, paths.gameDirectory.canonicalPath)
                putExtra(EXTRA_SAVES_ROOT, paths.savesRoot.canonicalPath)
                putExtra(EXTRA_LOG_DIR, paths.logDirectory.canonicalPath)
            }

            // Launch from the owning Activity without NEW_TASK. This keeps the
            // Ren'Py SDL screen in Ramen's task, so closing it returns to Ramen
            // instead of the Android launcher.
            activity.startActivity(intent)
            null
        }.getOrElse { error ->
            error.message?.takeIf { it.isNotBlank() }
                ?: "Не удалось открыть встроенный экран Ren'Py в Ramen."
        }
    }

    private fun Context.findRamenActivity(): Activity? {
        var current: Context? = this
        val visited = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Context, Boolean>()
        )

        while (current != null && visited.add(current)) {
            if (current is Activity) {
                return current
            }
            current = (current as? ContextWrapper)?.baseContext
        }

        return null
    }
}
