package com.quickfiles.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Environment
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.quickfiles.app.storage.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ExpandedMediaDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = AppGraph.get(compose.activity)
    private lateinit var folder: StorageLocation
    private lateinit var directory: File
    @Before fun prepare() {
        val path = InstrumentationRegistry.getArguments().getString("playerMediaDir")
        Assume.assumeTrue(path != null)
        val root = File(Environment.getExternalStorageDirectory(), "Download/QuickFilesTest").canonicalFile
        directory = File(Environment.getExternalStorageDirectory(), path!!).canonicalFile
        require(directory.toPath().startsWith(root.toPath()) && directory.name.startsWith("validation-player-"))
        folder = StorageLocation(LOCAL_SOURCE, path)
    }
    @After fun finish() { runBlocking { graph.playback.stop() } }
    private fun play(name: String) {
        val entries = runBlocking { graph.repository.local.list(folder) }
        runBlocking { graph.playback.play(entries.single { it.name == name }, entries) }
        compose.waitUntil(20000) { graph.playback.state.value.playing || graph.playback.state.value.error != null }
        assertNull("$name: ${graph.playback.state.value.error}", graph.playback.state.value.error)
    }
    @Test fun newMediaContainersActuallyPlayAndFlvIsNotSeekable() {
        createAmr(File(directory, "generated.amr"))
        for (name in listOf("video.3gpp", "video.flv", "video.mpg", "video.mpeg", "music.m4b", "music.oga", "generated.amr")) {
            play(name)
            if (name.endsWith("flv")) assertFalse(graph.playback.details.value.seekable)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "FORMAT_PLAY name=$name playing=true seekable=${graph.playback.details.value.seekable}\n") })
            runBlocking { graph.playback.stop() }
        }
    }
    @Test fun externalSubtitlesHandleUtf8VttCp949AndRejectOversize() {
        play("tracks.mkv")
        runBlocking(Dispatchers.Main) { graph.playback.controller!!.pause(); graph.playback.controller!!.seekTo(8000) }
        val entries = runBlocking { graph.repository.local.list(folder) }
        for ((name, legacy) in listOf("captions.srt" to false, "captions.vtt" to false, "korean.srt" to true)) {
            runBlocking(Dispatchers.Main) { graph.playback.attachSubtitle(entries.single { it.name == name }, legacy) }
            compose.waitUntil(20000) { graph.playback.details.value.tracks.groups.any { group -> group.type == androidx.media3.common.C.TRACK_TYPE_TEXT && (0 until group.length).any { group.isTrackSelected(it) && group.getTrackFormat(it).label == name } } }
            assertTrue(runBlocking(Dispatchers.Main) { graph.playback.controller!!.currentPosition in 7900..8100 })
        }
        val large = runCatching { runBlocking(Dispatchers.Main) { graph.playback.attachSubtitle(entries.single { it.name == "too-large.srt" }, false) } }.exceptionOrNull()
        assertNotNull(large); assertTrue(large!!.message.orEmpty().contains("4MiB"))
        val encoding = runCatching { runBlocking(Dispatchers.Main) { graph.playback.attachSubtitle(entries.single { it.name == "korean.srt" }, false) } }.exceptionOrNull()
        assertNotNull(encoding); assertTrue(encoding!!.message.orEmpty().contains("인코딩"))
        assertNull(graph.playback.state.value.error)
    }
    @Test fun unsupportedMpeg1PackIsReportedAsFormatFailure() {
        val entry = runBlocking { graph.repository.local.stat(folder.child("mpeg1-pack.mpg")) }
        runBlocking { graph.playback.play(entry, listOf(entry)) }
        compose.waitUntil(20000) { graph.playback.state.value.error != null }
        assertTrue(graph.playback.state.value.error.orEmpty().contains("파일 구조"))
    }
    private fun createAmr(target: File) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AMR_NB)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AMR_NB, 8000, 1).apply { setInteger(MediaFormat.KEY_BIT_RATE, 12200) }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            target.outputStream().use { out ->
                out.write("#!AMR\n".toByteArray())
                var frame = 0; var ended = false; var sentEnd = false
                val info = MediaCodec.BufferInfo(); val deadline = android.os.SystemClock.elapsedRealtime() + 30000
                while (!ended && android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (!sentEnd) {
                        val index = codec.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!; buffer.clear(); buffer.order(ByteOrder.LITTLE_ENDIAN)
                            if (frame == 400) { codec.queueInputBuffer(index, 0, 0, frame * 20000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM); sentEnd = true }
                            else { repeat(160) { sample -> buffer.putShort((kotlin.math.sin((frame * 160 + sample) * 2 * Math.PI * 440 / 8000) * 8000).toInt().toShort()) }; codec.queueInputBuffer(index, 0, 320, frame * 20000L, 0); frame++ }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10000)
                    if (index >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val buffer = codec.getOutputBuffer(index)!!; buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size); buffer.get(bytes); out.write(bytes)
                        }
                        ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0; codec.releaseOutputBuffer(index, false)
                    }
                }
                assertTrue("AMR encoder must finish", ended)
            }
        } finally { runCatching { codec.stop() }; codec.release() }
    }
}
