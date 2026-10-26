package com.quickfiles.app.playback

/** The kind of sleep timer currently owned by the playback session. */
internal enum class SleepTimerKind {
    OFF,
    DURATION,
    END_OF_ITEM,
}

/**
 * Immutable timer state that can be copied into service/UI state without exposing the
 * timer's mutation methods.
 *
 * [deadlineElapsedMs] is based on the injected monotonic clock and is only populated for
 * [SleepTimerKind.DURATION]. [targetMediaId] is only populated for
 * [SleepTimerKind.END_OF_ITEM].
 */
internal data class SleepTimerSnapshot(
    val kind: SleepTimerKind = SleepTimerKind.OFF,
    val deadlineElapsedMs: Long? = null,
    val targetMediaId: String? = null,
)

/**
 * Small, Android-free sleep-timer state machine for a playback service.
 *
 * The clock is supplied by the owner so production can pass elapsedRealtime and unit tests
 * can advance a deterministic monotonic value. Duration timers continue to count while
 * playback is paused or buffering. A timer expiry is consumed by the first call that reports
 * it; subsequent calls return false until another timer is started.
 */
internal class SleepTimerController(private val now: () -> Long) {
    companion object {
        const val MINUTES_MIN = 1
        const val MINUTES_MAX = 720
        const val MILLIS_PER_MINUTE = 60_000L
    }

    var snapshot: SleepTimerSnapshot = SleepTimerSnapshot()
        private set

    /** Starts or replaces the duration timer. Returns the new immutable state. */
    fun startDuration(minutes: Int): SleepTimerSnapshot {
        require(minutes in MINUTES_MIN..MINUTES_MAX) {
            "취침 타이머는 ${MINUTES_MIN}~${MINUTES_MAX}분 사이로 설정해 주세요."
        }
        val deadline = Math.addExact(now(), Math.multiplyExact(minutes.toLong(), MILLIS_PER_MINUTE))
        snapshot = SleepTimerSnapshot(
            kind = SleepTimerKind.DURATION,
            deadlineElapsedMs = deadline,
        )
        return snapshot
    }

    /** Starts or replaces an end-of-current-item timer for [mediaId]. */
    fun startEndOfItem(mediaId: String): SleepTimerSnapshot {
        require(mediaId.isNotBlank()) { "파일 식별자가 비어 있습니다." }
        snapshot = SleepTimerSnapshot(
            kind = SleepTimerKind.END_OF_ITEM,
            targetMediaId = mediaId,
        )
        return snapshot
    }

    /** Turns the timer off and returns the resulting state. */
    fun cancel(): SleepTimerSnapshot {
        snapshot = SleepTimerSnapshot()
        return snapshot
    }

    /**
     * Returns duration remaining in milliseconds, clamped at zero when the deadline is due.
     * End-of-item and off timers have no numeric countdown and return null.
     */
    fun remaining(nowElapsedMs: Long = now()): Long? = when (snapshot.kind) {
        SleepTimerKind.DURATION -> (snapshot.deadlineElapsedMs!! - nowElapsedMs).coerceAtLeast(0L)
        SleepTimerKind.OFF, SleepTimerKind.END_OF_ITEM -> null
    }

    /**
     * Consumes a duration expiry, if due. The state is switched to OFF before returning true,
     * so repeated service ticks cannot deliver duplicate expiry actions.
     */
    fun poll(nowElapsedMs: Long = now()): Boolean {
        if (snapshot.kind != SleepTimerKind.DURATION) return false
        val deadline = snapshot.deadlineElapsedMs ?: return false
        if (nowElapsedMs < deadline) return false
        cancel()
        return true
    }

    /**
     * Consumes an end-of-item expiry for the matching item. A stale completion event for any
     * other item is ignored.
     */
    fun onItemEnded(mediaId: String): Boolean {
        if (snapshot.kind != SleepTimerKind.END_OF_ITEM || snapshot.targetMediaId != mediaId) return false
        cancel()
        return true
    }

    /**
     * Tracks playlist transitions. End-of-item timers belong to exactly one media item and are
     * cancelled as soon as the current item changes to another id. Duration timers are retained.
     * Returns true when this call cancelled an end-of-item timer.
     */
    fun onCurrentItemChanged(mediaId: String): Boolean {
        if (snapshot.kind != SleepTimerKind.END_OF_ITEM || snapshot.targetMediaId == mediaId) return false
        cancel()
        return true
    }
}
