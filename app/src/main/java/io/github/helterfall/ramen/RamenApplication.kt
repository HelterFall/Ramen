package io.github.helterfall.ramen

import android.app.Application
import android.os.Process
import io.github.helterfall.ramen.diagnostics.CrashReportStore
import kotlin.system.exitProcess

/**
 * Persists uncaught failures before Android's default handler terminates the process.
 * Fatal main-thread failures cannot be recovered into the already-crashed Activity;
 * MainActivity displays the saved report on the next process launch.
 */
class RamenApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { CrashReportStore(filesDir).save(thread.name, error) }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
