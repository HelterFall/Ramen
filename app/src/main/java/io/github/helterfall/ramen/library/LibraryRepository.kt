package io.github.helterfall.ramen.library

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LibraryRepository(context: Context) {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): List<GameRecord> {
        val raw = preferences.getString(KEY_GAMES, "[]") ?: "[]"

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val relativeDirectory = item.optString("relativeDirectory")

                    if (!relativeDirectory.startsWith("games/")) continue
                    if (relativeDirectory.split('/').any { it == ".." || it.isBlank() }) continue

                    add(
                        GameRecord(
                            id = item.optString("id"),
                            title = item.optString("title", "Visual novel"),
                            relativeDirectory = relativeDirectory,
                            engineId = item.optString("engineId", "unknown"),
                            engineLabel = item.optString("engineLabel", "Неизвестный движок"),
                            compatibilityStatus = item.optString(
                                "compatibilityStatus",
                                "UNKNOWN"
                            ),
                            evidence = item.optString("evidence"),
                            importedAtEpochMillis = item.optLong("importedAtEpochMillis")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(games: List<GameRecord>) {
        val array = JSONArray()

        games.forEach { game ->
            array.put(
                JSONObject()
                    .put("id", game.id)
                    .put("title", game.title)
                    .put("relativeDirectory", game.relativeDirectory)
                    .put("engineId", game.engineId)
                    .put("engineLabel", game.engineLabel)
                    .put("compatibilityStatus", game.compatibilityStatus)
                    .put("evidence", game.evidence)
                    .put("importedAtEpochMillis", game.importedAtEpochMillis)
            )
        }

        check(preferences.edit().putString(KEY_GAMES, array.toString()).commit()) {
            "Could not save the game library."
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "ramen_library"
        private const val KEY_GAMES = "games"
    }
}
