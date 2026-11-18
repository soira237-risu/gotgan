package com.quickfiles.app.operations

import org.junit.Assert.*
import org.junit.Test

class DrainGateTest {
    @Test fun aRunningJobTakesNewWorkInsteadOfBeingRescheduled() {
        val gate = DrainGate()
        assertTrue("nothing runs yet, so a job is scheduled", gate.request())
        gate.started()
        // A second task while the first drains: rescheduling the same job ID would stop the running job.
        assertFalse(gate.request())
        assertTrue("the job drains once more for the task queued meanwhile", gate.drainAgain())
        assertFalse(gate.drainAgain())
        assertTrue("after the job finished, the next task schedules a new job", gate.request())
    }

    @Test fun aStoppedJobHandsOnWorkThatArrivedWhileItRan() {
        val gate = DrainGate()
        gate.started()
        assertFalse(gate.request())
        assertTrue("the request no drain took needs a fresh job", gate.ended())
        assertTrue(gate.request())
        gate.started()
        assertFalse("a job stopped with nothing pending needs no replacement", gate.ended())
    }
}
