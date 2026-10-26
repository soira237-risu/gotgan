package com.quickfiles.app.ui

import com.quickfiles.app.ui.icons.Symbols
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.takeOrElse
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.quickfiles.app.storage.LOCAL_SOURCE
import com.quickfiles.app.ui.theme.LocalBackdrop
import com.quickfiles.app.ui.theme.MediaGlass
import com.quickfiles.app.ui.theme.backdropBlur
import com.quickfiles.app.ui.theme.backdropSource
import com.quickfiles.app.ui.theme.glass
import com.quickfiles.app.ui.theme.rememberBackdrop
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

private val ViewerButtonShape = RoundedCornerShape(8.dp)

/** A fully pulled image shrinks to 78% before it is let go. */
private const val DISMISS_SHRINK = .22f

internal fun imagePanBounds(viewport: Size, image: Size, zoom: Float): Offset {
    if (viewport.width <= 0 || viewport.height <= 0 || image.width <= 0 || image.height <= 0) return Offset.Zero
    val fit = minOf(viewport.width / image.width, viewport.height / image.height)
    return Offset(((image.width * fit * zoom - viewport.width) / 2).coerceAtLeast(0f),
        ((image.height * fit * zoom - viewport.height) / 2).coerceAtLeast(0f))
}

internal fun shouldDismissImage(dx: Float, dy: Float, velocityY: Float, density: Float): Boolean =
    dy > abs(dx) * 1.5f && (dy >= 120f * density || (dy >= 32f * density && velocityY >= 900f * density))

