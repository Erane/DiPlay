package com.shilapi.xcertplay.legacy

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Diagnostic file IO that works on Android 4.3/4.4 car units: the public storage root is
 * written first (file-manager visible; on pre-23 the storage permission is granted at
 * install), with the app-private dirs as fallbacks. Some ROMs (Allwinner T3) return a null
 * or unusable getExternalFilesDir, so every target is attempted in turn.
 */
object LegacyDiagnostics {
    const val LOG_FILE = "legacy-log.txt"
    const val CRASH_FILE = "diplay-crash.txt"
    const val STARTED_FILE = "diplay-started.txt"

    /** Real platform identity: settings screens on car ROMs show rebranded version strings. */
    fun platformReport(context: Context): String = buildString {
        appendLine("---- 系统档案 ${System.currentTimeMillis()} ----")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("ROM build: ${Build.DISPLAY}")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / device=${Build.DEVICE} product=${Build.PRODUCT}")
        appendLine("硬件: hw=${Build.HARDWARE} board=${Build.BOARD}")
        appendLine("内核: ${System.getProperty("os.version")}")
        appendLine("ART: ${System.getProperty("java.vm.version")} (${System.getProperty("java.vm.name")})")
        appendLine("ABI: ${abiList()}")
        appendLine("指纹: ${Build.FINGERPRINT}")
        runCatching {
            val activityManager = context.getSystemService("activity") as? android.app.ActivityManager
            if (activityManager != null) {
                val mem = android.app.ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(mem)
                appendLine("内存: 总 ${(mem.totalMem / 1048576)} MB, 可用 ${(mem.availMem / 1048576)} MB, 低内存模式=${mem.lowMemory}")
            }
        }
    }

    fun abiList(): String =
        if (Build.VERSION.SDK_INT >= 21) {
            Build.SUPPORTED_ABIS?.joinToString(",") ?: "?"
        } else {
            "${Build.CPU_ABI} / ${Build.CPU_ABI2}"
        }

    /**
     * Write targets, most file-manager-visible first:
     * 1. /mnt/sdcard (the traditional 4.x path this unit's file manager and tools can use)
     * 2. the public root per the framework view
     * 3. the app's external files dir
     * 4. the internal files dir (last resort; needs the probe to read it back)
     */
    fun dirs(context: Context): List<File> {
        val dirs = mutableListOf<File>()
        if (Build.VERSION.SDK_INT < 29 &&
            Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
        ) {
            dirs += File("/mnt/sdcard")
        }
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            dirs += File(Environment.getExternalStorageDirectory(), "DiPlay")
        }
        context.getExternalFilesDir(null)?.let { dirs += it }
        dirs += context.filesDir
        return dirs
    }

    fun append(context: Context, name: String, text: String) {
        for (dir in dirs(context)) {
            runCatching {
                dir.mkdirs()
                File(dir, name).appendText(text)
            }
        }
    }

    /** Concatenates the file from every dir that has one (newest entry first is up to the caller). */
    fun readAll(context: Context, name: String): String? {
        var found: String? = null
        for (dir in dirs(context)) {
            val file = File(dir, name)
            if (file.exists()) {
                found = (found ?: "") + runCatching { file.readText() }.getOrElse { "(读取失败: $it)" } + "\n"
            }
        }
        return found
    }

    fun crashEntry(what: String, error: Throwable): String {
        val trace = java.io.StringWriter()
            .also { java.io.PrintWriter(it).use { w -> error.printStackTrace(w) } }
            .toString()
        return "==== ${System.currentTimeMillis()} $what (SDK ${Build.VERSION.SDK_INT}) ====\n$trace\n"
    }
}
