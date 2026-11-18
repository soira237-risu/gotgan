package com.quickfiles.app

import com.quickfiles.app.playback.resumePosition
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackResumeTest {
    @Test fun finishedItemsReopenFromTheStart() {
        assertEquals(0L, resumePosition(180_000, 180_000, ended = true))
        assertEquals("a save just before the end counts as finished", 0L, resumePosition(176_000, 180_000, ended = false))
        assertEquals(0L, resumePosition(180_000, 180_000, ended = false))
    }

    @Test fun unfinishedItemsKeepTheirPlace() {
        assertEquals(60_000L, resumePosition(60_000, 180_000, ended = false))
        assertEquals(174_000L, resumePosition(174_000, 180_000, ended = false))
        // Duration is unknown (C.TIME_UNSET) while an item is still preparing.
        assertEquals(30_000L, resumePosition(30_000, Long.MIN_VALUE + 1, ended = false))
        assertEquals(0L, resumePosition(-1, 180_000, ended = false))
    }
}
