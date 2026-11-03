package com.quickfiles.app.viewer

import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.svg.SvgDecoder
import com.quickfiles.app.storage.SvgSniff
import okio.Buffer

/** Routes SVG bytes to Coil's SVG decoder using [SvgSniff], ahead of the bitmap decoders that cannot read them. */
class SniffingSvgDecoderFactory : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
        if (result.mimeType != "image/svg+xml") {
            val peek = result.source.source().peek()
            val head = Buffer()
            while (head.size < SvgSniff.LIMIT && peek.read(head, SvgSniff.LIMIT - head.size) != -1L) Unit
            if (!SvgSniff.looksLikeSvg(head.readByteArray())) return null
        }
        return SvgDecoder(result.source, options)
    }
}
