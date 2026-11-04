package com.quickfiles.app.operations

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CrashLogTest {
    private lateinit var dir: File
    private var clock = 1_700_000_000_000L
    private lateinit var log: CrashLog

    @Before fun setUp() {
        dir = Files.createTempDirectory("crash").toFile()
        log = CrashLog(File(dir, "crash"), header = { listOf("앱: test (1)") }, now = { clock })
    }

    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun recordsHeaderAndStackTraceAndListsSummary() {
        val name = log.record("main", IllegalStateException("폴더를 열 수 없음"))
        val text = log.read(name)
        assertTrue(text, text.startsWith("곳간 오류 기록\n"))
        assertTrue(text, "스레드: main" in text)
        assertTrue(text, "앱: test (1)" in text)
        assertTrue(text, "java.lang.IllegalStateException: 폴더를 열 수 없음" in text)
        assertTrue(text, "at com.quickfiles.app.operations.CrashLogTest" in text)
        val report = log.reports().single()
        assertEquals(name, report.name)
        assertEquals("java.lang.IllegalStateException: 폴더를 열 수 없음", report.summary)
        assertFalse(File(dir, "crash").list()!!.any { it.endsWith(".partial") })
    }

    @Test fun includesCauses() {
        val text = log.read(log.record("io", RuntimeException("outer", java.io.IOException("inner"))))
        assertTrue(text, "Caused by: java.io.IOException: inner" in text)
    }

    @Test fun newestFirstAndSameMillisecondDoesNotOverwrite() {
        val first = log.record("a", Error("one"))
        val second = log.record("a", Error("two"))
        clock += 1000
        val third = log.record("a", Error("three"))
        assertNotEquals(first, second)
        assertEquals(listOf(third, second, first), log.reports().map { it.name })
    }

    @Test fun keepsOnlyTheNewestReports() {
        val names = (1..CrashLog.KEEP + 5).map { clock += 1000; log.record("t", Error("e$it")) }
        val kept = log.reports().map { it.name }
        assertEquals(CrashLog.KEEP, kept.size)
        assertEquals(names.takeLast(CrashLog.KEEP).reversed(), kept)
    }

    @Test fun capsVeryLongReports() {
        val text = log.read(log.record("t", Error("x".repeat(CrashLog.MAX_CHARS * 2))))
        assertTrue(text.length < CrashLog.MAX_CHARS + 100)
        assertTrue(text.trimEnd().endsWith("생략)"))
    }

    @Test fun redactsCredentialsInMessages() {
        val text = log.read(log.record("t", IllegalArgumentException("GET https://dongjin:hunter2@nas.local/dav/a.jpg?token=abc&x=1 failed")))
        assertFalse(text, "hunter2" in text)
        assertFalse(text, "token=abc" in text)
        assertTrue(text, "https://***@nas.local/dav/a.jpg?token=***&x=1" in text)
    }

    @Test fun deleteAndClearOnlyTouchReports() {
        val a = log.record("t", Error("a")); clock += 1000
        log.record("t", Error("b"))
        val other = File(File(dir, "crash"), "keep.txt").apply { writeText("x") }
        assertTrue(log.delete(a))
        assertEquals(1, log.reports().size)
        log.clear()
        assertTrue(log.reports().isEmpty())
        assertTrue(other.exists())
    }

    @Test fun rejectsNamesItDidNotWrite() {
        File(dir, "secret.txt").writeText("x")
        assertThrows(IllegalArgumentException::class.java) { log.read("../secret.txt") }
        assertThrows(IllegalArgumentException::class.java) { log.delete("../secret.txt") }
        assertTrue(File(dir, "secret.txt").exists())
    }

    @Test fun emptyWhenNothingWasRecorded() {
        assertTrue(log.reports().isEmpty())
    }

    @Test fun installedHandlerRecordsThenDelegates() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var delegated: Throwable? = null
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> delegated = e }
            log.install()
            val boom = IllegalStateException("boom")
            val thread = Thread { throw boom }.apply { name = "worker-7" }
            thread.start(); thread.join()
            assertSame(boom, delegated)
            val report = log.reports().single()
            assertTrue(log.read(report.name).contains("스레드: worker-7"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test fun failingWriteStillDelegates() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var delegated: Throwable? = null
        try {
            val blocked = File(dir, "blocked").apply { writeText("not a directory") }
            Thread.setDefaultUncaughtExceptionHandler { _, e -> delegated = e }
            CrashLog(blocked).install()
            val boom = RuntimeException("boom")
            Thread { throw boom }.apply { start(); join() }
            assertSame(boom, delegated)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
