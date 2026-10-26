package com.quickfiles.app.storage

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.*

/** One shared budget across metadata, headers and body, so nested readers never multiply retries. */
internal class ReadRetryBudget : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ReadRetryBudget>
    private val used = AtomicInteger()
    fun nextDelay(): Long? = when (used.getAndIncrement()) { 0 -> 1000; 1 -> 3000; else -> null }
}
suspend fun <T> retryRead(block: suspend () -> T): T {
    if (currentCoroutineContext()[ReadRetryBudget] != null) return block()
    val budget = ReadRetryBudget()
    return withContext(budget) {
        while (true) {
            try { return@withContext block() }
            catch (e: StorageException) {
                ensureActive()
                if (e.kind != StorageError.NETWORK) throw e
                val pause = budget.nextDelay() ?: throw e
                com.quickfiles.app.operations.DiagnosticLog.record("READ", "body-retry", e)
                delay(pause)
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
}
