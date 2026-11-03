package com.quickfiles.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.quickfiles.app.playback.PlayerDismissFrame
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerDismissDeviceTest {
    @get:Rule val rule = createComposeRule()
    private var dismissed = 0
    private fun show(enabled: Boolean = true) = rule.setContent {
        Box(Modifier.size(400.dp, 800.dp)) { PlayerDismissFrame(enabled, { dismissed++ }) { } }
    }
    private fun swipe(fromY: Float, toY: Float, dx: Float = 0f, millis: Long) {
        rule.onNodeWithTag("player-dismiss-frame").performTouchInput {
            swipe(Offset(centerX, height * fromY), Offset(centerX + width * dx, height * toY), millis)
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }
    @Test fun slowSwipePastAQuarterDismisses() { show(); swipe(.1f, .7f, millis = 600); assertEquals(1, dismissed) }
    @Test fun shortSlowSwipeSpringsBack() { show(); swipe(.1f, .2f, millis = 800); assertEquals(0, dismissed) }
    @Test fun quickFlickDismissesFromAShortDistance() { show(); swipe(.1f, .25f, millis = 40); assertEquals(1, dismissed) }
    @Test fun horizontalSwipeIsLeftForSeeking() { show(); swipe(.1f, .15f, dx = .75f, millis = 300); assertEquals(0, dismissed) }
    @Test fun upwardSwipeDoesNothing() { show(); swipe(.9f, .1f, millis = 300); assertEquals(0, dismissed) }
    @Test fun disabledFrameIgnoresSwipes() { show(enabled = false); swipe(.1f, .7f, millis = 100); assertEquals(0, dismissed) }
}
