package com.tung.readloud.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A small on-device log of playback events, so a stall on the phone can be looked at later without adb.
 * Lines also go to logcat. The file is cut back to its newer half once it grows past [MAX_BYTES].
 */
object EventLog {
    private const val TAG = "ReadLoud"
    private const val MAX_BYTES = 256 * 1024
    private val writer = Executors.newSingleThreadExecutor()
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        synchronized(this) {
            if (file != null) return
            file = File(context.applicationContext.filesDir, "events.log")
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                // Written right away: the process is about to die.
                runCatching { append(line("CRASH on ${thread.name}: ${Log.getStackTraceString(error).take(4000)}")) }
                previous?.uncaughtException(thread, error)
            }
        }
    }

    fun log(message: String, error: Throwable? = null) {
        if (error == null) Log.i(TAG, message) else Log.w(TAG, message, error)
        val text = line(if (error == null) message else "$message: $error")
        writer.execute { runCatching { append(text) } }
    }

    fun read(): String = runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()

    fun clear() {
        writer.execute { runCatching { file?.delete() } }
    }

    private fun line(message: String) = synchronized(format) { format.format(Date()) } + " " + message + "\n"

    private fun append(text: String) {
        val f = file ?: return
        f.appendText(text)
        if (f.length() > MAX_BYTES) {
            val kept = f.readText().takeLast(MAX_BYTES / 2)
            f.writeText(kept.substringAfter('\n'))
        }
    }
}
