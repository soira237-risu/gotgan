package com.quickfiles.app

import com.quickfiles.app.playback.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
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
    @Test fun timelineShowsPreviewThenSliderDragThenPlaybackClock() {
        val preview = SeekPreview("m", "s", 20_000, 34_000)
        assertEquals(34_000, timelinePosition(preview, 50_000f, 20_000))
        assertEquals(50_000, timelinePosition(null, 50_000f, 20_000))
        assertEquals(20_000, timelinePosition(null, null, 20_000))
    }
    @Test fun committedSeekReplacesTheStaleSampleInTheSameSnapshot() {
        val clock = PlaybackClock(20_000)
        var preview by mutableStateOf<SeekPreview?>(SeekPreview("m", "s", 20_000, 34_000))
        fun shown() = timelinePosition(preview, null, clock.position)
        val before = Snapshot.takeSnapshot()
        // What PlayerScreen.commitSeek does: drop the preview and move the clock together.
        Snapshot.withMutableSnapshot { preview = null; clock.committed(34_000) }
        val after = Snapshot.takeSnapshot()
        try {
            assertEquals(34_000, before.enter { shown() })
            // The first frame without the preview already shows the target, not the 20 s origin.
            assertEquals(34_000, after.enter { shown() })
        } finally { before.dispose(); after.dispose() }
        clock.sample(34_250)
        assertEquals(34_250, shown())
    }
    @Test fun clockNeverShowsNegativePositions() {
        assertEquals(0, PlaybackClock(-5).position)
        val clock = PlaybackClock(1_000)
        clock.sample(-1); assertEquals(0, clock.position)
        clock.committed(-1); assertEquals(0, clock.position)
    }
}

