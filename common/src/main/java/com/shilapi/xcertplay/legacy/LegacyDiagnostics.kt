package com.shilapi.xcertplay.legacy

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One diagnostic write, reported so the unit's own screen can say where the file actually is. */
data class LegacyWriteResult(val label: String, val path: String, val error: Throwable?) {
    override fun toString(): String = "$label $path ${error?.javaClass?.simpleName ?: "OK"}"
}

/** A diagnostic file read back, tagged with the directory it was found in. */
data class LegacyLogFile(val path: String, val text: String)

/**
 * Diagnostic file IO for Android 4.3/4.4 car units. Every reachable target gets a copy and every
 * target reports its outcome, because a unit with no adb and no share-target is diagnosed by the
 * user reading a path off the screen or pulling a memory stick.
 */
object LegacyDiagnostics {
    const val LOG_FILE = "legacy-log.txt"
    const val CRASH_FILE = "diplay-crash.txt"
    const val STARTED_FILE = "diplay-started.txt"

    /** Real platform identity: settings screens on car ROMs show rebranded version strings. */
    fun platformReport(context: Context): String = buildString {
        appendLine("---- 系统档案 ${System.currentTimeMillis()} ----")
        appendLine("App: ${context.packageName} ${appVersion(context)}")
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
        appendLine()
        append(AndroidVersionProbe.report())
    }

    fun appVersion(context: Context): String = runCatching {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        "v" + pkg.versionName + " (vc" + pkg.versionCode + ")"
    }.getOrDefault("version unknown")

    fun abiList(): String =
        if (Build.VERSION.SDK_INT >= 21) {
            Build.SUPPORTED_ABIS?.joinToString(",") ?: "?"
        } else {
            "${Build.CPU_ABI} / ${Build.CPU_ABI2}"
        }

    /**
     * Targets, most file-manager-visible first. One entry per location: `/mnt/sdcard` is only a
     * symlink of the public root on 4.x, and listing both writes the same file twice so every
     * read-back comes back doubled. `getExternalFilesDirs` is API 19 while this build floors at
     * 18, and it is the plural form that reaches a mounted memory stick.
     */
    fun targets(context: Context): List<Pair<String, File>> {
        val targets = mutableListOf<Pair<String, File>>()
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            targets += "共享存储根" to Environment.getExternalStorageDirectory()
        }
        val external = if (Build.VERSION.SDK_INT >= 19) {
            context.getExternalFilesDirs(null).toList().filterNotNull()
        } else {
            listOfNotNull(context.getExternalFilesDir(null))
        }
        external.forEachIndexed { index, directory -> targets += "应用外部目录$index" to directory }
        targets += "应用私有目录" to context.filesDir
        return targets
    }

    /** Rotate the running log instead of letting a soak test fill the stick. */
    private const val MAX_LOG_BYTES = 24L * 1024 * 1024

    fun append(context: Context, name: String, text: String): List<LegacyWriteResult> =
        writeAll(targets(context).map { (label, dir) -> label to File(dir, name) }) { appendText(text) }

    /** A fresh timestamped file per save: "this attempt" must stay separable from the last one. */
    fun saveSnapshot(context: Context, report: String): List<LegacyWriteResult> {
        val name = "diplay-log-" + SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        return writeAll(targets(context).map { (label, dir) -> label to File(dir, name) }) { writeText(report) }
    }

    private fun writeAll(
        files: List<Pair<String, File>>,
        write: File.() -> Unit,
    ): List<LegacyWriteResult> = files.map { (label, file) ->
        LegacyWriteResult(
            label = label,
            path = file.absolutePath,
            error = runCatching {
                file.parentFile?.mkdirs()
                if (file.name == LOG_FILE && file.length() > MAX_LOG_BYTES) {
                    val previous = File(file.parentFile, "legacy-log.1.txt")
                    previous.delete()
                    file.renameTo(previous)
                }
                file.write()
            }.exceptionOrNull(),
        )
    }

    /**
     * The newest copy of one log, with the path it came from. Every target receives the same
     * write, so returning all of them tripled the exported report; the largest file wins, because
     * a target written before the storage grant can be short or missing.
     */
    fun readAll(context: Context, name: String): LegacyLogFile? {
        var best: LegacyLogFile? = null
        for ((_, dir) in targets(context)) {
            val file = File(dir, name)
            if (!file.exists()) continue
            val text = runCatching { file.readText() }.getOrElse { continue }
            if (best == null || text.length > best.text.length) {
                best = LegacyLogFile(file.absolutePath, text)
            }
        }
        return best
    }

    fun crashEntry(what: String, error: Throwable): String {
        val trace = java.io.StringWriter()
            .also { java.io.PrintWriter(it).use { w -> error.printStackTrace(w) } }
            .toString()
        return "==== ${System.currentTimeMillis()} $what (SDK ${Build.VERSION.SDK_INT}) ====\n$trace\n"
    }
}
