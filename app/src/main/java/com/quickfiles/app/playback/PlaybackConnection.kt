package com.quickfiles.app.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.quickfiles.app.AppGraph
import com.quickfiles.app.data.SavedValue
import com.quickfiles.app.storage.FileEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PlaybackState(
    val title: String = "", val playing: Boolean = false, val playWhenReady: Boolean = false,
    val visible: Boolean = false, val error: String? = null, val retryable: Boolean = false,
    val video: Boolean = false,
)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackConnection(private val context: Context) {
    val state = MutableStateFlow(PlaybackState())
    val details = MutableStateFlow(PlaybackDetails())
    internal val sessionState = PlaybackSessionRuntime.state
    val options = PlayerOptions()
    private var applyingTracks = false
    private val lock = Mutex()
    var controller: MediaController? = null; private set
    suspend fun connect(): MediaController = withContext(Dispatchers.Main) { lock.withLock {
        controller ?: run {
            val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            val result = withContext(Dispatchers.IO) { future.get() }
            if (result.mediaItemCount == 0) withContext(Dispatchers.IO) { SubtitleFiles.clear(context) }
            result.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { updateState(player) }
                override fun onTracksChanged(tracks: Tracks) { restoreTracks(result) }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { restoreTracks(result) }
                override fun onPlayerError(error: PlaybackException) {
                    val causes = generateSequence(error as Throwable) { it.cause }.take(12).toList()
                    val network = causes.filterIsInstance<com.quickfiles.app.storage.StorageException>().any { it.kind == com.quickfiles.app.storage.StorageError.NETWORK }
                    state.value = state.value.copy(error = when (error.errorCode) {
                        in 3000..3999 -> "지원하지 않는 파일 구조이거나 손상된 미디어입니다."
                        in 4000..5999 -> "기기에서 이 코덱을 재생할 수 없습니다."
                        else -> "재생이 중단되었습니다. 연결과 파일을 확인해 주세요."
                    }, retryable = network)
                }
            })
            controller = result; updateState(result); result
        }
    } }
    suspend fun play(entry: FileEntry, siblings: List<FileEntry>) = withContext(Dispatchers.Main) {
        val player = connect()
        val sessionId = java.util.UUID.randomUUID().toString()
        check(send(
            player,
            PlaybackCommands.beginSession,
            Bundle().apply { putString(PlaybackCommands.ARG_SESSION_ID, sessionId) },
        )) { "새 재생 세션을 시작하지 못했습니다." }
        val list = siblings.filter { if (entry.isVideo) it.isVideo else it.isAudio }.ifEmpty { listOf(entry) }
        val start = list.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
        val subtitleFolders = siblings.filter { !it.isDirectory && com.quickfiles.app.storage.FileFormats.isSubtitle(it.name) }.groupBy { it.location.parent() }
        list.forEach { item -> options.subtitles[item.id] = subtitleFolders[item.location.parent()].orEmpty() }
        val retained = list.map { it.id }.toSet()
        options.subtitles.keys.retainAll(retained)
        val position = AppGraph.get(context).dao.saved("playback:${entry.id}")?.value?.toLongOrNull() ?: 0
        state.value = PlaybackState(entry.name, playWhenReady = true, visible = true, video = entry.isVideo)
        player.setMediaItems(list.map { MediaItem.Builder().setMediaId(it.id).setUri(StorageDataSource.mediaUri(it.location)).setMediaMetadata(MediaMetadata.Builder()
            .setTitle(it.name).setDisplayTitle(it.name).setIsPlayable(true).setIsBrowsable(false)
            .setMediaType(if (it.isVideo) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()).setSubtitleConfigurations(options.choices[it.id]?.external?.let(::listOf) ?: emptyList())
            .setCustomCacheKey(options.choices[it.id]?.external?.id).build() }, start, position)
        // Explicitly opening a new file starts with video available. Automatic playlist
        // transitions keep the background track policy selected by the player window.
        if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
        }
        player.prepare(); player.play()
    }
    fun toggle() { controller?.let { if (it.playWhenReady) it.pause() else it.play() } }
    fun seekBy(deltaMs: Long) {
        controller?.let { player ->
            val detail = details.value
            if (detail.seekable) player.seekTo(seekTarget(player.currentPosition, deltaMs, detail.duration))
        }
    }

    suspend fun setAudioOnly(enabled: Boolean): Boolean = send(PlaybackCommands.run { if (enabled) audioOnly else showVideo })
    suspend fun setSleepTimerMinutes(minutes: Int): Boolean =
        send(PlaybackCommands.timerMinutes, Bundle().apply { putInt(PlaybackCommands.ARG_MINUTES, minutes) })
    suspend fun setSleepTimerEndOfItem(): Boolean = send(PlaybackCommands.timerEndOfItem)
    suspend fun clearSleepTimer(): Boolean = send(PlaybackCommands.timerCancel)
    suspend fun retry(): Boolean = send(PlaybackCommands.retry)

    private suspend fun send(command: androidx.media3.session.SessionCommand, args: Bundle = Bundle.EMPTY): Boolean =
        send(connect(), command, args)

    private suspend fun send(player: MediaController, command: androidx.media3.session.SessionCommand, args: Bundle = Bundle.EMPTY): Boolean =
        withContext(Dispatchers.Main) {
            if (!player.isSessionCommandAvailable(command)) return@withContext false
            val future = player.sendCustomCommand(command, args)
            val result = withContext(Dispatchers.IO) { future.get() }
            result.resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS
        }

    private fun updateState(player: Player) {
        details.value = player.details()
        state.value = state.value.copy(title = player.mediaMetadata.title?.toString().orEmpty(),
            playing = player.isPlaying, playWhenReady = player.playWhenReady, visible = player.mediaItemCount > 0,
            video = player.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO,
            error = if (player.playerError == null) null else state.value.error,
            retryable = if (player.playerError == null) false else state.value.retryable)
    }
    fun selectTrack(type: Int, groupIndex: Int?, trackIndex: Int = 0) {
        val player = controller ?: return
        val id = player.currentMediaItem?.mediaId ?: return
        val choice = options.choices.getOrPut(id) { ItemTrackChoice() }
        val group = groupIndex?.let { player.currentTracks.groups.getOrNull(it) }
        if (group != null && (group.type != type || trackIndex !in 0 until group.length || !group.isTrackSupported(trackIndex))) return
        val key = group?.getTrackFormat(trackIndex)?.let(::trackKey)
        if (type == C.TRACK_TYPE_AUDIO) choice.audio = key
        else { choice.text = key; choice.textDisabled = group == null }
        restoreTracks(player)
    }
    private fun restoreTracks(player: Player) {
        if (applyingTracks) return
        val choice = options.choices[player.currentMediaItem?.mediaId]
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, choice?.textDisabled == true)
        for (type in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
            val selected = if (type == C.TRACK_TYPE_AUDIO) choice?.audio else choice?.text
            if (selected == null) continue
            for (group in player.currentTracks.groups.filter { it.type == type }) {
                val index = (0 until group.length).firstOrNull {
                    val format = group.getTrackFormat(it)
                    group.isTrackSupported(it) && (trackKey(format) == selected || (selected == "external" && format.label == choice?.external?.label))
                } ?: continue
                builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, index)); break
            }
        }
        val parameters = builder.build()
        if (parameters != player.trackSelectionParameters) {
            applyingTracks = true
            try { player.trackSelectionParameters = parameters } finally { applyingTracks = false }
        }
    }
    suspend fun attachSubtitle(entry: FileEntry, cp949: Boolean) = withContext(Dispatchers.Main) {
        val player = connect()
        val original = player.currentMediaItem ?: return@withContext
        require(entry in options.subtitles[original.mediaId].orEmpty()) { "현재 영상과 같은 폴더의 자막을 선택해 주세요." }
        val config = SubtitleFiles.prepare(context, entry, cp949)
        withContext(Dispatchers.Main) {
            if (player.currentMediaItem?.mediaId != original.mediaId) {
                withContext(Dispatchers.IO) { SubtitleFiles.file(context, config.uri.lastPathSegment!!).delete() }
                return@withContext
            }
            val choice = options.choices.getOrPut(original.mediaId) { ItemTrackChoice() }
            val previous = choice.external
            choice.external = config; choice.externalEntry = entry; choice.cp949 = cp949; choice.textDisabled = false; choice.text = "external"
            val index = player.currentMediaItemIndex
            val position = player.currentPosition
            // ProgressiveMediaSource 1.9.3 only compares URI/image duration/cache key in
            // canUpdateMediaItem. A new key forces creation of the added subtitle source.
            player.replaceMediaItem(index, original.buildUpon().setSubtitleConfigurations(listOf(config)).setCustomCacheKey(config.id).build())
            player.seekTo(index, position)
            restoreTracks(player)
            previous?.let { withContext(Dispatchers.IO) { SubtitleFiles.file(context, it.uri.lastPathSegment!!).delete() } }
        }
    }
    suspend fun stop(expectedSessionId: String? = null) = withContext(Dispatchers.Main) {
        if (expectedSessionId != null && sessionState.value.sessionId != expectedSessionId) return@withContext
        val player = controller
        val args = expectedSessionId?.let { id -> Bundle().apply { putString(PlaybackCommands.ARG_SESSION_ID, id) } } ?: Bundle.EMPTY
        if (player == null || !send(player, PlaybackCommands.stop, args)) {
            if (expectedSessionId == null || sessionState.value.sessionId == expectedSessionId) legacyStop(player)
        }
    }

    private suspend fun legacyStop(player: MediaController?) {
        val subtitleIds = options.choices.values.mapNotNull { it.external?.uri?.lastPathSegment }.toSet()
        player?.let {
            it.currentMediaItem?.let { item -> AppGraph.get(context).dao.save(SavedValue("playback:${item.mediaId}", it.currentPosition.toString())) }
            it.stop(); it.clearMediaItems(); it.setPlaybackSpeed(1f); it.repeatMode = Player.REPEAT_MODE_OFF; it.volume = 1f
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
        }
        onServiceStopped()
        withContext(Dispatchers.IO) {
            subtitleIds.forEach { id -> runCatching { SubtitleFiles.file(context, id).delete() } }
        }
    }

    internal fun onServiceStopped() {
        options.reset()
        details.value = PlaybackDetails()
        state.value = PlaybackState()
    }
}
