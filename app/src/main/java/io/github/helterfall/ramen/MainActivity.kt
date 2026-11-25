package io.github.helterfall.ramen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.helterfall.ramen.ui.RamenApp
import io.github.helterfall.ramen.diagnostics.CrashReportStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashReportStore = CrashReportStore(filesDir)
        val previousCrashReport = crashReportStore.read()
        setContent {
            RamenApp(
                crashReport = previousCrashReport,
                onCrashReportDismiss = crashReportStore::clear
            )
        }
    }
}
