package com.shilapi.xcertplay

import android.app.Application
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
 */
class DiPlayApplication : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                Toast.makeText(
                    this,
                    "DiPlay 启动错误: ${error.javaClass.simpleName}: ${error.message?.take(80)}",
                    Toast.LENGTH_LONG,
                ).show()
            }
            runCatching { appendCrash(this@DiPlayApplication, "uncaught on ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        /** Appends to app-external files (always writable) and the public Download folder when possible. */
        fun appendCrash(context: android.content.Context, what: String, error: Throwable) {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            val entry = "\n==== ${System.currentTimeMillis()} $what ====\n$trace\n"
            val targets = mutableListOf<File?>()
            targets.add(context.getExternalFilesDir(null))
            if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                targets.add(File(Environment.getExternalStorageDirectory(), "Download"))
            }
            targets.add(context.filesDir)
            targets.filterNotNull().forEach { dir ->
                runCatching {
                    dir.mkdirs()
                    File(dir, "diplay-crash.txt").appendText(entry)
                }
            }
        }
    }
}
