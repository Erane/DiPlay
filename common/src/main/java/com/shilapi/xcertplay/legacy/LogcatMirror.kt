package com.shilapi.xcertplay.legacy

import android.content.Context
import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Tees this process's own logcat lines into [LegacyDiagnostics.LOG_FILE]. Log.e stack traces
 * (RTSP handler failures, decoder errors, BT IO) otherwise exist only in logcat, which no car
 * unit has attached when the failure happens. Since 4.1 an app can only read its own UID's log
 * lines, and the PID filter keeps even unrestricted ROMs from flooding the file with noise.
 * Lines are batched: [LegacyDiagnostics.append] opens every target per call, so per-line writes
 * would hammer the storage on busy sessions.
 */
object LogcatMirror {
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        val pending = ConcurrentLinkedQueue<String>()
        Thread({
            runCatching {
                val flusher = Thread({
                    while (true) {
                        try {
                            Thread.sleep(1500)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                        val batch = ArrayList<String>(48)
                        while (true) {
                            val line = pending.poll() ?: break
                            batch.add(line)
                        }
                        if (batch.isNotEmpty()) {
                            runCatching {
                                LegacyDiagnostics.append(
                                    appContext,
                                    LegacyDiagnostics.LOG_FILE,
                                    batch.joinToString("\n"),
                                )
                            }
                        }
                    }
                }, "diplay-log-flush").apply { isDaemon = true; start() }

                val pid = Process.myPid()
                val marker = "($pid)"
                val process = ProcessBuilder("logcat", "-v", "time").start()
                val reader = BufferedReader(InputStreamReader(process.inputStream), 8192)
                while (true) {
                    val line = reader.readLine() ?: break
                    if (marker in line) pending.add(line)
                }
            }
        }, "diplay-logcat-mirror").apply { isDaemon = true; start() }
    }
}
