package com.tribal.vpn.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Forensic instrumentation for deaths the Java crash handler can't see
 * (native crashes, linker aborts, lmkd/killer signals) - the dev phone has
 * no adb/logcat access, so the app must collect its own evidence.
 *
 * Everything here is best-effort: no method may ever throw, because
 * diagnostics that crash the app would destroy the evidence they exist to
 * collect.
 */
object Diagnostics {

    private const val APP_LOG = "app_runtime_log.txt"
    private const val APP_LOG_OLD = "app_runtime_log.old.txt"
    private const val DIAG_REPORT = "last_run_diagnostics.txt"
    private const val RUNNING_MARKER = "running.marker"
    private const val LOAD_PROBE_MARKER = "load_probe.marker"
    private const val CRASHED_VARIANTS = "crashed_variants.txt"
    private const val APP_LOG_MAX_BYTES = 128 * 1024
    private const val LOGCAT_MAX_BYTES = 256 * 1024

    @Volatile
    private var filesDir: File? = null

    /** True when the previous process died without a clean Activity finish. */
    @Volatile
    var previousRunEndedAbnormally: Boolean = false
        private set

    /**
     * PackageInfo.lastUpdateTime of the current install. Native-load crash
     * records are only valid for the install they were written in - an app
     * update must re-test every variant, not inherit the old verdicts.
     */
    @Volatile
    var installStamp: Long = 0L
        private set

    /** Call from Application.onCreate - before anything else can die. */
    fun init(context: Context) {
        filesDir = context.filesDir
        installStamp = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (_: Throwable) {
            0L
        }
        previousRunEndedAbnormally = try {
            File(filesDir, RUNNING_MARKER).exists()
        } catch (_: Throwable) {
            false
        }
        writeMarker()
    }

    // --- #1 durable app-log sink -------------------------------------------

    /**
     * Mirrors every appendLog() line to disk with flush + fsync so the last
     * line before a hard death (SIGKILL / native crash) survives. Cheap here
     * because the log volume is human-scale, and durability is the point.
     */
    fun appendLogLine(timestampMillis: Long, plain: String?, technical: String?, isError: Boolean) {
        val dir = filesDir ?: return
        try {
            val file = File(dir, APP_LOG)
            if (file.length() > APP_LOG_MAX_BYTES) {
                val old = File(dir, APP_LOG_OLD)
                old.delete()
                file.renameTo(old)
            }
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                .format(Date(timestampMillis))
            val level = if (isError) "E" else "I"
            val line = "$stamp $level ${plain ?: "-"} | ${technical ?: "-"}\n"
            FileOutputStream(file, true).use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        } catch (_: Throwable) {
            // Diagnostics must never crash the app.
        }
    }

    // --- #3 running marker --------------------------------------------------

    private fun writeMarker() {
        try {
            FileOutputStream(File(filesDir, RUNNING_MARKER)).use { out ->
                out.write(
                    SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                        .format(Date()).toByteArray(Charsets.UTF_8)
                )
                out.fd.sync()
            }
        } catch (_: Throwable) {
            // Diagnostics must never crash the app.
        }
    }

    fun clearRunningMarker() {
        try {
            filesDir?.let { File(it, RUNNING_MARKER).delete() }
        } catch (_: Throwable) {
            // Diagnostics must never crash the app.
        }
    }

    // --- #5 native-load probe markers (crash-safe variant cascade) -------

    /**
     * A load-probe marker can only be left behind when the process dies
     * BETWEEN beginLoadProbe() and endLoadProbe() - i.e. System.loadLibrary
     * killed the process (linker abort, SIGSEGV inside JNI_OnLoad), which
     * no Java catch can intercept. The leftover marker is how the next
     * launch knows which variant did it. Consuming deletes the marker.
     */
    fun consumeLeftoverLoadProbe(): String? {
        val dir = filesDir ?: return null
        return try {
            val marker = File(dir, LOAD_PROBE_MARKER)
            if (!marker.exists()) return null
            val variant = marker.readText().trim()
            marker.delete()
            variant.ifEmpty { null }
        } catch (_: Throwable) {
            // Diagnostics must never crash the app.
            null
        }
    }

