package io.github.helterfall.ramen.runtime.systemnnn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import io.github.helterfall.ramen.MainActivity
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-visible foreground worker for full-game SCAN and JSON export.
 *
 * The service owns the worker thread, so destroying/recreating the Compose
 * library screen on rotation does not cancel the analysis.
 */
class SystemNnnFullGameAuditService : Service() {

    @Volatile private var workerThread: Thread? = null
    @Volatile private var cancellationToken: AtomicBoolean? = null
    @Volatile private var latestStartId: Int = 0
    @Volatile private var timeLimitReached = false
    @Volatile private var cpuWakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val operationRunning = AtomicBoolean(false)
    private var lastNotificationUpdateMillis = 0L
    private var lastNotificationPercent = -1

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_START_SCAN -> {
                val relativeDirectory = intent.getStringExtra(EXTRA_RELATIVE_DIRECTORY).orEmpty()
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "System-NNN" }
                startScan(relativeDirectory, title, startId)
                return START_REDELIVER_INTENT
            }
            ACTION_EXPORT_REPORT -> {
                val destination = intent.getStringExtra(EXTRA_DESTINATION_URI).orEmpty()
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "System-NNN" }
                startExport(destination, title, startId)
                return START_REDELIVER_INTENT
            }
            ACTION_CANCEL -> {
                cancelCurrentOperation()
                return START_NOT_STICKY
            }
            else -> {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
        }
    }

    private fun startScan(relativeDirectory: String, title: String, startId: Int) {
        if (!operationRunning.compareAndSet(false, true)) {
            updateNotification(
                title,
                "Другой SCAN уже выполняется.",
                null,
                ongoing = true,
                showCancel = true,
                force = true
            )
            return
        }

        timeLimitReached = false
        val stagedBeforeScan = SystemNnnFullGameAuditStateStore.stagingFile(this)
        val stagedTemporary = File(stagedBeforeScan.parentFile, stagedBeforeScan.name + ".tmp")
        if ((stagedBeforeScan.exists() && !stagedBeforeScan.delete()) ||
            (stagedTemporary.exists() && !stagedTemporary.delete())
        ) {
            operationRunning.set(false)
            val message = "Не удалось очистить предыдущий временный JSON. SCAN не запущен."
            SystemNnnFullGameAuditStateStore.update(
                this,
                SystemNnnFullGameAuditStateStore.STATUS_FAILED,
                title,
                message
            )
            stopSelfResult(startId)
            return
        }

        val token = AtomicBoolean(false)
        cancellationToken = token
        SystemNnnFullGameAuditStateStore.update(
            this,
            SystemNnnFullGameAuditStateStore.STATUS_RUNNING,
            title,
            "Подготовка фонового SCAN…",
            null
        )
        try {
            enterForeground(title, "Подготовка фонового SCAN…", null, showCancel = true)
            acquireCpuWakeLock()
        } catch (failure: Exception) {
            releaseCpuWakeLock()
            operationRunning.set(false)
            cancellationToken = null
            stopForeground(true)
            val message = "Android не разрешил запустить фоновый SCAN: " +
                (failure.message ?: failure.javaClass.simpleName)
            SystemNnnFullGameAuditStateStore.update(
                this,
                SystemNnnFullGameAuditStateStore.STATUS_FAILED,
                title,
                message
            )
            stopSelfResult(startId)
            return
        }

        launchWorker("Ramen-SystemNnn-Scan") {
            try {
                checkNotCancelled(token)
                val root = resolveImportedGameDirectory(relativeDirectory)
                val report = SystemNnnFullGameAuditor(
                    cacheDirectory = File(cacheDir, "ramen-full-game-audit-cache")
                ).audit(root) { label, fraction ->
                    checkNotCancelled(token)
                    val message = "$title: $label"
                    SystemNnnFullGameAuditStateStore.update(
                        this,
                        SystemNnnFullGameAuditStateStore.STATUS_RUNNING,
                        title,
                        message,
                        fraction?.coerceIn(0f, 0.99f)
                    )
                    updateNotification(
                        title,
                        label,
                        fraction,
                        ongoing = true,
                        showCancel = true
                    )
                }
                checkNotCancelled(token)

                val staged = SystemNnnFullGameAuditStateStore.stagingFile(this)
                val stagedBytes = SystemNnnFullGameAuditExporter.stageReport(report, staged)
                checkNotCancelled(token)
                val scope = report["scope"] as? Map<*, *>
                val discoveredModules = scope?.get("discoveredSptModules")
                    ?: scope?.get("allValidSptModules") ?: "?"
                val scannedModules = scope?.get("successfullyScannedModules")
                    ?: scope?.get("allValidSptModules") ?: "?"
                val failedModules = (scope?.get("failedModules") as? Number)?.toInt() ?: 0
                val records = scope?.get("totalPhysicalRecords") ?: "?"
                val issueGroups = (report["issues"] as? List<*>)?.size ?: 0
                val incompleteReasons = buildList {
                    if (failedModules > 0) add("ошибок модулей: $failedModules")
                    if (scope?.get("fileDiscoveryTruncated") == true) add("достигнут лимит файлов")
                    if (scope?.get("fileDiscoveryDepthLimited") == true) add("достигнут предел глубины папок")
                    if (scope?.get("gotoDetailsTruncated") == true ||
                        scope?.get("resourceReferenceDetailsTruncated") == true ||
                        scope?.get("issueDetailsTruncated") == true
                    ) add("часть подробных списков сокращена")
                    if (scope?.get("invalidSptCandidates") is Number &&
                        (scope["invalidSptCandidates"] as Number).toInt() > 0
                    ) add("невалидных SPT-кандидатов: ${scope["invalidSptCandidates"]}")
                }.joinToString("; ")
                val summary = buildString {
                    append("Готово. Модулей найдено: ")
                    append(discoveredModules)
                    append(", разобрано: ")
                    append(scannedModules)
                    append(", физических записей: ")
                    append(records)
                    append(". Групп замечаний: ")
                    append(issueGroups)
                    append(". JSON: ")
                    append(stagedBytes)
                    append(" байт.")
                    if (incompleteReasons.isNotBlank()) {
                        append(" Неполнота/ограничения: ")
                        append(incompleteReasons)
                        append('.')
                    }
                    append(" Открой Ramen, чтобы сохранить файл.")
                }
                SystemNnnFullGameAuditStateStore.update(
                    this,
                    SystemNnnFullGameAuditStateStore.STATUS_READY,
                    title,
                    summary,
                    1f,
                    stagedBytes
                )
                updateNotification(title, summary, 1f, ongoing = false, showCancel = false, force = true)
            } catch (failure: Exception) {
                if (timeLimitReached) {
                    val timeoutMessage = "Android ограничил время фоновой службы. Запусти SCAN заново, когда приложение будет открыто."
                    SystemNnnFullGameAuditStateStore.stagingFile(this).delete()
                    SystemNnnFullGameAuditStateStore.update(
                        this,
                        SystemNnnFullGameAuditStateStore.STATUS_FAILED,
                        title,
                        timeoutMessage
                    )
                    updateNotification(title, timeoutMessage, null, ongoing = false, showCancel = false, force = true)
                } else if (token.get() || Thread.currentThread().isInterrupted ||
                    failure is InterruptedException
                ) {
                    SystemNnnFullGameAuditStateStore.stagingFile(this).delete()
                    SystemNnnFullGameAuditStateStore.update(
                        this,
                        SystemNnnFullGameAuditStateStore.STATUS_CANCELLED,
                        title,
                        "SCAN отменён. Незавершённый JSON удалён."
                    )
                    updateNotification(
                        title,
                        "SCAN отменён.",
                        null,
                        ongoing = false,
                        showCancel = false,
                        force = true
                    )
                } else {
                    SystemNnnFullGameAuditStateStore.stagingFile(this).delete()
                    val safeMessage = failure.message ?: failure.javaClass.simpleName
                    val message = "SCAN не завершён: " + safeMessage.take(240)
                    SystemNnnFullGameAuditStateStore.update(
                        this,
                        SystemNnnFullGameAuditStateStore.STATUS_FAILED,
                        title,
                        message
                    )
                    updateNotification(title, message, null, ongoing = false, showCancel = false, force = true)
                }
            }
        }
    }

    private fun startExport(destinationText: String, title: String, startId: Int) {
        if (!operationRunning.compareAndSet(false, true)) {
            return
        }
        val stagedReport = SystemNnnFullGameAuditStateStore.stagingFile(this)
        if (!stagedReport.isFile || stagedReport.length() <= 0L) {
            operationRunning.set(false)
            SystemNnnFullGameAuditStateStore.update(
                this,
                SystemNnnFullGameAuditStateStore.STATUS_FAILED,
                title,
                "Подготовленный SCAN JSON не найден. Запусти анализ заново."
            )
            stopSelfResult(startId)
            return
        }
        val destination = runCatching { Uri.parse(destinationText) }.getOrNull()
        if (destination == null || destination.scheme.isNullOrBlank()) {
            operationRunning.set(false)
            SystemNnnFullGameAuditStateStore.update(
                this,
                SystemNnnFullGameAuditStateStore.STATUS_READY,
                title,
                "Не удалось открыть место сохранения. Готовый JSON остался внутри приложения."
            )
            stopSelfResult(startId)
            return
        }

        timeLimitReached = false
        val token = AtomicBoolean(false)
        cancellationToken = token
        SystemNnnFullGameAuditStateStore.update(
            this,
            SystemNnnFullGameAuditStateStore.STATUS_EXPORTING,
            title,
            "Подготовка к сохранению JSON…",
            0f
        )
        try {
            enterForeground(title, "Записываю JSON…", 0f, showCancel = true)
            acquireCpuWakeLock()
        } catch (failure: Exception) {
            releasePersistedDestinationPermission(destination)
            releaseCpuWakeLock()
            operationRunning.set(false)
            cancellationToken = null
            stopForeground(true)
            SystemNnnFullGameAuditStateStore.update(
                this,
                SystemNnnFullGameAuditStateStore.STATUS_READY,
                title,
                "Не удалось запустить сохранение: " +
                    (failure.message ?: failure.javaClass.simpleName) +
                    ". Готовый JSON остался внутри приложения.",
                1f
            )
            stopSelfResult(startId)
            return
        }

        launchWorker("Ramen-SystemNnn-Export") {
            val staged = SystemNnnFullGameAuditStateStore.stagingFile(this)
            try {
                checkNotCancelled(token)
                if (!staged.isFile || staged.length() <= 0L) {
                    throw IOException("Подготовленный SCAN JSON отсутствует или пуст.")
                }
                val expectedBytes = staged.length()
                val expectedSha256 = SystemNnnFullGameAuditExporter.sha256(staged)
                val output = contentResolver.openOutputStream(destination, "wt")
                    ?: throw IOException("Android не открыл выбранный JSON-документ для записи.")
                val copiedBytes = output.use { stream ->
                    SystemNnnFullGameAuditExporter.copyTo(staged, stream) { copied, total ->
                        checkNotCancelled(token)
                        val fraction = if (total > 0L) copied.toFloat() / total else 0f
                        val message = "Сохраняю JSON: $copied из $total байт…"
                        SystemNnnFullGameAuditStateStore.update(
                            this,
                            SystemNnnFullGameAuditStateStore.STATUS_EXPORTING,
                            title,
                            message,
                            fraction,
                            total
                        )
                        updateNotification(title, message, fraction, ongoing = true, showCancel = true)
                    }
                }
                if (copiedBytes != expectedBytes || copiedBytes <= 0L) {
                    throw IOException("Размер записанного JSON не совпал с подготовленным отчётом.")
                }
                checkNotCancelled(token)
                val verificationStream = contentResolver.openInputStream(destination)
                    ?: throw IOException("Не удалось повторно открыть JSON для проверки.")
                val verifiedBytes = SystemNnnFullGameAuditExporter.verifySavedReport(
                    verificationStream,
                    copiedBytes,
                    expectedSha256
                )
                checkNotCancelled(token)
                staged.delete()
                releasePersistedDestinationPermission(destination)
                val message = "SCAN сохранён. Проверено $verifiedBytes байт; SHA-256 совпадает."
                SystemNnnFullGameAuditStateStore.update(
                    this,
                    SystemNnnFullGameAuditStateStore.STATUS_COMPLETE,
                    title,
                    message,
                    1f,
                    verifiedBytes
                )
                updateNotification(title, message, 1f, ongoing = false, showCancel = false, force = true)
            } catch (failure: Exception) {
                releasePersistedDestinationPermission(destination)
                if (timeLimitReached) {
                    val timeoutMessage = "Android ограничил время фоновой службы. Готовый JSON остался внутри приложения — экспорт можно повторить позже."
                    SystemNnnFullGameAuditStateStore.update(
                        this,
                        SystemNnnFullGameAuditStateStore.STATUS_READY,
                        title,
                        timeoutMessage,
                        1f,
                        staged.length()
                    )
                    updateNotification(title, timeoutMessage, null, ongoing = false, showCancel = false, force = true)
                } else if (token.get() || Thread.currentThread().isInterrupted ||
                    failure is InterruptedException
                ) {
                    SystemNnnFullGameAuditStateStore.update(
                        this,
                        SystemNnnFullGameAuditStateStore.STATUS_READY,
                        title,
                        "Сохранение отменено. Готовый JSON остался внутри приложения.",
                        1f,
                        staged.length()
                    )
                    updateNotification(title, "Сохранение отменено. JSON можно экспортировать повторно.", null,
                        ongoing = false, showCancel = false, force = true)
                } else {
                    val safeMessage = failure.message ?: failure.javaClass.simpleName
                    val message = "Не удалось сохранить JSON: " + safeMessage.take(220) +
                        ". Готовый отчёт сохранён внутри приложения — экспорт можно повторить."
                    SystemNnnFullGameAuditStateStore.update(
                        this,
                        SystemNnnFullGameAuditStateStore.STATUS_READY,
                        title,
                        message,
                        1f,
                        staged.length()
                    )
                    updateNotification(title, message, null, ongoing = false, showCancel = false, force = true)
                }
            }
        }
    }

    private fun launchWorker(
        threadName: String,
        task: () -> Unit
    ) {
        val thread = Thread({
            try {
                task()
            } finally {
                operationRunning.set(false)
                cancellationToken = null
                workerThread = null
                releaseCpuWakeLock()
                mainHandler.post {
                    if (!operationRunning.get()) {
                        stopForeground(false)
                        stopSelfResult(latestStartId)
                    }
                }
            }
        }, threadName)
        workerThread = thread
        try {
            thread.start()
        } catch (failure: Exception) {
            operationRunning.set(false)
            cancellationToken = null
            workerThread = null
            releaseCpuWakeLock()
            val snapshot = SystemNnnFullGameAuditStateStore.read(this)
            val staged = SystemNnnFullGameAuditStateStore.stagingFile(this)
            val canRetryExport =
                snapshot.status == SystemNnnFullGameAuditStateStore.STATUS_EXPORTING &&
                    staged.isFile && staged.length() > 0L
            val message = "Не удалось запустить фоновый поток: " +
                (failure.message ?: failure.javaClass.simpleName)
            SystemNnnFullGameAuditStateStore.update(
                this,
                if (canRetryExport) SystemNnnFullGameAuditStateStore.STATUS_READY
                else SystemNnnFullGameAuditStateStore.STATUS_FAILED,
                snapshot.title,
                if (canRetryExport) "$message. Готовый JSON остался внутри приложения."
                else message,
                if (canRetryExport) 1f else null,
                if (canRetryExport) staged.length() else 0L
            )
            updateNotification(
                snapshot.title,
                message,
                null,
                ongoing = false,
                showCancel = false,
                force = true
            )
            stopForeground(false)
            stopSelfResult(latestStartId)
        }
    }

    private fun cancelCurrentOperation() {
        val snapshot = SystemNnnFullGameAuditStateStore.read(this)
        val token = cancellationToken
        if (token == null) {
            val staged = SystemNnnFullGameAuditStateStore.stagingFile(this)
            if (snapshot.status == SystemNnnFullGameAuditStateStore.STATUS_RUNNING) {
                staged.delete()
                SystemNnnFullGameAuditStateStore.update(
                    this,
                    SystemNnnFullGameAuditStateStore.STATUS_CANCELLED,
                    snapshot.title,
                    "SCAN отменён."
                )
            } else if (snapshot.status == SystemNnnFullGameAuditStateStore.STATUS_EXPORTING) {
                SystemNnnFullGameAuditStateStore.update(
                    this,
                    SystemNnnFullGameAuditStateStore.STATUS_READY,
                    snapshot.title,
                    "Сохранение отменено. Готовый JSON остался внутри приложения.",
                    1f,
                    staged.length()
                )
            }
            stopSelfResult(latestStartId)
            return
        }
        token.set(true)
        val isExporting = snapshot.status == SystemNnnFullGameAuditStateStore.STATUS_EXPORTING
        SystemNnnFullGameAuditStateStore.update(
            this,
            if (isExporting) SystemNnnFullGameAuditStateStore.STATUS_EXPORTING
            else SystemNnnFullGameAuditStateStore.STATUS_RUNNING,
            snapshot.title,
            "Останавливаю текущую операцию…",
            snapshot.progress,
            snapshot.bytes
        )
        updateNotification(
            snapshot.title,
            "Останавливаю текущую операцию…",
            null,
            ongoing = true,
            showCancel = false,
            force = true
        )
        workerThread?.interrupt()
    }

    private fun releasePersistedDestinationPermission(destination: Uri) {
        runCatching {
            contentResolver.releasePersistableUriPermission(
                destination,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun acquireCpuWakeLock() {
        releaseCpuWakeLock()
        val powerManager = getSystemService(PowerManager::class.java)
            ?: throw IOException("Android could not access the power manager.")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:RamenFullGameAudit"
        ).apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_MILLIS)
        }
        cpuWakeLock = wakeLock
    }

    private fun releaseCpuWakeLock() {
        val wakeLock = cpuWakeLock
        cpuWakeLock = null
        if (wakeLock?.isHeld == true) {
            runCatching { wakeLock.release() }
        }
    }

    private fun checkNotCancelled(token: AtomicBoolean) {
        if (token.get() || Thread.currentThread().isInterrupted) {
            throw IOException("SCAN остановлен пользователем.")
        }
    }

    private fun resolveImportedGameDirectory(relativeDirectory: String): File {
        if (relativeDirectory.isBlank() || File(relativeDirectory).isAbsolute ||
            relativeDirectory.split('/').any { it == ".." }
        ) {
            throw IOException("Путь к импортированной игре некорректен.")
        }
        val requested = File(filesDir, relativeDirectory)
        if (java.nio.file.Files.isSymbolicLink(requested.toPath())) {
            throw IOException("Папка игры не может быть символической ссылкой.")
        }
        val gamesRoot = File(filesDir, "games").canonicalFile
        val root = requested.canonicalFile
        if (!root.isDirectory || root == gamesRoot ||
            !root.toPath().startsWith(gamesRoot.toPath())
        ) {
            throw IOException("Папка импортированной игры недоступна в приватном хранилище.")
        }
        return root
    }

    private fun enterForeground(
        title: String,
        message: String,
        progress: Float?,
        showCancel: Boolean
    ) {
        val notification = buildNotification(
            title,
            message,
            progress,
            ongoing = true,
            showCancel = showCancel
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(
        title: String,
        message: String,
        progress: Float?,
        ongoing: Boolean,
        showCancel: Boolean,
        force: Boolean = false
    ) {
        val now = SystemClock.elapsedRealtime()
        val percent = progress?.let { (it.coerceIn(0f, 1f) * 100).toInt() } ?: -1
        if (!force && now - lastNotificationUpdateMillis < 350L &&
            percent == lastNotificationPercent
        ) return
        lastNotificationUpdateMillis = now
        lastNotificationPercent = percent
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(title, message, progress, ongoing, showCancel)
        )
    }

    private fun buildNotification(
        title: String,
        message: String,
        progress: Float?,
        ongoing: Boolean,
        showCancel: Boolean
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Ramen · SCAN")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText("$title — $message"))
            .setContentIntent(openPendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(
                100,
                progress?.let { (it.coerceIn(0f, 1f) * 100).toInt() } ?: 0,
                progress == null
            )
        if (showCancel) {
            val cancelIntent = Intent(this, SystemNnnFullGameAuditService::class.java).apply {
                action = ACTION_CANCEL
            }
            val cancelPendingIntent = PendingIntent.getService(
                this,
                CANCEL_REQUEST_CODE,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Отменить",
                cancelPendingIntent
            )
        } else if (!ongoing) {
            builder.setProgress(0, 0, false)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Фоновый SCAN",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Показывает ход и результат полной проверки System-NNN."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        timeLimitReached = true
        releaseCpuWakeLock()
        cancellationToken?.set(true)
        workerThread?.interrupt()
        val snapshot = SystemNnnFullGameAuditStateStore.read(this)
        SystemNnnFullGameAuditStateStore.update(
            this,
            SystemNnnFullGameAuditStateStore.STATUS_FAILED,
            snapshot.title,
            "Android ограничил время фоновой операции. Запусти SCAN заново, когда приложение будет открыто."
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        cancellationToken?.set(true)
        workerThread?.interrupt()
        releaseCpuWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_SCAN = "io.github.helterfall.ramen.action.START_FULL_GAME_SCAN"
        const val ACTION_EXPORT_REPORT = "io.github.helterfall.ramen.action.EXPORT_FULL_GAME_SCAN"
        const val ACTION_CANCEL = "io.github.helterfall.ramen.action.CANCEL_FULL_GAME_SCAN"
        const val EXTRA_RELATIVE_DIRECTORY = "relative_directory"
        const val EXTRA_DESTINATION_URI = "destination_uri"
        const val EXTRA_TITLE = "audit_title"

        private const val CHANNEL_ID = "ramen_full_game_scan"
        private const val NOTIFICATION_ID = 7319
        private const val OPEN_APP_REQUEST_CODE = 7320
        private const val CANCEL_REQUEST_CODE = 7321
        private const val MAX_WAKE_LOCK_MILLIS = 5L * 60L * 60L * 1000L
    }
}
