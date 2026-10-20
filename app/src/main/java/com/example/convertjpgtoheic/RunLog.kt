package com.example.convertjpgtoheic

import android.content.Context
import android.util.Log
import java.io.File
import java.io.Writer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A plain-text record of each run, one file per run, kept in the app's own folder on shared
 * storage: `Android/data/com.example.convertjpgtoheic/files/logs/`.
 *
 * The on-screen report lives only until the app closes and lists a sample of failures, and
 * logcat's buffer holds about an hour of a run. This keeps every failure with its reason, the
 * music converter's per-song lines and a closing summary, for as long as [KEEP] newer runs allow.
 * Writing it never stops a run: any I/O problem here is logged and otherwise ignored.
 */
object RunLog {

    private const val TAG = "RunLog"
    private const val KEEP = 30

    private var out: Writer? = null
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Starts a new file for a run of [mode]; closes any run left open. */
    @Synchronized
    fun start(context: Context, mode: RunMode, options: RunOptions) {
        close()
        try {
            val dir = context.getExternalFilesDir("logs") ?: return
            dir.listFiles { f -> f.name.startsWith("run-") }?.sortedByDescending { it.name }?.drop(KEEP - 1)
                ?.forEach { it.delete() }
            val file = File(dir, "run-${LocalDateTime.now().format(stamp)}-${mode.name.lowercase()}.txt")
            out = file.bufferedWriter()
            line("${mode.name} started · $options")
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the run log", e)
            out = null
        }
    }

    /** The newest run's file, or null if there is none yet. */
    fun latest(context: Context): File? =
        context.getExternalFilesDir("logs")?.listFiles { f -> f.name.startsWith("run-") }?.maxByOrNull { it.name }

    /** One timestamped line; nothing if no run is being logged. */
    @Synchronized
    fun line(text: String) {
        val w = out ?: return
        try {
            w.write("${LocalDateTime.now().format(clock)} $text\n")
            w.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Could not write to the run log", e)
            close()
        }
    }

    /** The run's closing summary — every count, without the lists — then the file is closed. */
    @Synchronized
    fun finish(report: RunReport) {
        line("finished · ${report.copy(failures = emptyList(), undated = emptyList())}")
        close()
    }

    @Synchronized
    private fun close() {
        runCatching { out?.close() }
        out = null
    }
}
