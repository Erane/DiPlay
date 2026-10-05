package com.shilapi.xcertplay

import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.multidex.MultiDexApplication
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Adds a startup-crash reporter on top of legacy multidex: car ROMs frequently swallow the
 * "has stopped" dialog, so every uncaught throwable is also toasted and appended to a file the
 * unit's own file manager can open (app-external files dir, no storage permission needed).
 *
 * The handler is installed in [attachBaseContext] — before provider installation and onCreate —
 * and onCreate writes a marker file so a silent launch can be told apart from a dead process:
 * no marker means the process never ran at all (launcher/ROM side), a marker without UI means
 * something crashed after it.
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
            runCatching { appendCrash(base, "uncaught on ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { writeMarker(this, "application onCreate") }
    }

    companion object {
        /** Appends to app-external files (always writable) and the public Download folder when possible. */
        fun appendCrash(context: Context, what: String, error: Throwable) {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            appendLine(context, "diplay-crash.txt", "==== ${System.currentTimeMillis()} $what ====\n$trace\n")
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
