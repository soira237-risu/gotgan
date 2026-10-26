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
    /** Floating glass that changes size or position: sheets, dock, mini player. Carries release velocity. */
    fun <T> spatial(): SpringSpec<T> = spring(dampingRatio = .9f, stiffness = 700f)
    fun <T> spatialFast(): SpringSpec<T> = spring(dampingRatio = .9f, stiffness = 1400f)
    /** Opacity and colour; no overshoot. */
    fun <T> effects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
}

internal enum class FolderMotion(val direction: Int) { NONE(0), FORWARD(1), BACKWARD(-1), REPLACE(0) }

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
