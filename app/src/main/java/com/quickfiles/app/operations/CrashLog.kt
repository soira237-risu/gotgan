package com.quickfiles.app.operations

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Crash reports kept on the device only: one text file per uncaught exception in [dir], never uploaded.
 * The user reads, shares or deletes them from Settings. Everything here is plain JVM so host tests cover it.
 */
class CrashLog(private val dir: File, private val header: () -> List<String> = { emptyList() }, private val now: () -> Long = System::currentTimeMillis) {
    data class Report(val name: String, val time: Long, val summary: String, val size: Long)

    /** Records each uncaught exception, then hands it to the previous handler so the platform still ends the process. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // A failing write must never replace the original crash.
            runCatching { record(thread.name, error) }
            if (previous != null) previous.uncaughtException(thread, error)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }

    /** Writes one report and returns its file name. Older reports beyond [KEEP] are removed. */
    @Synchronized
    fun record(threadName: String, error: Throwable): String {
        dir.mkdirs()
        val time = now()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("곳간 오류 기록")
            appendLine("시각: ${stamp(time, "yyyy-MM-dd HH:mm:ss ZZZZZ")}")
            appendLine("스레드: ${threadName.take(80)}")
            runCatching(header).getOrDefault(emptyList()).forEach { appendLine(it) }
            appendLine()
            append(redact(trace))
        }.let { if (it.length > MAX_CHARS) it.take(MAX_CHARS) + "\n… (${it.length - MAX_CHARS}자 생략)\n" else it }
        var name = "crash-${stamp(time, "yyyyMMdd-HHmmss-SSS")}.txt"
        var n = 1
        while (File(dir, name).exists()) name = "crash-${stamp(time, "yyyyMMdd-HHmmss-SSS")}-${n++}.txt"
        // Write then rename, so a half-written report never shows up in the list.
        val partial = File(dir, "$name.partial")
        partial.writeText(text)
        check(partial.renameTo(File(dir, name))) { "crash report rename failed" }
        reports().drop(KEEP).forEach { File(dir, it.name).delete() }
        return name
    }

    /** Newest first. */
    fun reports(): List<Report> = (dir.listFiles { file -> file.isFile && NAME.matches(file.name) } ?: emptyArray())
        .map { Report(it.name, it.lastModified(), summary(it), it.length()) }
        .sortedWith(compareByDescending<Report> { it.name.substring(6, 25) }.thenByDescending { it.name.length }.thenByDescending { it.name })

    fun read(name: String): String = file(name).readText()

    fun delete(name: String): Boolean = file(name).delete()

    fun clear() { reports().forEach { File(dir, it.name).delete() } }

    private fun file(name: String): File {
        // Only names this class wrote; never a path from elsewhere.
        require(NAME.matches(name)) { "not a crash report: $name" }
        return File(dir, name)
    }

    /** First line of the stack trace (exception class and message), cut to one short line. */
    private fun summary(file: File): String = runCatching {
        file.bufferedReader().useLines { lines -> lines.dropWhile { it.isNotBlank() }.drop(1).firstOrNull { it.isNotBlank() } }
    }.getOrNull()?.trim()?.take(160) ?: "내용을 읽을 수 없음"

    private fun stamp(time: Long, pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(time))

    companion object {
        const val KEEP = 20
        const val MAX_CHARS = 64 * 1024
        private val NAME = Regex("""crash-\d{8}-\d{6}-\d{3}(-\d+)?\.txt""")
        private val USER_INFO = Regex("""([a-zA-Z][a-zA-Z0-9+.-]*://)[^/\s@]+@""")
        private val SECRET_QUERY = Regex("""(?i)([?&](?:password|passwd|pwd|token|access_token|key|secret|auth)=)[^&\s]*""")

        /** Exception messages can quote URLs; credentials in them never reach the file. */
        fun redact(text: String): String = text.replace(USER_INFO, "$1***@").replace(SECRET_QUERY, "$1***")
    }
}
