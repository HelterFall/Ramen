package io.github.helterfall.ramen.runtime.systemnnn

import android.content.Context

/**
 * Small process-independent snapshot shared by the library screen and the
 * foreground SCAN service. The actual report remains in private app storage.
 */
internal object SystemNnnFullGameAuditStateStore {
    const val STATUS_IDLE = "idle"
    const val STATUS_RUNNING = "running"
    const val STATUS_READY = "ready"
    const val STATUS_EXPORTING = "exporting"
    const val STATUS_COMPLETE = "complete"
    const val STATUS_FAILED = "failed"
    const val STATUS_CANCELLED = "cancelled"

    private const val PREFS_NAME = "ramen_full_game_audit_state"
    private const val KEY_STATUS = "status"
    private const val KEY_TITLE = "title"
    private const val KEY_MESSAGE = "message"
    private const val KEY_PROGRESS = "progress"
    private const val KEY_BYTES = "bytes"
    private const val KEY_UPDATED_AT = "updated_at"

    data class Snapshot(
        val status: String = STATUS_IDLE,
        val title: String = "",
        val message: String = "",
        val progress: Float? = null,
        val bytes: Long = 0L,
        val updatedAtMillis: Long = 0L
    ) {
        val isRunning: Boolean
            get() = status == STATUS_RUNNING || status == STATUS_EXPORTING

        val hasStagedReport: Boolean
            get() = status == STATUS_READY
    }

    fun read(context: Context): Snapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawProgress = prefs.getFloat(KEY_PROGRESS, -1f)
        return Snapshot(
            status = prefs.getString(KEY_STATUS, STATUS_IDLE) ?: STATUS_IDLE,
            title = prefs.getString(KEY_TITLE, "") ?: "",
            message = prefs.getString(KEY_MESSAGE, "") ?: "",
            progress = rawProgress.takeIf { it >= 0f },
            bytes = prefs.getLong(KEY_BYTES, 0L),
            updatedAtMillis = prefs.getLong(KEY_UPDATED_AT, 0L)
        )
    }

    fun update(
        context: Context,
        status: String,
        title: String,
        message: String,
        progress: Float? = null,
        bytes: Long = 0L
    ) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, status)
            .putString(KEY_TITLE, title)
            .putString(KEY_MESSAGE, message)
            .putFloat(KEY_PROGRESS, progress?.coerceIn(0f, 1f) ?: -1f)
            .putLong(KEY_BYTES, bytes.coerceAtLeast(0L))
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun stagingFile(context: Context) = FilePaths.auditReport(context)
}

private object FilePaths {
    fun auditReport(context: Context) =
        java.io.File(context.applicationContext.filesDir, "reports/ramen-library-full-game-audit.json")
}
