package io.github.helterfall.ramen.library

/**
 * Keeps library launch eligibility in one place so the card cannot hide a
 * player whose engine route is already wired into RamenApp.
 */
object GameLaunchPolicy {
    fun canLaunch(game: GameRecord): Boolean = when {
        game.engineId == "html5" &&
            game.compatibilityStatus == "SUPPORTED_LIMITED" -> true
        game.engineId == "system-nnn-spt" &&
            game.compatibilityStatus == "PARSER_AVAILABLE" -> true
        game.engineId == "nscripter" &&
            game.compatibilityStatus == "PARSER_AVAILABLE" -> true
        else -> false
    }
}