    /** Write + fsync BEFORE System.loadLibrary - best-effort, never throws. */
    fun beginLoadProbe(variant: String) {
        val dir = filesDir ?: return
        try {
            FileOutputStream(File(dir, LOAD_PROBE_MARKER)).use { out ->
                out.write(variant.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        } catch (_: Throwable) {
            // Diagnostics must never crash the app.
        }
    }

    /** Delete AFTER the load returns or throws (call from finally). */
    fun endLoadProbe() {
        try {
            filesDir?.let { File(it, LOAD_PROBE_MARKER).delete() }
        } catch (_: Throwable) {
            // Diagnostics must never crash the app.
        }
    }

    /**
     * Records "this variant killed the process during THIS install". A
     * skip stays in force only while PackageInfo.lastUpdateTime is
     * unchanged - an app update wipes the verdicts so every variant is
     * re-tested honestly instead of inheriting the old result.
     */
    fun recordVariantCrash(variant: String) {
        val dir = filesDir ?: return
        try {
            FileOutputStream(File(dir, CRASHED_VARIANTS), true).use { out ->
                out.write("$installStamp $variant\n".toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        } catch (_: Throwable) {
            // Diagnostics must never crash the app.
        }
    }

    /**
     * Variants that crashed a previous launch of THIS install.
     * Stale-stamp entries are dropped from the file as a side effect,
     * which is what un-skips all variants after an app update.
     */
    fun crashedVariants(): Set<String> {
        val dir = filesDir ?: return emptySet()
        return try {
            val file = File(dir, CRASHED_VARIANTS)
            if (!file.exists()) return emptySet()
            val entries = mutableListOf<Pair<Long, String>>()
            for (line in file.readLines()) {
                val idx = line.indexOf(' ')
                if (idx <= 0) continue
                val stamp = line.substring(0, idx).toLongOrNull() ?: continue
                val name = line.substring(idx + 1).trim()
                if (name.isNotEmpty()) entries.add(stamp to name)
            }
            entries.removeAll { it.first != installStamp }
            file.writeText(entries.joinToString("") { "${it.first} ${it.second}\n" })
            entries.map { it.second }.toSet()
        } catch (_: Throwable) {
            // Diagnostics must never crash the app.
            emptySet()
        }
    }

    // --- #2 + #4 launch-time capture ---------------------------------------

    /**
     * Runs on a daemon thread at each launch: captures this app's recent
     * logcat (uid-scoped, so it covers the previous dead pid), records the
     * OS's own exit reasons when available, and assembles one report file.
     */
    fun runStartupCapture(context: Context) {
        Thread {
            try {
                val dir = filesDir ?: return@Thread
                val logcat = captureLogcat()
                val report = buildString {
                    append("=== Tribal VPN last-run diagnostics ===\n")
                    append("captured: ")
                    append(SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()))
                    append("\n\n")
                    append(if (previousRunEndedAbnormally) {
                        "PREVIOUS RUN ENDED ABNORMALLY - crash, system kill, or Android eviction " +
                            "(the clean-shutdown marker was never cleared).\n\n"
                    } else {
                        "Previous run shut down cleanly (marker was cleared on Activity finish).\n\n"
                    })
                    append(exitReasonsBlock(context))
                    append("\n=== lines of interest ===\n")
                    append(fatalLines(logcat))
                    append("\n\n=== logcat tail (this app's uid, main+crash buffers) ===\n")
                    append(logcat)
                }
                File(dir, DIAG_REPORT).writeText(report)
            } catch (_: Throwable) {
                // Diagnostics must never crash the app.
            }
        }.apply { isDaemon = true }.start()
    }

    private fun captureLogcat(): String {
        return try {
            // logd already restricts unprivileged readers to their own uid's
            // entries (which covers the previous, dead pid too) - and --uid
            // is not supported by every logcat build (it produced a usage
            // dump on the dev phone), so we don't use it.
            val p = ProcessBuilder(
                "logcat", "-d", "-b", "main", "-b", "crash",
                "-t", "800", "-v", "time"
            ).redirectErrorStream(true).start()
            val bytes = ByteArrayOutputStream().use { sink ->
                val buf = ByteArray(8192)
                var total = 0
                p.inputStream.use { input ->
                    while (total < LOGCAT_MAX_BYTES) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        total += n
                    }
                }
                sink.toByteArray()
            }
            p.waitFor()
            bytes.toString(Charsets.UTF_8)
        } catch (t: Throwable) {
            "logcat capture failed: ${t.message}"
        }
    }

    private fun exitReasonsBlock(context: Context): String {
        if (Build.VERSION.SDK_INT < 30) {
            return "process exit reasons: not available before Android 11 (SDK 30)\n"
        }
        return try {
            val am = context.getSystemService(ActivityManager::class.java)
            val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            if (infos.isEmpty()) {
                "process exit reasons: none recorded\n"
            } else {
                val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                buildString {
                    append("process exit reasons (most recent first):\n")
                    for (info in infos) {
                        val reason = when (info.reason) {
                            ApplicationExitInfo.REASON_ANR -> "ANR"
                            ApplicationExitInfo.REASON_CRASH -> "JAVA CRASH"
                            ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH"
                            ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
                            ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory (lmkd)"
                            ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested (swipe/stop)"
                            ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
                            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
                            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
                            else -> "other/unknown(${info.reason})"
                        }
                        append("  pid=${info.pid} $reason status=${info.status} " +
                            "at=${fmt.format(Date(info.timestamp))}")
                        info.description?.let { append(" desc=").append(it) }
                        append('\n')
                    }
                }
            }
        } catch (t: Throwable) {
            "process exit reasons: unavailable (${t.message})\n"
        }
    }

    private fun fatalLines(logcat: String): String =
        logcat.lineSequence()
            .filter { line ->
                line.contains("Fatal signal") ||
                    line.contains("FATAL EXCEPTION") ||
                    line.contains("Abort message") ||
                    line.contains("ANR in") ||
                    line.contains("Out of memory") ||
                    line.contains("lowmemorykiller") ||
                    line.contains("Killing")
            }
            .joinToString("\n")
            .ifEmpty { "(no fatal/ANR/kill lines found in the captured window)" }

    // --- UI accessors --------------------------------------------------------

    fun readDiagnosticsReport(): String? =
        try {
            filesDir?.let { dir ->
                File(dir, DIAG_REPORT).takeIf { it.exists() }?.readText()
            }
        } catch (_: Throwable) {
            null
        }

    private fun readAppLogTail(maxLines: Int = 80): String =
        try {
            filesDir?.let { dir ->
                File(dir, APP_LOG).takeIf { it.exists() }?.readLines()
                    ?.takeLast(maxLines)?.joinToString("\n")
            } ?: "(app log empty)"
        } catch (_: Throwable) {
            "(app log unreadable)"
        }

    /** Everything needed for a paste into a bug report, in one string. */
    fun buildCopyBlob(): String {
        val report = readDiagnosticsReport() ?: "(no diagnostics report captured yet)"
        return buildString {
            append(report)
            append("\n\n=== durable app log (fsync'd, tail) ===\n")
            append(readAppLogTail())
        }
    }
}
