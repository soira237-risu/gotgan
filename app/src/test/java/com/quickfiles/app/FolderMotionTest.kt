package com.quickfiles.app

import com.quickfiles.app.storage.StorageLocation
import com.quickfiles.app.ui.FolderMotion
import com.quickfiles.app.ui.folderMotion
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
}
