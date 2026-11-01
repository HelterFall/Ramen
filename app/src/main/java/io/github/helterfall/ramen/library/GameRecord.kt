package io.github.helterfall.ramen.library

data class GameRecord(
    val id: String,
    val title: String,
    val relativeDirectory: String,
    val engineId: String,
    val engineLabel: String,
    val compatibilityStatus: String,
    val evidence: String,
    val importedAtEpochMillis: Long
)
