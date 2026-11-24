package io.github.helterfall.ramen.diagnostics

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportStoreTest {
    @Test
    fun savesUncaughtStackTraceAndClearsItAfterAcknowledgement() {
        val directory = Files.createTempDirectory("ramen-crash-report").toFile()
        try {
            val store = CrashReportStore(directory)
            val failure = IllegalStateException("Canvas: trying to use a recycled bitmap")
            failure.stackTrace = arrayOf(
                StackTraceElement("android.graphics.BaseCanvas", "throwIfCannotDraw", "BaseCanvas.java", 82)
            )

            store.save("main", failure)
            val report = store.read()

            assertNotNull(report)
            assertTrue(report!!.contains("Thread: main"))
            assertTrue(report.contains("IllegalStateException"))
            assertTrue(report.contains("recycled bitmap"))
            assertTrue(report.contains("android.graphics.BaseCanvas.throwIfCannotDraw"))

            store.clear()
            assertFalse(File(directory, "last-crash-report.txt").exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