@Composable
internal fun AnimatedAssetImage(asset: Any, model: ExplorerViewModel, modifier: Modifier = Modifier,
    previous: (() -> Unit)? = null, next: (() -> Unit)? = null, onTap: (() -> Unit)? = null, onDismiss: ((Rect?) -> Unit)? = null,
    onDismissDrag: ((Float) -> Unit)? = null, onShown: (() -> Unit)? = null, sourceName: String? = null) {
    var zoom by remember(asset) { mutableFloatStateOf(1f) }
    var pan by remember(asset) { mutableStateOf(Offset.Zero) }
    // 0..1 while pulling a fitted image down to close: it shrinks and the viewer fades with it.
    var pull by remember(asset) { mutableFloatStateOf(0f) }
    var dimensions by remember(asset) { mutableStateOf(Size.Zero) }
    var error by remember(asset) { mutableStateOf(false) }
    var animation by remember(asset) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val tap by rememberUpdatedState(onTap)
    val dismiss by rememberUpdatedState(onDismiss)
    val dismissDrag by rememberUpdatedState(onDismissDrag)
    val previousPage by rememberUpdatedState(previous)
    val nextPage by rememberUpdatedState(next)
    fun transition(targetZoom: Float, targetPan: Offset, after: () -> Unit = {}) {
        animation?.cancel()
        val startZoom = zoom; val startPan = pan; val startPull = pull
        animation = scope.launch {
            animate(0f, 1f, animationSpec = MaterialMotion.enter()) { value, _ ->
                zoom = startZoom + (targetZoom - startZoom) * value
                pan = startPan + (targetPan - startPan) * value
                if (startPull > 0f) { pull = startPull * (1f - value); dismissDrag?.invoke(pull) }
            }
            after()
        }
    }
    // Where the image draws right now, in this surface's coordinates, so closing can fly on from there.
    fun shownBounds(viewport: Size): Rect? {
        if (dimensions == Size.Zero || viewport.width <= 0f) return null
        val fit = minOf(viewport.width / dimensions.width, viewport.height / dimensions.height)
        val size = Size(dimensions.width * fit, dimensions.height * fit)
        val center = Offset(viewport.width / 2, viewport.height / 2)
        val scale = zoom * (1f - DISMISS_SHRINK * pull)
        return Rect(center - Offset(size.width, size.height) * (scale / 2), Size(size.width * scale, size.height * scale)).translate(pan)
    }
    DisposableEffect(asset) { onDispose { animation?.cancel() } }
    Box(modifier.clipToBounds().testTag("image-gesture-surface")
        .semantics {
            contentDescription = "이미지 미리보기"
            stateDescription = when {
                error -> "이미지 표시 실패"
                dimensions == Size.Zero -> "이미지 불러오는 중"
                zoom > 1.01f -> "확대됨"
                else -> "화면 맞춤"
            }
            if (onTap != null) onClick("도구 표시 또는 숨기기") { tap?.invoke(); true }
            customActions = listOf(CustomAccessibilityAction("화면에 맞추기") { transition(1f, Offset.Zero); true }) +
                if (onDismiss != null) listOf(CustomAccessibilityAction("뷰어 닫기") { dismiss?.invoke(null); true }) else emptyList()
        }
        .pointerInput(asset) {
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false)
                animation?.cancel()
                val tracker = VelocityTracker().apply { addPosition(first.uptimeMillis, first.position) }
                var movement = Offset.Zero
                var dragging = false
                var multiTouch = false
                val startedAtFit = zoom <= 1.01f
                do {
                    val event = awaitPointerEvent()
                    event.changes.firstOrNull { it.id == first.id }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                    val delta = event.calculatePan()
                    movement += delta
                    if (event.changes.count { it.pressed } > 1) multiTouch = true
                    if (!dragging && (movement.getDistance() > viewConfiguration.touchSlop || multiTouch)) dragging = true
                    if (dragging) {
                        val viewport = Size(size.width.toFloat(), size.height.toFloat())
                        val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, 8f)
                        if (multiTouch || !startedAtFit || newZoom > 1.01f) {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val focus = event.calculateCentroid(useCurrent = false).takeOrElse { center }
                            val adjusted = (pan - (focus - center)) * (newZoom / zoom) + (focus - center) + delta
                            val bounds = imagePanBounds(viewport, dimensions, newZoom)
                            pan = Offset(adjusted.x.coerceIn(-bounds.x, bounds.x), adjusted.y.coerceIn(-bounds.y, bounds.y))
                            zoom = newZoom
                        } else if (dismiss != null && movement.y > abs(movement.x)) {
                            pan = Offset(0f, movement.y.coerceAtLeast(0f))
                            pull = (movement.y / (size.height * .5f)).coerceIn(0f, 1f)
                            dismissDrag?.invoke(pull)
                        }
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
                if (dragging && startedAtFit && !multiTouch) {
                    when {
                        dismiss != null && shouldDismissImage(movement.x, movement.y, tracker.calculateVelocity().y, density) ->
                            dismiss?.invoke(shownBounds(Size(size.width.toFloat(), size.height.toFloat())))
                        abs(movement.x) > 72.dp.toPx() && abs(movement.x) > abs(movement.y) * 1.5f -> {
                            transition(1f, Offset.Zero)
                            if (movement.x < 0) nextPage?.invoke() else previousPage?.invoke()
                        }
                        else -> transition(1f, Offset.Zero)
                    }
                }
            }
        }
        .pointerInput(asset) {
            detectTapGestures(onTap = { tap?.invoke() }, onDoubleTap = { point ->
                val target = if (zoom > 1.01f) 1f else 2.5f
                val bounds = imagePanBounds(Size(size.width.toFloat(), size.height.toFloat()), dimensions, target)
                val focus = point - Offset(size.width / 2f, size.height / 2f)
                val targetPan = if (target == 1f) Offset.Zero else (focus * (1f - target)).let {
                    Offset(it.x.coerceIn(-bounds.x, bounds.x), it.y.coerceIn(-bounds.y, bounds.y))
                }
                transition(target, targetPan)
            })
        }) {
        AsyncImage(asset, null, imageLoader = model.graph.imageLoader,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom * (1f - DISMISS_SHRINK * pull); scaleY = scaleX; translationX = pan.x; translationY = pan.y
            }, contentScale = ContentScale.Fit,
            onSuccess = { dimensions = Size(it.result.image.width.toFloat(), it.result.image.height.toFloat()); error = false; onShown?.invoke() },
            onError = { error = true; onShown?.invoke() })
        if (dimensions == Size.Zero && !error) CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (error) Surface(Modifier.align(Alignment.Center).padding(24.dp)) { Text(if (sourceName?.endsWith(".avif", ignoreCase = true) == true && android.os.Build.VERSION.SDK_INT < 34)
            "이 기기에서 AVIF 이미지를 표시하지 못했습니다. Android 14 미만에서는 기기 디코더 지원이 필요합니다."
            else "이미지를 표시하지 못했습니다. 파일이 손상되었거나 지원하지 않는 형식일 수 있습니다.",
            Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
private fun ImageSystemBars(immersive: Boolean) {
    val activity = LocalContext.current.activity()
    val view = LocalView.current
    DisposableEffect(activity, view) {
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        val originalBehavior = controller?.systemBarsBehavior
        val originalInsets = ViewCompat.getRootWindowInsets(view)
        onDispose {
            if (originalBehavior != null) controller?.systemBarsBehavior = originalBehavior
            for (type in listOf(WindowInsetsCompat.Type.statusBars(), WindowInsetsCompat.Type.navigationBars())) {
                if (originalInsets?.isVisible(type) != false) controller?.show(type) else controller?.hide(type)
            }
        }
    }
    LaunchedEffect(activity, view, immersive) {
        activity?.let {
            val controller = WindowCompat.getInsetsController(it.window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (immersive) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
internal fun ViewerTrashAction(preview: PreviewState, model: ExplorerViewModel, overMedia: Boolean = false) {
    val state by model.state.collectAsStateWithLifecycle()
    var confirm by remember(preview.entry.id) { mutableStateOf(false) }
    var submitting by remember(preview.entry.id) { mutableStateOf(false) }
    val canTrash = state.writable && preview.entry.accessError == null &&
        (preview.entry.location.sourceId == LOCAL_SOURCE || preview.entry.revision.strongEtag != null)
    if (overMedia) GlassIconButton(Symbols.Delete, "휴지통으로 이동", { confirm = true }, Modifier.testTag("viewer-trash"),
        enabled = canTrash && !submitting, colors = MediaGlass, tint = Color.White)
    else ToolIcon(Symbols.Delete, "휴지통으로 이동", { confirm = true }, enabled = canTrash && !submitting, modifier = Modifier.testTag("viewer-trash"))
    if (confirm) AlertDialog(onDismissRequest = { if (!submitting) confirm = false }, title = { Text("휴지통으로 이동") },
        text = { Text("‘${preview.entry.name}’을 휴지통으로 이동할까요? 휴지통에서 복원할 수 있습니다.") },
        confirmButton = { TextButton(shape = ViewerButtonShape, enabled = !submitting, onClick = {
            submitting = true
            model.enqueue("TRASH", listOf(preview.entry.location), destination = null,
                onQueued = { confirm = false; model.closePreview() }, onFinished = { submitting = false }, preserveSelection = true)
        }, modifier = Modifier.testTag("confirm-viewer-trash")) { Text("휴지통으로 이동") } },
        dismissButton = { TextButton(shape = ViewerButtonShape, enabled = !submitting, onClick = { confirm = false }) { Text("취소") } })
}

/** Nearby images in the folder's visible order; the current one is wider and outlined. */
@Composable
private fun ThumbnailStrip(preview: PreviewState, model: ExplorerViewModel, modifier: Modifier = Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    val images = remember(state.entries, state.query, state.searchResults, state.kind) { model.visibleEntries().filter { it.isImage } }
    val index = images.indexOfFirst { it.id == preview.entry.id }
    if (index < 0) return
    val window = images.subList((index - 4).coerceAtLeast(0), (index + 5).coerceAtMost(images.size))
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${index + 1} / ${images.size}", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .85f))
        Row(Modifier.height(56.dp).glass(MediaGlass, MaterialTheme.shapes.medium, behind = Modifier.backdropBlur()).padding(6.dp)
            .semantics { contentDescription = "사진 ${index + 1} / ${images.size}" }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            window.forEach { entry ->
                val current = entry.id == preview.entry.id
                Box(Modifier.fillMaxHeight().width(if (current) 52.dp else 30.dp).clip(MaterialTheme.shapes.extraSmall)
                    .background(Color.White.copy(alpha = .12f))
                    .then(if (current) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall) else Modifier)
                    .clickable(enabled = !current, onClickLabel = entry.name) { model.open(entry) }) {
                    Thumbnail(entry, model, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImagePreviewScreen(preview: PreviewState, model: ExplorerViewModel, onClose: () -> Unit, onSwipedClosed: (Rect?) -> Unit,
    background: Color = Color.Black, onDismissDrag: (Float) -> Unit = {}, onShown: () -> Unit = {}) {
    var immersive by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(preview.error) { if (preview.error != null) { immersive = false; onShown() } }
    ImageSystemBars(immersive)
    val backdrop = rememberBackdrop(Color.Black)
    // The thumbnail strip appears only while moving between images, then fades away.
    var stripVisible by remember { mutableStateOf(false) }
    LaunchedEffect(preview.entry.id) {
        val previous = model.lastViewedImageId
        model.lastViewedImageId = preview.entry.id
        if (previous == null || previous == preview.entry.id) return@LaunchedEffect
        stripVisible = true
        kotlinx.coroutines.delay(1600)
        stripVisible = false
    }
    Box(Modifier.fillMaxSize().background(background).testTag("preview")) {
        val asset = preview.asset ?: preview.bitmap
        when {
            preview.loading -> Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                CircularProgressIndicator()
                Text("파일을 읽고 있어요…", color = Color.White)
            }
            preview.error != null -> Surface(Modifier.align(Alignment.Center).padding(24.dp)) { Column(Modifier.padding(16.dp)) {
                ErrorPanel(preview.error)
                TextButton(shape = ViewerButtonShape, onClick = { model.open(preview.entry) }) { Text("다시 시도") }
            } }
            asset != null -> AnimatedAssetImage(asset, model, Modifier.fillMaxSize().backdropSource(backdrop),
                previous = preview.previous?.let { entry -> { model.open(entry) } },
                next = preview.next?.let { entry -> { model.open(entry) } },
                onTap = { immersive = !immersive }, onDismiss = onSwipedClosed, onDismissDrag = onDismissDrag, onShown = onShown, sourceName = preview.entry.name)
        }
        // Tools float on neutral media glass over the black canvas; one tap hides them with the system bars.
        CompositionLocalProvider(LocalContentColor provides Color.White, LocalBackdrop provides backdrop) {
            AnimatedVisibility(stripVisible, enter = fadeIn(MaterialMotion.state()), exit = fadeOut(MaterialMotion.exit()), modifier = Modifier.align(Alignment.BottomCenter)) {
                ThumbnailStrip(preview, model, Modifier.navigationBarsPadding().padding(bottom = if (immersive) 32.dp else 96.dp))
            }
            AnimatedVisibility(!immersive, enter = fadeIn(MaterialMotion.state()), exit = fadeOut(MaterialMotion.exit()), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassIconButton(Symbols.ArrowBackIosNew, "닫기", onClose, Modifier.testTag("close-preview"), iconSize = 18.dp, colors = MediaGlass, tint = Color.White)
                    Box(Modifier.weight(1f).height(48.dp).glass(MediaGlass, behind = Modifier.backdropBlur()).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text(preview.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, color = Color.White)
                    }
                    ViewerTrashAction(preview, model, overMedia = true)
                }
            }
            AnimatedVisibility(!immersive, enter = fadeIn(MaterialMotion.state()), exit = fadeOut(MaterialMotion.exit()), modifier = Modifier.align(Alignment.BottomCenter)) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(64.dp).glass(MediaGlass, behind = Modifier.backdropBlur()).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BarItem(Symbols.ChevronLeft, "이전", { preview.previous?.let(model::open) }, enabled = preview.previous != null, tint = Color.White, pill = MediaGlass.pill)
                    Text("두 번 눌러 확대 · 아래로 쓸어 닫기", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .8f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    BarItem(Symbols.ChevronRight, "다음", { preview.next?.let(model::open) }, enabled = preview.next != null, tint = Color.White, pill = MediaGlass.pill)
                }
            }
        }
    }
}
