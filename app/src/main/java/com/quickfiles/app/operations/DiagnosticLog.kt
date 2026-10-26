package com.quickfiles.app.operations

import android.util.Log
import com.quickfiles.app.storage.StorageException

object DiagnosticLog {
    // Only fixed operation/stage codes and exception classes; never URLs, headers, messages or content.
    fun record(operation: String, stage: String, error: Throwable) {
        val kind = (error as? StorageException)?.kind?.name ?: error.javaClass.simpleName
        // Diagnostics are best effort, including when the storage core runs in a host JVM test.
        runCatching { Log.w("GotganIO", "operation=${operation.take(24)} stage=${stage.take(24)} kind=$kind cause=${error.cause?.javaClass?.simpleName ?: "none"}") }
    }
}
