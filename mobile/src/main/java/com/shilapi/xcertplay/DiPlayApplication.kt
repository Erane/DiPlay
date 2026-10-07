package com.shilapi.xcertplay

import android.content.Context
import android.widget.Toast
import androidx.multidex.MultiDexApplication
import com.shilapi.xcertplay.legacy.LegacyDiagnostics
import com.shilapi.xcertplay.legacy.LegacyDiagnostics.CRASH_FILE
import com.shilapi.xcertplay.legacy.LegacyDiagnostics.STARTED_FILE

/**
 * Adds a startup-crash reporter on top of legacy multidex: car ROMs frequently swallow the
 * "has stopped" dialog, so every uncaught throwable is also toasted and appended to a file the
 * unit's own file manager can open.
 *
 * The handler is installed in [attachBaseContext] — before provider installation and onCreate —
 * and the real platform profile (SDK level, kernel, ABI, RAM — settings screens on car ROMs
 * often show a rebranded version string) plus an onCreate marker are written to a marker file,
 * so a silent launch can be told apart from a dead process: no marker means the process never
 * ran at all (launcher/ROM side), a marker without UI means something crashed after it.
 *
 * Writing goes through [LegacyDiagnostics] alone: a second, different target list here produced
 * files the diagnostics screens never read back.
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
                LegacyDiagnostics.append(base, CRASH_FILE, LegacyDiagnostics.crashEntry("uncaught on ${thread.name}", error))
            }
            previous?.uncaughtException(thread, error)
        }
        super.attachBaseContext(base)
        // Earliest moment with a context: record the true platform before anything can fail.
        runCatching {
            LegacyDiagnostics.append(base, STARTED_FILE, LegacyDiagnostics.platformReport(base) + "\n")
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Car units have no adb: mirror this process's logcat (stack traces included)
        // into the file the user can pull with a memory stick.
        runCatching { com.shilapi.xcertplay.legacy.LogcatMirror.start(this) }
        runCatching {
            LegacyDiagnostics.append(this, STARTED_FILE, "${System.currentTimeMillis()} application onCreate\n")
        }
    }
}
