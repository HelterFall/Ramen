package io.github.helterfall.ramen.runtime.hsp3

/**
 * Sandboxed compatibility facade for HSP imports.
 *
 * This intentionally does not use JNI, reflection, System.loadLibrary, or
 * platform DLL loading. Every imported function returns zero until a safe,
 * explicit Android implementation is added for that exact API.
 */
class Hsp3ExternalCallShim(
    private val logger: (String) -> Unit = {}
) {
    data class Result(
        val returnValue: Long,
        val recognized: Boolean,
        val diagnostic: String
    )

    fun invoke(libraryName: String, functionName: String, argumentCount: Int): Result {
        val library = normalizeLibrary(libraryName)
        val function = functionName.trim()
        val recognized = library in SUPPORTED_LIBRARIES &&
            function.lowercase() in SAFE_ZERO_STUBS

        val message = if (recognized) {
            "Win32/HSP shim: $library!$function($argumentCount args) returned 0."
        } else {
            "Unknown external call: $library!$function($argumentCount args); logged and returned 0."
        }
        if (!recognized) logger(message)
        return Result(returnValue = 0L, recognized = recognized, diagnostic = message)
    }

    private fun normalizeLibrary(name: String): String {
        val normalized = name.trim().substringAfterLast('/').substringAfterLast('\\').lowercase()
        return if (normalized.startsWith("<") || normalized.endsWith(".dll") || normalized.endsWith(".hpi")) {
            normalized
        } else {
            "$normalized.dll"
        }
    }

    companion object {
        private val SUPPORTED_LIBRARIES = setOf(
            "kernel32.dll", "user32.dll", "winmm.dll", "gdi32.dll", "shell32.dll",
            "ole32.dll", "oleaut32.dll", "comctl32.dll", "comdlg32.dll", "advapi32.dll",
            "version.dll", "shlwapi.dll", "imm32.dll", "msvcrt.dll", "hsp3.dll",
            "hsp3dish.dll", "hsp3win.dll"
        )

        /** Safe no-op/zero-result APIs; no operating-system side effects are performed. */
        private val SAFE_ZERO_STUBS = setOf(
            "gettickcount", "gettickcount64", "timegettime", "getlastError".lowercase(),
            "setlasterror", "getmodulehandlea", "getmodulehandlew", "getcommandlinea",
            "getcommandlinew", "getcurrentprocessid", "getcurrentthreadid", "getversion",
            "getversionexA".lowercase(), "getsystemmetrics", "getasynckeystate", "getkeystate",
            "getcursorpos", "getforegroundwindow", "iswindow", "getactivewindow",
            "getdesktopwindow", "getdc", "releasedc", "getdevicecaps", "createpen",
            "createbrushindirect", "deleteobject", "selectobject", "bitblt", "stretchblt",
            "textouta", "textoutw", "drawtexta", "drawtextw", "messageboxa", "messageboxw",
            "shellexecutea", "shellexecutew", "shgetfolderpatha", "shgetfolderpathw",
            "playsounda", "playsoundw", "waveoutopen", "waveoutclose", "waveoutwrite",
            "waveoutreset", "waveoutprepareheader", "waveoutunprepareheader", "timebeginperiod",
            "timeendperiod", "sleep", "settimer", "killtimer", "showwindow", "setwindowpos",
            "movewindow", "destroywindow", "registerclassa", "registerclassw",
            "createwindowexa", "createwindowexw", "coinitialize", "couninitialize",
            "cocreateinstance", "sysallocstring", "sysfreestring", "getfileattributesa",
            "getfileattributesw", "createfilea", "createfilew", "closehandle", "readfile",
            "writefile", "setfilepointer", "getfilesize", "getwindowsdirectorya",
            "getwindowsdirectoryw", "getsystemdirectorya", "getsystemdirectoryw",
            "hspfunc_prm_getv", "hspfunc_prm_geti", "hspfunc_prm_getdi", "hspfunc_prm_gets",
            "hspfunc_prm_getds", "hspfunc_val_realloc", "hspfunc_fread", "hspfunc_fsize",
            "hspfunc_getbmscr", "hspfunc_getobj", "hspfunc_setobj", "hspfunc_addobj",
            "hspfunc_puterror", "hspfunc_getproc", "hspfunc_seekproc", "hspfunc_prm_next",
            "hspfunc_prm_get", "hspfunc_prm_getd", "hspfunc_prm_getdd", "hspfunc_prm_getlb",
            "hspfunc_prm_getpval", "hspfunc_malloc", "hspfunc_free", "hspfunc_expand",
            "hspfunc_hspevent", "hspfunc_registvar", "hspfunc_setpc", "hspfunc_call",
            "hspfunc_mref", "hspfunc_dim", "hspfunc_redim", "hspfunc_array",
            "hspfunc_varname", "hspfunc_seekvar"
        ).map { it.lowercase() }.toSet()
    }
}
