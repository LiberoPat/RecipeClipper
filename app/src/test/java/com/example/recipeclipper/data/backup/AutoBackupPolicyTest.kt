package com.example.recipeclipper.data.backup

import com.example.recipeclipper.data.backup.AutoBackupPolicy.KEEP
import com.example.recipeclipper.data.backup.AutoBackupPolicy.MIN_GAP_MS
import com.example.recipeclipper.data.backup.AutoBackupPolicy.NUDGE_AFTER_MS
import com.example.recipeclipper.data.backup.AutoBackupPolicy.REFRESH_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The automatic copy's rules (#150); iOS pins the same cases in AutoBackupPolicyTests. */
class AutoBackupPolicyTest {

    private val now = 1_800_000_000_000L
    private val copied = AutoBackupRecord(lastBackupAt = now, lastFingerprint = "a", lastFile = "copy.zip")

    @Test fun `a first copy is always due`() {
        assertTrue(AutoBackupPolicy.isDue(AutoBackupRecord(), "a", emptyList(), now))
    }

    @Test fun `an unchanged library isn't copied again within the week`() {
        assertFalse(AutoBackupPolicy.isDue(copied, "a", listOf("copy.zip"), now + REFRESH_MS - 1))
    }

    @Test fun `an unchanged library is copied again after a week, so the date stays true`() {
        assertTrue(AutoBackupPolicy.isDue(copied, "a", listOf("copy.zip"), now + REFRESH_MS))
    }

    @Test fun `a change waits for the gap since the last copy`() {
        assertFalse(AutoBackupPolicy.isDue(copied, "b", listOf("copy.zip"), now + MIN_GAP_MS - 1))
        assertTrue(AutoBackupPolicy.isDue(copied, "b", listOf("copy.zip"), now + MIN_GAP_MS))
    }

    @Test fun `a copy gone from the folder is written again at once`() {
        assertTrue(AutoBackupPolicy.isDue(copied, "a", listOf("other.zip"), now + 1))
    }

    @Test fun `Back up now always writes`() {
        assertTrue(AutoBackupPolicy.isDue(copied, "a", listOf("copy.zip"), now + 1, force = true))
    }

    @Test fun `a clock set back doesn't stop the copy`() {
        assertTrue(AutoBackupPolicy.isDue(copied, "a", listOf("copy.zip"), now - 1))
    }

    @Test fun `the nudge needs an old copy and nothing copying`() {
        val old = AutoBackupRecord(lastBackupAt = now - NUDGE_AFTER_MS - 1)
        assertTrue(AutoBackupPolicy.needsNudge(old, BackupDestination.NOT_CHOSEN, now))
        assertTrue(AutoBackupPolicy.needsNudge(old, BackupDestination.LOST, now))
        assertTrue(AutoBackupPolicy.needsNudge(old.copy(enabled = false), BackupDestination.READY, now))
        assertFalse("copying: no nudge", AutoBackupPolicy.needsNudge(old, BackupDestination.READY, now))
        assertFalse("30 days exactly", AutoBackupPolicy.needsNudge(old.copy(lastBackupAt = now - NUDGE_AFTER_MS), BackupDestination.LOST, now))
        assertFalse("never copied", AutoBackupPolicy.needsNudge(AutoBackupRecord(), BackupDestination.UNAVAILABLE, now))
    }

    @Test fun `the folder card waits for a recipe and asks once`() {
        val fresh = AutoBackupRecord()
        assertFalse(AutoBackupPolicy.offersFolderPrompt(fresh, BackupDestination.NOT_CHOSEN, libraryEmpty = true))
        assertTrue(AutoBackupPolicy.offersFolderPrompt(fresh, BackupDestination.NOT_CHOSEN, libraryEmpty = false))
        assertFalse(AutoBackupPolicy.offersFolderPrompt(fresh.copy(folderPromptDone = true), BackupDestination.NOT_CHOSEN, false))
        assertFalse(AutoBackupPolicy.offersFolderPrompt(fresh.copy(enabled = false), BackupDestination.NOT_CHOSEN, false))
        assertFalse(AutoBackupPolicy.offersFolderPrompt(fresh, BackupDestination.READY, false))
        assertFalse("iOS never asks", AutoBackupPolicy.offersFolderPrompt(fresh, BackupDestination.UNAVAILABLE, false))
    }

    @Test fun `copies are named by the local minute`() {
        val zone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("recipe-clipper-backup-2027-01-15-0800.zip", AutoBackupPolicy.fileName(now))
            assertTrue(AutoBackupPolicy.isBackupFile(AutoBackupPolicy.fileName(now)))
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test fun `only the app's own copies beyond the newest three go`() {
        val ours = (1..5).map { "recipe-clipper-backup-2026-09-0$it-1200.zip" }
        val theirs = listOf("recipe-clipper-2026-09-01.zip", "notes.txt", "recipe-clipper-backup-2026-09-01-1200 (1).zip")
        val written = ours.last()
        val gone = AutoBackupPolicy.toDelete(ours + theirs, written)
        assertEquals(ours.take(5 - KEEP).toSet(), gone.toSet())
    }

    @Test fun `the copy just written is kept whatever its name`() {
        val ours = (1..4).map { "recipe-clipper-backup-2026-09-0$it-1200.zip" }
        val gone = AutoBackupPolicy.toDelete(ours + "renamed.zip", "renamed.zip")
        assertEquals(ours.take(2).toSet(), gone.toSet())
    }

    @Test fun `the fingerprint ignores when the export was made, and nothing else`() {
        val a = """{"format":"recipe-clipper-backup","exportedAt":1,"recipes":[]}"""
        val b = """{"format":"recipe-clipper-backup","exportedAt":2,"recipes":[]}"""
        val c = """{"format":"recipe-clipper-backup","exportedAt":2,"recipes":[{"title":"Soup"}]}"""
        assertEquals(AutoBackupPolicy.fingerprint(a, emptyList()), AutoBackupPolicy.fingerprint(b, emptyList()))
        assertNotEquals(AutoBackupPolicy.fingerprint(b, emptyList()), AutoBackupPolicy.fingerprint(c, emptyList()))
        assertNotEquals(AutoBackupPolicy.fingerprint(a, emptyList()), AutoBackupPolicy.fingerprint(a, listOf("photos/p.jpg")))
    }
}
