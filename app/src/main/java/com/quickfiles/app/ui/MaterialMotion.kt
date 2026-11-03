package com.quickfiles.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.quickfiles.app.storage.StorageLocation

/** M3 easing/duration tokens; Compose supplies the system duration scale, springs included.
 * https://github.com/material-components/material-components-android/blob/master/docs/theming/Motion.md
 * The pinned Compose Material3 1.4.0 exposes MotionScheme only internally.
 */
internal object MaterialMotion {
    private val standard = CubicBezierEasing(.2f, 0f, 0f, 1f)
    private val decelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    private val accelerate = CubicBezierEasing(.3f, 0f, 1f, 1f)
    private val emphasizedDecelerate = CubicBezierEasing(.05f, .7f, .1f, 1f)
    private val emphasizedAccelerate = CubicBezierEasing(.3f, 0f, .8f, .15f)
    fun <T> enter(): TweenSpec<T> = tween(200, easing = decelerate)
    fun <T> exit(): TweenSpec<T> = tween(150, easing = accelerate)
    fun <T> state(): TweenSpec<T> = tween(150, easing = standard)
    fun <T> settle(): TweenSpec<T> = tween(100, easing = standard)
    /** Whole-screen moves: folders, full panels. */
    fun <T> screenEnter(duration: Int = 400): TweenSpec<T> = tween(duration, easing = emphasizedDecelerate)
    fun <T> screenExit(duration: Int = 200): TweenSpec<T> = tween(duration, easing = emphasizedAccelerate)
    /** A thumbnail growing into the viewer and back. */
    fun <T> container(duration: Int = 450): TweenSpec<T> = tween(duration, easing = standard)
    // Navigation timings in one place. The first ones (folder 100+350ms, viewers 300/200ms, thumbnail flight 450/350ms)
    // read as rushed on a phone, so moving between folders and into or out of a viewer now takes its time.
    /** The folder that arrives, and the one that steps aside first. */
    fun <T> folderEnter(): TweenSpec<T> = screenEnter(500)
    fun <T> folderExit(): TweenSpec<T> = screenExit(200)
    /** A viewer or full panel coming in and leaving again. */
    fun <T> viewerEnter(): TweenSpec<T> = screenEnter(500)
    fun <T> viewerExit(): TweenSpec<T> = screenExit(300)
    /** The thumbnail flight into the image viewer and back into its tile. */
    fun <T> containerOpen(): TweenSpec<T> = container(600)
    fun <T> containerClose(): TweenSpec<T> = container(450)
    /** An image leaving sideways before the next one opens. */
    fun <T> pageTurn(): TweenSpec<T> = tween(250, easing = accelerate)
    /** Thumbnail cross-fade over the file-type icon (coil takes plain milliseconds). */
    const val THUMBNAIL_FADE_MS = 200
    /** Floating glass that changes size or position: sheets, dock, mini player. Carries release velocity. */
    fun <T> spatial(): SpringSpec<T> = spring(dampingRatio = .9f, stiffness = 700f)
    fun <T> spatialFast(): SpringSpec<T> = spring(dampingRatio = .9f, stiffness = 1400f)
    /** Opacity and colour; no overshoot. */
    fun <T> effects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
}

internal enum class FolderMotion(val direction: Int) { NONE(0), FORWARD(1), BACKWARD(-1), REPLACE(0) }

/**
 * Horizontal position of the drawn folder in units of the shared-axis shift. The arriving part keeps the motion the folder
 * came in with and the leaving part uses the newer change, so an interrupted entry never jumps to the other side.
 * A replacement scales in place instead of travelling.
 */
internal fun folderOffset(enterMotion: FolderMotion, enter: Float, leaveMotion: FolderMotion, leave: Float): Float {
    fun travel(motion: FolderMotion) = if (motion == FolderMotion.REPLACE) 0f else motion.direction.toFloat()
    return travel(enterMotion) * (1f - enter) - travel(leaveMotion) * leave
}

/** Compare whole path segments: a sibling such as 'photos-old' is not a child of 'photos'. */
internal fun folderMotion(from: StorageLocation?, to: StorageLocation?): FolderMotion = when {
    from == to -> FolderMotion.NONE
    from == null -> FolderMotion.FORWARD
    to == null -> FolderMotion.BACKWARD
    from.sourceId != to.sourceId -> FolderMotion.REPLACE
    from.path.isEmpty() || to.path.startsWith(from.path + "/") -> FolderMotion.FORWARD
    to.path.isEmpty() || from.path.startsWith(to.path + "/") -> FolderMotion.BACKWARD
    else -> FolderMotion.REPLACE
}
