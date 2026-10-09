package com.tribal.vpn

import android.app.Application
import java.io.File

/**
 * Captures uncaught crashes (any thread) to a file the next launch reads and
 * shows in the in-app Logs screen. Rationale: the dev phone has no adb/logcat
 * access, so without this a crash vanishes with no diagnostics. The original
 * handler still runs afterwards, so system crash behavior is unchanged - this
 * only persists the stack trace.
 */
class TribalVpnApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                File(filesDir, LAST_CRASH_FILE).writeText(
                    "Thread: ${thread.name}\n" + throwable.stackTraceToString()
                )
            } catch (_: Throwable) {
                // Crash reporting must never crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val LAST_CRASH_FILE = "last_crash.txt"
    }
}
