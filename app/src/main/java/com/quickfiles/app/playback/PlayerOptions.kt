package com.quickfiles.app.playback

import androidx.media3.common.*
import com.quickfiles.app.storage.FileEntry
import kotlinx.coroutines.flow.MutableStateFlow

internal fun seekTarget(position: Long, delta: Long, duration: Long): Long =
    (position + delta).coerceIn(0L, duration.coerceAtLeast(0L))

internal fun displayAspect(width: Int, height: Int, pixelRatio: Float = 1f, rotation: Int = 0): Float? {
    if (width <= 0 || height <= 0 || !pixelRatio.isFinite() || pixelRatio <= 0f) return null
    val ratio = width.toFloat() * pixelRatio / height
    return if (rotation % 180 != 0) 1f / ratio else ratio
}

internal fun clockText(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1000)
    return if (seconds >= 3600) "%d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
}

data class PlaybackDetails(
    val mediaId: String = "", val duration: Long = 0, val seekable: Boolean = false,
    val aspect: Float? = null, val speed: Float = 1f, val repeat: Int = Player.REPEAT_MODE_OFF,
    val muted: Boolean = false, val previous: Boolean = false, val next: Boolean = false,
    val buffering: Boolean = false, val audioAvailable: Boolean = false,
    val tracks: Tracks = Tracks.EMPTY,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun Player.details(): PlaybackDetails {
    val videoFormat = currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.firstNotNullOfOrNull { group ->
        (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it) }
    }
    val aspect = videoFormat?.let { displayAspect(it.width, it.height, it.pixelWidthHeightRatio, it.rotationDegrees) }
        ?: videoSize.let { displayAspect(it.width, it.height, it.pixelWidthHeightRatio) }
    return PlaybackDetails(
    currentMediaItem?.mediaId.orEmpty(), duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
    isCurrentMediaItemSeekable && isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && duration > 0,
    aspect,
    playbackParameters.speed, repeatMode, volume == 0f,
    hasPreviousMediaItem(), hasNextMediaItem(), playbackState == Player.STATE_BUFFERING,
    currentTracks.groups.any { group -> group.type == C.TRACK_TYPE_AUDIO && (0 until group.length).any(group::isTrackSupported) }, currentTracks,
)
}

/** Session-level presentation choices; track selections belong to each media item. */
class PlayerOptions {
    val fill = MutableStateFlow(false)
    internal val choices = mutableMapOf<String, ItemTrackChoice>()
    internal val subtitles = mutableMapOf<String, List<FileEntry>>()
    fun reset() { fill.value = false; choices.clear(); subtitles.clear() }
}

internal data class ItemTrackChoice(
    var audio: String? = null, var text: String? = null, var textDisabled: Boolean = false,
    var external: MediaItem.SubtitleConfiguration? = null, var externalEntry: FileEntry? = null,
    var cp949: Boolean = false,
)

internal fun trackKey(format: Format): String = listOf(format.id, format.language, format.label, format.sampleMimeType, format.codecs, format.channelCount).joinToString("|")

internal fun trackLabel(format: Format, index: Int): String =
    listOfNotNull(format.label, format.language?.takeUnless { it == "und" }, format.codecs ?: format.sampleMimeType?.substringAfter('/')).distinct().joinToString(" · ").ifBlank { "트랙 ${index + 1}" }
