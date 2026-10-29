package com.quickfiles.app

import com.quickfiles.app.playback.*
import org.junit.Assert.*
import org.junit.Test

class PlayerDismissTest {
    @Test fun gestureStaysUndecidedInsideTouchSlop() {
        assertNull(dismissDragStart(0f, 0f, 8f))
        assertNull(dismissDragStart(8f, -8f, 8f))
    }
    @Test fun onlyMostlyDownwardSwipesDismiss() {
        assertEquals(true, dismissDragStart(0f, 20f, 8f))
        assertEquals(true, dismissDragStart(10f, 16f, 8f))
        assertEquals(false, dismissDragStart(0f, -20f, 8f))
        assertEquals(false, dismissDragStart(20f, 10f, 8f))
        // The diagonal band between seeking (|dx| > 1.5|dy|) and dismissing belongs to neither.
        assertEquals(false, dismissDragStart(12f, 16f, 8f))
    }
    @Test fun distanceAloneDismissesPastAQuarterOfTheScreen() {
        assertFalse(shouldDismissPlayer(240f, 0f, 1000f, 3000f))
        assertTrue(shouldDismissPlayer(250f, 0f, 1000f, 3000f))
    }
    @Test fun flingDirectionOverridesDistance() {
        assertTrue(shouldDismissPlayer(40f, 3000f, 1000f, 3000f))
        assertFalse(shouldDismissPlayer(800f, -3000f, 1000f, 3000f))
        assertTrue(shouldDismissPlayer(800f, -2000f, 1000f, 3000f))
    }
    @Test fun invalidGeometryNeverDismisses() {
        assertFalse(shouldDismissPlayer(0f, 5000f, 1000f, 3000f))
        assertFalse(shouldDismissPlayer(500f, 0f, 0f, 3000f))
        assertFalse(shouldDismissPlayer(Float.NaN, 0f, 1000f, 3000f))
        assertTrue(shouldDismissPlayer(500f, Float.NaN, 1000f, 3000f))
    }
}
