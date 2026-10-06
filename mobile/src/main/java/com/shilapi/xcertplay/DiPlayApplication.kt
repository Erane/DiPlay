package com.shilapi.xcertplay

import com.shilapi.xcertplay.systemServiceCompat
import com.shilapi.xcertplay.checkSelfPermissionCompat
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.multidex.MultiDexApplication
import androidx.core.content.ContextCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Adds a startup-crash reporter on top of legacy multidex: car ROMs frequently swallow the
 * "has stopped" dialog, so every uncaught throwable is also toasted and appended to a file the
 * unit's own file manager can open (app-external files dir, no storage permission needed).
 *
 * The handler is installed in [attachBaseContext] — before provider installation and onCreate —
 * and the real platform profile (SDK level, kernel, ABI, RAM — settings screens on car ROMs
 * often show a rebranded version string) plus an onCreate marker are written to a marker file,
 * so a silent launch can be told apart from a dead process: no marker means the process never
 * ran at all (launcher/ROM side), a marker without UI means something crashed after it.
 */
class DiPlayApplication : MultiDexApplication() {
    override fun attachBaseContext(base: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                Toast.makeText(
                    base,
                    "DiPlay 启动错误: ${error.javaClass.simpleName}: ${error.message?.take(80)}",
                    Toast.LENGTH_LONG,
                ).show()
            }
            runCatching {
                com.shilapi.xcertplay.legacy.LegacyDiagnostics.append(
                    base, com.shilapi.xcertplay.legacy.LegacyDiagnostics.CRASH_FILE,
                    com.shilapi.xcertplay.legacy.LegacyDiagnostics.crashEntry("uncaught on ${thread.name}", error),
                )
            }
            previous?.uncaughtException(thread, error)
        }
        super.attachBaseContext(base)
        // Earliest moment with a context: record the true platform before anything can fail.
        runCatching { appendLine(base, "diplay-started.txt", platformReport(base) + "\n") }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { appendLine(this, "diplay-started.txt", "${System.currentTimeMillis()} application onCreate\n") }
    }

    companion object {
        /** One profile of everything that defines the real runtime on this unit. */
        fun platformReport(context: Context): String = buildString {
            appendLine("---- 系统档案 ${System.currentTimeMillis()} ----")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}, ${Build.VERSION.CODENAME.takeIf { it != "REL" } ?: "release"})")
            appendLine("ROM build: ${Build.DISPLAY}")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / device=${Build.DEVICE} product=${Build.PRODUCT}")
            appendLine("硬件: hw=${Build.HARDWARE} board=${Build.BOARD}")
            appendLine("内核: ${System.getProperty("os.version")}")
            appendLine("ART: ${System.getProperty("java.vm.version")} (${System.getProperty("java.vm.name")})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS?.joinToString(",")}")
            appendLine("指纹: ${Build.FINGERPRINT}")
            runCatching {
                systemServiceCompat(context, ActivityManager::class.java)?.let { am ->
                    val mem = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(mem)
                    appendLine("内存: 总 ${(mem.totalMem / 1048576)} MB, 可用 ${(mem.availMem / 1048576)} MB, 低内存模式=${mem.lowMemory}")
                }
            }
        }

        /** Appends to app-external files (always writable) and the public Download folder when possible. */
        fun appendCrash(context: Context, what: String, error: Throwable) {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            appendLine(
                context,
                "diplay-crash.txt",
                "==== ${System.currentTimeMillis()} $what (Android ${Build.VERSION.RELEASE}/SDK ${Build.VERSION.SDK_INT}) ====\n$trace\n",
            )
        }

        /** Marks that our process really started; its absence means the launch never reached the app. */
        fun writeMarker(context: Context, what: String) {
            appendLine(context, "diplay-started.txt", "${System.currentTimeMillis()} $what\n")
        }

        private fun appendLine(context: Context, name: String, text: String) {
            val targets = mutableListOf<File?>()
            targets.add(context.getExternalFilesDir(null))
            if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                targets.add(File(Environment.getExternalStorageDirectory(), "Download"))
            }
            targets.add(context.filesDir)
            targets.filterNotNull().forEach { dir ->
                runCatching {
                    dir.mkdirs()
                    File(dir, name).appendText(text)
                }
            }
        }
    }
}
