package com.quickfiles.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.quickfiles.app.ui.SelectionStroke
import com.quickfiles.app.ui.crossesCell
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
}
