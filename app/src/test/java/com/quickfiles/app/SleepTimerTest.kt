package com.quickfiles.app

import com.quickfiles.app.playback.SleepTimerController
import com.quickfiles.app.playback.SleepTimerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    private class FakeClock(var value: Long = 1_000L) {
        fun now(): Long = value
    }

    @Test
    fun durationUsesMonotonicDeadlineAndExpiresOnce() {
        val clock = FakeClock()
        val timer = SleepTimerController(clock::now)

        val started = timer.startDuration(15)
        assertEquals(SleepTimerKind.DURATION, started.kind)
        assertEquals(901_000L, started.deadlineElapsedMs)
        assertEquals(900_000L, timer.remaining())

        clock.value = 900_999L
        assertEquals(1L, timer.remaining())
        assertFalse(timer.poll())

        // Due at the deadline, and consuming it changes the state to OFF.
        clock.value = 901_000L
        assertTrue(timer.poll())
        assertEquals(SleepTimerKind.OFF, timer.snapshot.kind)
        assertNull(timer.remaining())
        assertFalse("an expiry event must only be delivered once", timer.poll())
    }

    @Test
    fun durationKeepsCountingAcrossPauseAndWallClockChanges() {
        val clock = FakeClock(10_000L)
        val timer = SleepTimerController(clock::now)
        timer.startDuration(1)

        // A paused/buffering player does not affect elapsed time.
        clock.value = 40_000L
        assertEquals(30_000L, timer.remaining())
        // The caller supplies monotonic elapsed time; a wall-clock adjustment is irrelevant.
        assertFalse(timer.poll(69_999L))
        assertTrue(timer.poll(70_000L))
    }

    @Test
    fun endOfItemExpiresOnlyForTargetAndChangingItemCancelsIt() {
        val clock = FakeClock()
        val timer = SleepTimerController(clock::now)

        val started = timer.startEndOfItem("episode-a")
        assertEquals(SleepTimerKind.END_OF_ITEM, started.kind)
        assertEquals("episode-a", started.targetMediaId)
        assertNull(timer.remaining())
        assertFalse(timer.onItemEnded("episode-b"))
        assertEquals(SleepTimerKind.END_OF_ITEM, timer.snapshot.kind)

        assertTrue(timer.onItemEnded("episode-a"))
        assertEquals(SleepTimerKind.OFF, timer.snapshot.kind)
        assertFalse(timer.onItemEnded("episode-a"))

        timer.startEndOfItem("episode-a")
        assertFalse(timer.onCurrentItemChanged("episode-a"))
        assertTrue(timer.onCurrentItemChanged("episode-b"))
        assertEquals(SleepTimerKind.OFF, timer.snapshot.kind)
        assertFalse(timer.onItemEnded("episode-a"))
    }

    @Test
    fun newTimerReplacesPreviousAndCancelTurnsOffAnyMode() {
        val clock = FakeClock()
        val timer = SleepTimerController(clock::now)

        timer.startDuration(720)
        assertEquals(SleepTimerKind.END_OF_ITEM, timer.startEndOfItem("episode-a").kind)
        assertEquals(SleepTimerKind.OFF, timer.cancel().kind)
        assertNull(timer.snapshot.deadlineElapsedMs)
        assertNull(timer.snapshot.targetMediaId)
    }

    @Test
    fun durationRangeAndTargetAreValidated() {
        val timer = SleepTimerController { 0L }
        listOf(0, -1, 721).forEach { minutes ->
            try {
                timer.startDuration(minutes)
                throw AssertionError("expected invalid duration: $minutes")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
        listOf("", "  ").forEach { mediaId ->
            try {
                timer.startEndOfItem(mediaId)
                throw AssertionError("expected invalid media id")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }
}
