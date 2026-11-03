package com.quickfiles.app.viewer

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer

/** Largest page bitmap the service draws; the app refuses anything outside these bounds. */
internal const val PDF_MAX_WIDTH = 2048
internal const val PDF_MAX_HEIGHT = 4096

/** Untrusted PDF parsing has neither filesystem permissions nor network access. */
class PdfService : Service() {
    private var renderer: PdfRenderer? = null
    private val binder = object : IPdfService.Stub() {
        @Synchronized override fun open(input: ParcelFileDescriptor): Int {
            close(); renderer = PdfRenderer(input); return renderer!!.pageCount
        }
        @Synchronized override fun render(page: Int, width: Int, output: ParcelFileDescriptor) {
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                renderer!!.openPage(page).use { pdf ->
                    val w = width.coerceIn(256, PDF_MAX_WIDTH)
                    val h = (w.toDouble() * pdf.height / pdf.width).toInt().coerceIn(1, PDF_MAX_HEIGHT)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE); pdf.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        // Raw pixels behind a width/height header: encoding a PNG took longer than drawing the page.
                        val pixels = ByteBuffer.allocate(8 + w * h * 4).putInt(w).putInt(h)
                        bitmap.copyPixelsToBuffer(pixels)
                        pixels.flip()
                        while (pixels.hasRemaining()) stream.channel.write(pixels)
                    } finally { bitmap.recycle() }
                }
            }
        }
        @Synchronized override fun close() { renderer?.close(); renderer = null }
    }
    override fun onBind(intent: Intent) = binder
    override fun onDestroy() { binder.close(); super.onDestroy() }
}
