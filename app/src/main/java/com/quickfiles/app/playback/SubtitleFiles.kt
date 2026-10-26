package com.quickfiles.app.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.quickfiles.app.AppGraph
import com.quickfiles.app.storage.*
import com.quickfiles.app.viewer.TextDocument
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.*

internal fun subtitleText(bytes: ByteArray, cp949: Boolean): String {
    val doc = TextDocument(bytes, true, Revision(), cp949)
    return doc.charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, doc.bom.size, bytes.size - doc.bom.size)).toString()
}

/** Only app-created, bounded UTF-8 subtitle copies are exposed to the media loader. */
internal object SubtitleFiles {
    const val LIMIT = 4 * 1024 * 1024
    fun directory(context: Context) = File(context.cacheDir, "player-subtitles")
    fun file(context: Context, id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}\\.(srt|vtt)")))
        return File(directory(context), id)
    }
    suspend fun prepare(context: Context, entry: FileEntry, cp949: Boolean): MediaItem.SubtitleConfiguration = withContext(Dispatchers.IO) {
        require(FileFormats.isSubtitle(entry.name)) { "SRT 또는 VTT 자막을 선택해 주세요." }
        val source = AppGraph.get(context).repository.provider(entry.location.sourceId)
        val revision = source.stat(entry.location).revision
        val bytes = source.openRead(entry.location, 0, revision).use { it.stream.readAtMost(LIMIT + 1) }
        require(bytes.size <= LIMIT) { "외부 자막은 4MiB 이하만 지원합니다." }
        if (!revision.matches(source.stat(entry.location).revision)) throw StorageException(StorageError.CONFLICT, "자막 파일이 변경되었습니다. 다시 선택해 주세요.")
        val text = try { subtitleText(bytes, cp949) } catch (e: java.nio.charset.CharacterCodingException) {
            throw StorageException(StorageError.UNSUPPORTED, "자막 인코딩을 읽지 못했습니다. CP949 설정을 바꿔 다시 선택해 주세요.", e)
        }
        val encoded = text.toByteArray(Charsets.UTF_8)
        require(encoded.size <= LIMIT) { "변환된 자막이 4MiB를 초과합니다." }
        val dir = directory(context).apply { mkdirs() }
        require((dir.listFiles()?.sumOf { it.length() } ?: 0) + encoded.size <= 32L * 1024 * 1024) { "외부 자막 임시 공간이 가득 찼습니다. 재생을 정지한 뒤 다시 열어 주세요." }
        val extension = FileFormats.extension(entry.name)
        val id = "${UUID.randomUUID()}.$extension"
        val target = file(context, id)
        try { target.writeBytes(encoded); ensureActive() } catch (e: Exception) { target.delete(); throw e }
        MediaItem.SubtitleConfiguration.Builder(StorageDataSource.subtitleUri(id))
            .setId(id).setLabel(entry.name).setMimeType(if (extension == "srt") MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
            .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT).build()
    }
    fun clear(context: Context) { directory(context).listFiles()?.filter { it.name.matches(Regex("[a-f0-9-]{36}\\.(srt|vtt)")) }?.forEach { it.delete() } }
}
