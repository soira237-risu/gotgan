package com.quickfiles.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.quickfiles.app.ui.SelectionStroke
import com.quickfiles.app.ui.StrokeStart
import com.quickfiles.app.ui.crossesCell
import com.quickfiles.app.ui.edgeScrollSpeed
import com.quickfiles.app.ui.strokeStart
import org.junit.Assert.*
import org.junit.Test

class SelectionStrokeTest {
    @Test fun fastMovementIncludesIntermediateCellsButNotAdjacentRows() {
        val from = Offset(0f, 25f)
        val to = Offset(400f, 25f)
        assertTrue(crossesCell(from, to, Rect(100f, 0f, 150f, 50f)))
        assertTrue(crossesCell(to, from, Rect(200f, 0f, 250f, 50f)))
        assertFalse(crossesCell(from, to, Rect(100f, 60f, 150f, 110f)))
        assertFalse(crossesCell(from, to, Rect(450f, 0f, 500f, 50f)))
    }
    @Test fun stationaryAndDiagonalTouchesUseActualRectangle() {
        val cell = Rect(100f, 100f, 200f, 200f)
        assertTrue(crossesCell(Offset(150f, 150f), Offset(150f, 150f), cell))
        assertFalse(crossesCell(Offset.Zero, Offset.Zero, cell))
        assertTrue(crossesCell(Offset.Zero, Offset(300f, 300f), cell))
        assertFalse(crossesCell(Offset.Zero, Offset(90f, 300f), cell))
    }
    @Test fun reversePassesAndAdditionalFingersNeverToggleTwice() {
        val add = SelectionStroke(true)
        assertEquals(setOf("a", "b"), add.visit(listOf("a", "b", "a")))
        assertEquals(setOf("c"), add.visit(listOf("b", "c", "a")))
        assertTrue(add.visit(listOf("c", "b", "a")).isEmpty())
        val remove = SelectionStroke(false)
        assertFalse(remove.selecting)
        assertEquals(setOf("a"), remove.visit(listOf("a")))
        assertTrue(remove.visit(listOf("a")).isEmpty())
    }
    @Test fun secondFingerPinchesInsteadOfPaintingWhereAPinchExists() {
        // Selecting, one finger on a tile, a second finger lands: a grid pinch, never a two-finger paint.
        assertEquals(StrokeStart.PINCH, strokeStart(2, true, 0f, 8f, selecting = true, canPinch = true))
        assertEquals(StrokeStart.PINCH, strokeStart(2, true, 0f, 8f, selecting = false, canPinch = true))
        // The list has no pinch, so several fingers keep painting while selecting and scrolling otherwise.
        assertEquals(StrokeStart.PAINT, strokeStart(2, true, 0f, 8f, selecting = true, canPinch = false))
        assertEquals(StrokeStart.SCROLL, strokeStart(2, true, 0f, 8f, selecting = false, canPinch = false))
    }
    @Test fun oneFingerWaitsWithinSlopThenScrollsOrPaints() {
        assertEquals(StrokeStart.WAIT, strokeStart(1, true, 7.9f, 8f, selecting = true, canPinch = true))
        assertEquals(StrokeStart.PAINT, strokeStart(1, true, 8.1f, 8f, selecting = true, canPinch = true))
        assertEquals(StrokeStart.SCROLL, strokeStart(1, true, 8.1f, 8f, selecting = false, canPinch = true))
        // Lifting before the long-press time is a tap, whatever else is down.
        assertEquals(StrokeStart.TAP, strokeStart(0, false, 0f, 8f, selecting = true, canPinch = true))
        assertEquals(StrokeStart.TAP, strokeStart(1, false, 0f, 8f, selecting = false, canPinch = false))
    }
    @Test fun edgeScrollEasesInAndIsFastestAtTheEdge() {
        assertEquals(0f, edgeScrollSpeed(500f, 1000f, 100f), 0f)
        assertEquals(0f, edgeScrollSpeed(100f, 1000f, 100f), 0f)
        assertEquals(-1f, edgeScrollSpeed(0f, 1000f, 100f), 1e-6f)
        assertEquals(1f, edgeScrollSpeed(1000f, 1000f, 100f), 1e-6f)
        assertEquals(.25f, edgeScrollSpeed(950f, 1000f, 100f), 1e-6f)
        assertEquals(-.25f, edgeScrollSpeed(50f, 1000f, 100f), 1e-6f)
        // A finger dragged past the edge does not go faster than the edge itself.
        assertEquals(1f, edgeScrollSpeed(1200f, 1000f, 100f), 1e-6f)
    }
}
