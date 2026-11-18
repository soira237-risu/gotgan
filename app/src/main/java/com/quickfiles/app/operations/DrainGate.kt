package com.quickfiles.app.operations

/**
 * Keeps one network job draining the queue. JobScheduler stops a running job when a job with the same ID is
 * scheduled again, and the stopped task would stay paused. While a job runs, a new request only asks it to look
 * at the queue once more before it finishes; work queued while it is finishing is never left behind.
 */
class DrainGate {
    private var active = false
    private var again = false

    /** Called after work was queued. True when the caller must schedule a job; false when the running job takes it. */
    @Synchronized fun request(): Boolean {
        if (!active) return true
        again = true
        return false
    }

    @Synchronized fun started() { active = true; again = false }

    /** After a drain: true to drain once more because work arrived meanwhile; false when the job may finish. */
    @Synchronized fun drainAgain(): Boolean {
        if (again) { again = false; return true }
        active = false
        return false
    }

    /** The job stopped or failed before [drainAgain] let it finish. True when a request arrived that no drain took, so a new job is needed. */
    @Synchronized fun ended(): Boolean {
        val missed = active && again
        active = false; again = false
        return missed
    }
}
