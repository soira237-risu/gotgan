package com.quickfiles.app.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SpaceGuardTest {
    @Test fun asksTheFileSystemRarelyWhileSpaceIsAmpleAndEveryBlockWhenLow() {
        var free = 10L * 1024 * 1024 * 1024; var asked = 0
        val guard = SpaceGuard(File(".")) { asked++; free }
        repeat(1024) { guard.take(128 * 1024); free -= 128 * 1024 }   // 128 MiB written
        assertTrue("asked $asked times", asked in 15..20)               // about once per 8 MiB, not 1024 times
        free = SpaceGuard.LOW / 2; asked = 0
        val low = SpaceGuard(File(".")) { asked++; free }
        repeat(10) { low.take(1024); free -= 1024 }
        assertEquals(11, asked)
    }
    @Test fun refusesTheBlockThatDoesNotFit() {
        var free = 300L * 1024
        val guard = SpaceGuard(File(".")) { free }
        guard.take(128 * 1024); free -= 128 * 1024
        guard.take(128 * 1024); free -= 128 * 1024
        val error = assertThrows(StorageException::class.java) { guard.take(128 * 1024) }
        assertEquals(StorageError.NO_SPACE, error.kind)
    }
}
