package com.quickfiles.app

import com.quickfiles.app.data.RecentFiles
import com.quickfiles.app.storage.FileEntry
import com.quickfiles.app.storage.LOCAL_SOURCE
import com.quickfiles.app.storage.MediaRecents
import com.quickfiles.app.storage.StorageLocation
import org.junit.Assert.*
import org.junit.Test

class RecentFilesTest {
    @Test fun openedEntriesRoundTrip() {
        val entry = FileEntry(StorageLocation("webdav-1", "사진/여행/a.jpg"), "a.jpg", false, 1234, 1_700_000_000_000, "\"etag\"")
        assertEquals(entry, RecentFiles.decode(RecentFiles.encode(entry)))
        val bare = FileEntry(StorageLocation(LOCAL_SOURCE, "a.txt"), "a.txt", false)
        assertEquals(bare, RecentFiles.decode(RecentFiles.encode(bare)))
        assertNull(RecentFiles.decode("{broken"))
    }

    @Test fun mediaRowsBecomePhoneEntriesAndProtectedOrHiddenRowsAreDropped() {
        val e = MediaRecents.toEntry("Download/", "보고서.pdf", 2048, 1_700_000_000)!!
        assertEquals(StorageLocation(LOCAL_SOURCE, "Download/보고서.pdf"), e.location)
        assertEquals(1_700_000_000_000L, e.modified); assertEquals(2048L, e.size)
        assertEquals("a.txt", MediaRecents.toEntry(null, "a.txt", 1, 1)!!.location.path)
        assertNull(MediaRecents.toEntry("Android/data/com.x/", "a.bin", 1, 1))
        assertNull(MediaRecents.toEntry("Android/obb/", "a.obb", 1, 1))
        assertNull(MediaRecents.toEntry("DCIM/.thumbnails/", "t.jpg", 1, 1))
        assertNull(MediaRecents.toEntry("Download/", ".nomedia", 0, 1))
        assertNull(MediaRecents.toEntry("Download/", null, 1, 1))
        assertNull(MediaRecents.toEntry("Download/../", "x", 1, 1))
        assertNotNull(MediaRecents.toEntry("Android/media/com.app/", "voice.m4a", 1, 1))
    }
}
