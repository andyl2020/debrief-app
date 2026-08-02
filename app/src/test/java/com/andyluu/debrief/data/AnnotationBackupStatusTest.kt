package com.andyluu.debrief.data

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationBackupStatusTest {
    @Test
    fun noWarningIsShownWhenThereIsNothingUserAuthoredToProtect() {
        assertNull(
            AnnotationBackupStatus(
                protectedItemCount = 0,
                localCurrent = false,
                folderCurrent = false,
            ).warning
        )
    }

    @Test
    fun deletingTheLastMarkerStillWarnsUntilFolderCopiesCatchUp() {
        val warning = AnnotationBackupStatus(
            protectedItemCount = 0,
            localCurrent = true,
            folderCurrent = false,
            localRevision = 11,
            folderRevision = 10,
            requiresFolderSync = true,
        ).warning.orEmpty()

        assertTrue(warning.contains("recording-folder backup is out of date"))
    }

    @Test
    fun localAndFolderFailuresHaveDifferentActionableWarnings() {
        val localWarning = AnnotationBackupStatus(
            protectedItemCount = 2,
            localCurrent = false,
        ).warning.orEmpty()
        val folderWarning = AnnotationBackupStatus(
            protectedItemCount = 2,
            localCurrent = true,
            folderCurrent = false,
        ).warning.orEmpty()

        assertTrue(localWarning.contains("encrypted database"))
        assertTrue(localWarning.contains("Retry backup"))
        assertTrue(folderWarning.contains("saved locally"))
        assertTrue(folderWarning.contains("Re-link"))
    }

    @Test
    fun currentRedundantCopiesStayQuiet() {
        assertNull(
            AnnotationBackupStatus(
                protectedItemCount = 8,
                localCurrent = true,
                folderCurrent = true,
                localRevision = 10,
                folderRevision = 10,
            ).warning
        )
    }
}
