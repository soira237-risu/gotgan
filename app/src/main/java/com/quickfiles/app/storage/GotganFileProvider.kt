package com.quickfiles.app.storage

import android.net.Uri
import androidx.core.content.FileProvider

/**
 * Remote files are shared from cache copies named `*.full`, so the stock provider would report them as
 * application/octet-stream. The URI's display name carries the real file name; derive the type from that.
 */
class GotganFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? {
        val name = uri.getQueryParameter(DISPLAY_NAME)
        if (name != null) MimeTypes.forName(name).takeIf { it != MimeTypes.ANY }?.let { return it }
        return super.getType(uri)
    }

    private companion object { const val DISPLAY_NAME = "displayName" }
}
