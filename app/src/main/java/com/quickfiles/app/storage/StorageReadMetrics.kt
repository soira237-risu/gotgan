package com.quickfiles.app.storage

import androidx.annotation.Keep
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Process-local request counts for diagnostics. No paths, credentials or file contents are recorded. */
@Keep
object StorageReadMetrics {
    private val listings = ConcurrentHashMap<String, AtomicLong>()
    fun listed(source: String) { listings.getOrPut(source) { AtomicLong() }.incrementAndGet() }
    fun count(source: String): Long = listings[source]?.get() ?: 0
}
