package com.quickfiles.app.ui

import com.quickfiles.app.ui.icons.Symbols
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.quickfiles.app.ui.theme.GlassLevel
import com.quickfiles.app.ui.theme.LocalGlass
import com.quickfiles.app.ui.theme.glass
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Closes the enclosing sheet with its exit motion, then runs the action; outside a sheet it runs at once. */
internal val LocalSheetDismiss = staticCompositionLocalOf<(() -> Unit) -> Unit> { { action -> action() } }

/** Floating sheet that rises from below and always leaves the way it came; Compose applies the system animation duration scale. */
@Composable
internal fun WorkspaceSheet(title: String, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // 0 hidden below the screen, 1 resting; a drag adds its own offset on top.
    val reveal = remember { Animatable(0f) }
    var dragged by remember { mutableFloatStateOf(0f) }
    var travel by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var settle by remember { mutableStateOf<Job?>(null) }
    val dismissRequest by rememberUpdatedState(onDismissRequest)
    // Every way out (scrim, close button, back, drag, an action in the sheet) plays the same exit before the sheet is removed.
    fun close(velocity: Float = 0f, after: () -> Unit = { dismissRequest() }) {
        if (closing) return
        closing = true
        settle?.cancel()
        scope.launch {
            reveal.animateTo(0f, MaterialMotion.spatial(), initialVelocity = if (travel > 0f) -velocity / travel else 0f)
            after()
        }
    }
    Dialog({ close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.apply {
                setWindowAnimations(0)
                setDimAmount(0f)
            }
        }
        val density = LocalDensity.current
        LaunchedEffect(travel > 0f) { if (travel > 0f && !closing) reveal.animateTo(1f, MaterialMotion.spatial()) }
        val dismissDistance = with(density) { 56.dp.toPx() }
        val dismissVelocity = with(density) { 1200.dp.toPx() }
        // Below the 8dp inset plus room for the navigation bar, so the sheet starts fully out of view.
        val below = with(density) { 56.dp.toPx() }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = reveal.value.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = .32f))
                .pointerInput(Unit) { detectTapGestures { close() } })
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.BottomCenter) {
                // Floating thick glass sheet, inset 8dp from the screen edges.
                Box(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(8.dp)
                    .onSizeChanged { travel = it.height + below }
                    .graphicsLayer {
                        // Hidden until measured so the first frame never shows it resting before it rises.
                        alpha = if (travel > 0f) 1f else 0f
                        translationY = travel * (1f - reveal.value) + dragged
                    }
                    // Dialog windows cannot sample the screen behind them for blur, so the sheet stays nearly opaque.
                    .glass(LocalGlass.current.copy(thick = .97f), MaterialTheme.shapes.extraLarge, GlassLevel.THICK)
                    .testTag("workspace-sheet").semantics { paneTitle = title; dismiss { close(); true } }) {
                    CompositionLocalProvider(LocalSheetDismiss provides { action -> close(after = action) }) {
                    Column {
                        Column(Modifier.fillMaxWidth().testTag("workspace-sheet-header")
                            .draggable(rememberDraggableState { delta -> if (!closing) dragged = (dragged + delta).coerceAtLeast(0f) }, Orientation.Vertical,
                                onDragStarted = { settle?.cancel() }, onDragStopped = { velocity ->
                                    if (dragged >= dismissDistance || (dragged > 0 && velocity >= dismissVelocity)) close(velocity)
                                    else {
                                        settle?.cancel()
                                        settle = scope.launch {
                                            animate(dragged, 0f, initialVelocity = velocity, animationSpec = MaterialMotion.spatial()) { value, _ -> dragged = value }
                                        }
                                    }
                                })) {
                        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 4.dp)
                            .width(38.dp).height(5.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = .45f), RoundedCornerShape(50)))
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                            GlassIconButton(Symbols.Close, "$title 닫기", { close() }, size = 44.dp, iconSize = 20.dp)
                        }
                        }
                        content()
                    }
                    }
                }
            }
        }
    }
}
