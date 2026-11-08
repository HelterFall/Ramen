package io.github.helterfall.ramen.runtime.renpy

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File

data class RenPyRuntimeAvailability(
    val isReady: Boolean,
    val missingComponents: List<String>
) {
    fun message(): String =
        if (isReady) {
            "Встроенный Ren'Py Runtime 8.5.3 доступен."
        } else {
            "Ren'Py распознан, но Android Runtime 8.5.3 не включён: " +
                missingComponents.joinToString()
        }
}

/**
 * Strictly checks the Android host and the matching 8.5.3 payload.
 * A lone native library or placeholder Activity must never enable playback.
 */
object RenPyRuntimeAvailabilityProbe {
    const val REQUIRED_RENPY_VERSION = "8.5.3"
    const val HOST_PROTOCOL_VERSION = 2

    // Keep playback unavailable until a real Android game-launch smoke test passes.
    const val EMBEDDED_LAUNCH_ENABLED = false

    const val ACTIVITY_CLASS = "org.renpy.android.PythonSDLActivity"
    const val HOST_PROTOCOL_METADATA = "io.github.helterfall.ramen.RENPY_HOST_PROTOCOL"
    const val DESCRIPTOR_ASSET = "renpy-runtime.json"

    fun inspect(context: Context): RenPyRuntimeAvailability {
        val missing = mutableListOf<String>()
        if (!EMBEDDED_LAUNCH_ENABLED) {
            missing += "Ren'Py launch is temporarily disabled until Runtime import and device smoke tests pass"
        }

        val activityInfo = runCatching {
            val component = ComponentName(context.packageName, ACTIVITY_CLASS)
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getActivityInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getActivityInfo(component, PackageManager.GET_META_DATA)
            }
        }.getOrNull()

        if (activityInfo == null) {
            missing += "PythonSDLActivity"
        } else {
            val declaredProtocol = activityInfo.metaData?.getInt(HOST_PROTOCOL_METADATA, -1)
            if (declaredProtocol != HOST_PROTOCOL_VERSION) {
                missing += "host protocol metadata v2"
            }
            val expectedProcessName = "${context.packageName}:renpy"
            if (activityInfo.processName != expectedProcessName) {
                missing += "isolated Ren'Py process (:renpy)"
            }
        }

        // Ren'Py 8.5.3 RAPT statically links SDL2 into librenpython.so.
        // The official Android host loads only this shared library.
        val nativeDirectory = context.applicationInfo.nativeLibraryDir
        if (!File(nativeDirectory, "librenpython.so").isFile) {
            missing += "librenpython.so (Ren'Py 8.5.3 engine with SDL2)"
        }
        val hasPrivateRuntimePayload = runCatching {
            context.assets.open("private.mp3").use { input -> input.read() >= 0 }
        }.getOrDefault(false)
        if (!hasPrivateRuntimePayload) {
            missing += "private.mp3"
        }

        val hasCommonRuntimeAssets = runCatching {
            context.assets.list("x-renpy/x-common")?.isNotEmpty() == true
        }.getOrDefault(false)
        if (!hasCommonRuntimeAssets) {
            missing += "Ren'Py common assets (x-renpy/x-common)"
        }

        val descriptorValid = runCatching {
            val descriptorText = context.assets.open(DESCRIPTOR_ASSET).bufferedReader().use {
                val value = it.readText()
                value.takeIf { text -> text.length <= MAX_DESCRIPTOR_CHARS }
            } ?: return@runCatching false

            val descriptor = JSONObject(descriptorText)
            descriptor.optString("renpyVersion") == REQUIRED_RENPY_VERSION &&
                descriptor.optInt("hostProtocol", -1) == HOST_PROTOCOL_VERSION
        }.getOrDefault(false)
        if (!descriptorValid) {
            missing += "$DESCRIPTOR_ASSET (8.5.3 / protocol 2)"
        }

        return RenPyRuntimeAvailability(missing.isEmpty(), missing)
    }

    private const val MAX_DESCRIPTOR_CHARS = 4_096
}
