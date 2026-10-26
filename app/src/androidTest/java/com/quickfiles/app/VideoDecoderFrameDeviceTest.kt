package com.quickfiles.app

import com.quickfiles.app.playback.PlaybackDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** New-build diagnostics are isolated so VideoFrameDeviceTest still runs on older APKs. */
class VideoDecoderFrameDeviceTest : VideoFrameDeviceTest() {
    override fun decoderSnapshot(): JSONObject? = runBlocking(Dispatchers.Main) {
        PlaybackDiagnostics.snapshot()?.let {
            JSONObject().put("generation", it.generation).put("rendered", it.rendered)
                .put("dropped", it.dropped).put("skipped", it.skipped)
                .put("decoderInitializations", it.initializations).put("decoderReleases", it.releases)
                .put("processingSamples", it.processingOffsetCount).put("processingOffsetUs", it.processingOffsetUs)
        }
    }
}
