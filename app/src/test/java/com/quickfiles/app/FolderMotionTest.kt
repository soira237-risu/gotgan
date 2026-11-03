package com.quickfiles.app

import com.quickfiles.app.storage.StorageLocation
import com.quickfiles.app.ui.FolderMotion
import com.quickfiles.app.ui.folderMotion
import com.quickfiles.app.ui.folderOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderMotionTest {
    @Test fun ancestorAndDescendantUseOppositeDirectionsAcrossMultipleSegments() {
        val root = StorageLocation("phone")
        val folder = root.child("Download").child("photos")
        assertEquals(FolderMotion.FORWARD, folderMotion(root, folder))
        assertEquals(FolderMotion.BACKWARD, folderMotion(folder, root))
        assertEquals(FolderMotion.FORWARD, folderMotion(folder.parent(), folder))
        assertEquals(FolderMotion.BACKWARD, folderMotion(folder, folder.parent()))
    }
    @Test fun siblingsAndDifferentStorageNeverLookLikeHierarchicalNavigation() {
        val folder = StorageLocation("phone", "photos")
        assertEquals(FolderMotion.REPLACE, folderMotion(folder, StorageLocation("phone", "photos-old")))
        assertEquals(FolderMotion.REPLACE, folderMotion(folder, StorageLocation("phone", "videos/photos")))
        assertEquals(FolderMotion.REPLACE, folderMotion(folder, StorageLocation("nas", "photos/child")))
    }
    @Test fun homeAndUnchangedLocationKeepTheirMeaning() {
        val folder = StorageLocation("phone", "Download")
        assertEquals(FolderMotion.FORWARD, folderMotion(null, folder))
        assertEquals(FolderMotion.BACKWARD, folderMotion(folder, null))
        assertEquals(FolderMotion.NONE, folderMotion(folder, folder.copy()))
        assertEquals(FolderMotion.NONE, folderMotion(null, null))
    }
    @Test fun anInterruptedEntryLeavesFromWhereItIsInsteadOfJumpingSides() {
        // Halfway into a child folder, the user goes straight back up.
        val arriving = folderOffset(FolderMotion.FORWARD, .5f, FolderMotion.FORWARD, 0f)
        assertEquals(.5f, arriving, 1e-6f)
        assertEquals(arriving, folderOffset(FolderMotion.FORWARD, .5f, FolderMotion.BACKWARD, 0f), 1e-6f)
        // While leaving it keeps moving the way of the newer change.
        assertEquals(1f, folderOffset(FolderMotion.FORWARD, .5f, FolderMotion.BACKWARD, .5f), 1e-6f)
        // A sibling replacement scales in place and adds no travel of its own.
        assertEquals(arriving, folderOffset(FolderMotion.FORWARD, .5f, FolderMotion.REPLACE, .7f), 1e-6f)
        assertEquals(0f, folderOffset(FolderMotion.REPLACE, .2f, FolderMotion.REPLACE, .9f), 1e-6f)
        assertEquals(0f, folderOffset(FolderMotion.BACKWARD, 1f, FolderMotion.NONE, 0f), 1e-6f)
    }
}
