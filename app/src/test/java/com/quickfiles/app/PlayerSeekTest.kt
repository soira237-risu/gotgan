package com.quickfiles.app

import com.quickfiles.app.playback.*
import org.junit.Assert.*
import org.junit.Test

class PlayerSeekTest {
    @Test fun horizontalDistanceIsRelativeToStartingTimeAndScreenWidth() {
        val drag = SeekDrag("movie", "session", 120_000, 3_600_000, 600f)
        assertEquals(150_000, drag.move(300f, false).target)
        assertEquals(140_000, drag.move(-100f, false).target)
    }
    @Test fun fineModeHasOneFifthSensitivityWithoutJumpingOnModeChanges() {
        val drag = SeekDrag("movie", "session", 30_000, 300_000, 600f)
        assertEquals(40_000, drag.move(100f, false).target)
        assertEquals(40_000, drag.move(0f, true).target)
        assertEquals(42_000, drag.move(100f, true).target)
        assertEquals(42_000, drag.move(0f, false).target)
        assertEquals(32_000, drag.move(-100f, false).target)
    }
    @Test fun boundsDoNotAccumulateInvisibleOvershootAndShortClipsUseTheirDuration() {
        val drag = SeekDrag("short", "s", 5_000, 10_000, 100f)
        assertEquals(10_000, drag.move(100f, false).target)
        assertEquals(9_000, drag.move(-10f, false).target)
        assertEquals(0, drag.move(-200f, false).target)
        assertEquals(1_000, drag.move(10f, false).target)
    }
    @Test fun fractionalMovesArePreservedAndInvalidGeometryDoesNotSeek() {
        val drag = SeekDrag("m", "s", 0, 60_000, 1000f)
        repeat(100) { drag.move(.01f, true) }
        assertEquals(12, drag.move(0f, true).target)
        assertEquals(0, SeekDrag("m", "s", 100, -1, 200f).move(50f, false).target)
        assertEquals(100, SeekDrag("m", "s", 100, 200, 0f).move(50f, false).target)
    }
}
