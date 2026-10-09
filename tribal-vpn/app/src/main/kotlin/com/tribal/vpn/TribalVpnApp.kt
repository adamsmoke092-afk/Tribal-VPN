package com.tribal.vpn

import android.app.Application
import com.tribal.vpn.diagnostics.Diagnostics
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.Security

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
        // First: record whether the previous run died abnormally and (re)write
        // the running marker, before anything else can die.
        Diagnostics.init(this)

        // Android's built-in "BC" provider is a stub without modern
        // algorithms (sshj's SSH key exchange needs X25519), and it occupies
        // the "BC" name so sshj's own bundled BouncyCastle never registers -
        // Security.addProvider silently ignores duplicate provider names.
        // Swap the stub for the real, bundled BC for this process. Everything
        // else in the app resolves crypto via the default provider order
        // (Conscrypt first), so nothing else is affected.
        try {
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        } catch (_: Throwable) {
            // If this fails, SSH fails honestly at key exchange instead.
        }

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
