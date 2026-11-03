package com.quickfiles.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.quickfiles.app.ui.imagePageTurn
import com.quickfiles.app.ui.imagePanBounds
import com.quickfiles.app.ui.shouldDismissImage
import org.junit.Assert.*
import org.junit.Test

class ImageGestureTest {
    @Test fun fittedAndLetterboxedImagesCannotPanIntoEmptySpace() {
        assertEquals(Offset.Zero, imagePanBounds(Size(1000f, 2000f), Size(2000f, 1000f), 1f))
        assertEquals(Offset(750f, 0f), imagePanBounds(Size(1000f, 2000f), Size(2000f, 1000f), 2.5f))
        assertEquals(Offset.Zero, imagePanBounds(Size.Zero, Size(100f, 100f), 4f))
    }
    @Test fun dismissalNeedsDownwardIntentAndEnoughDistanceOrVelocity() {
        assertFalse(shouldDismissImage(0f, 10f, 3000f, 1f))
        assertFalse(shouldDismissImage(150f, 125f, 3000f, 1f))
        assertFalse(shouldDismissImage(0f, -140f, -3000f, 1f))
        assertFalse(shouldDismissImage(0f, 50f, 800f, 1f))
        assertTrue(shouldDismissImage(10f, 130f, 0f, 1f))
        assertTrue(shouldDismissImage(5f, 35f, 1000f, 1f))
        assertFalse(shouldDismissImage(0f, 35f, 1000f, 3f))
        assertTrue(shouldDismissImage(15f, 105f, 3000f, 3f))
    }
    @Test fun pageTurnsNeedASidewaysStrokeAndAFlickCanBeShort() {
        // Long sideways strokes turn as before: toward the start opens the next image.
        assertEquals(1, imagePageTurn(-80f, 10f, 0f, 1f))
        assertEquals(-1, imagePageTurn(80f, 10f, 0f, 1f))
        // Short strokes stay unless flung the same way.
        assertEquals(0, imagePageTurn(-40f, 0f, -300f, 1f))
        assertEquals(1, imagePageTurn(-40f, 0f, -1200f, 1f))
        assertEquals(0, imagePageTurn(-40f, 0f, 1200f, 1f))
        assertEquals(0, imagePageTurn(-20f, 0f, -3000f, 1f))
        // Diagonal strokes never turn, and thresholds scale with density.
        assertEquals(0, imagePageTurn(-100f, 80f, -3000f, 1f))
        assertEquals(0, imagePageTurn(-80f, 0f, 0f, 3f))
        assertEquals(-1, imagePageTurn(230f, 0f, 0f, 3f))
    }
}
