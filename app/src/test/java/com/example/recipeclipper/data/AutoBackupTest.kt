package com.example.recipeclipper.data

import com.example.recipeclipper.data.backup.AutoBackupPolicy
import com.example.recipeclipper.data.backup.AutoBackupRecord
import com.example.recipeclipper.data.backup.BackupDestination
import com.example.recipeclipper.data.backup.BackupError
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ExportedBackup
import com.example.recipeclipper.fake.AutoBackupFixture
import com.example.recipeclipper.fake.AutoBackupFixture.Companion.FOLDER
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The automatic copy (#150) against a fake export, folder, record and scheduler. */
class AutoBackupTest {

    private fun exported(json: String) = BackupResult.Success(ExportedBackup(json, 1L, 1))

    @Test fun `with no folder nothing is written`() = runTest {
        val f = AutoBackupFixture()
        assertEquals(AutoBackupOutcome.NO_DESTINATION, f.autoBackup.run())
        assertEquals(0, f.repository.exportCalls)
    }

    @Test fun `the first look writes a copy and records it`() = runTest {
        val f = AutoBackupFixture().withFolder()
        assertEquals(AutoBackupOutcome.WRITTEN, f.autoBackup.run())
        val name = AutoBackupPolicy.fileName(f.time)
        assertEquals(listOf(name), f.folder.files.keys.toList())
        val record = f.store.record.value
        assertEquals(f.time, record.lastBackupAt)
        assertEquals(name, record.lastFile)
        assertFalse(record.lastFailed)
    }

    @Test fun `an unchanged library isn't written again, a changed one is after the gap`() = runTest {
        val f = AutoBackupFixture().withFolder()
        f.repository.exportResult = exported("""{"exportedAt":1,"recipes":[]}""")
        f.autoBackup.run()
        f.time += AutoBackupPolicy.MIN_GAP_MS
        f.repository.exportResult = exported("""{"exportedAt":2,"recipes":[]}""")
        assertEquals(AutoBackupOutcome.UP_TO_DATE, f.autoBackup.run())

        f.repository.exportResult = exported("""{"exportedAt":3,"recipes":[{"title":"Soup"}]}""")
        assertEquals(AutoBackupOutcome.WRITTEN, f.autoBackup.run())
        assertEquals(2, f.folder.files.size)
    }

    @Test fun `only the newest three copies stay, and the user's own files are never touched`() = runTest {
        val f = AutoBackupFixture().withFolder()
        f.folder.files["shopping.txt"] = "mine"
        repeat(5) { i ->
            f.repository.exportResult = exported("""{"exportedAt":$i,"n":$i}""")
            f.autoBackup.run(force = true)
            f.time += 24L * 60 * 60 * 1000
        }
        val ours = f.folder.files.keys.filter(AutoBackupPolicy::isBackupFile)
        assertEquals(AutoBackupPolicy.KEEP, ours.size)
        assertTrue("shopping.txt" in f.folder.files)
        assertTrue(f.store.record.value.lastFile in ours)
    }

    @Test fun `a failed write keeps the older copies and says so`() = runTest {
        val f = AutoBackupFixture().withFolder()
        f.autoBackup.run()
        val before = f.store.record.value
        f.folder.writable = false
        assertEquals(AutoBackupOutcome.FAILED, f.autoBackup.run(force = true))
        assertEquals(1, f.folder.files.size)
        assertTrue(f.store.record.value.lastFailed)
        assertEquals(before.lastBackupAt, f.store.record.value.lastBackupAt)

        f.folder.writable = true
        f.time += 60_000
        f.autoBackup.run(force = true)
        assertFalse("a good copy clears it", f.store.record.value.lastFailed)
    }

    @Test fun `an export that fails writes nothing`() = runTest {
        val f = AutoBackupFixture().withFolder()
        f.repository.exportResult = BackupResult.Failure(BackupError.ExportFailed)
        assertEquals(AutoBackupOutcome.FAILED, f.autoBackup.run())
        assertTrue(f.folder.files.isEmpty())
        assertNull(f.store.record.value.lastBackupAt)
    }

    @Test fun `a folder whose permission went is reported lost`() = runTest {
        val f = AutoBackupFixture().withFolder()
        f.folder.granted.clear()
        assertEquals(AutoBackupOutcome.NO_DESTINATION, f.autoBackup.run())
        assertEquals(BackupDestination.LOST, f.autoBackup.state.first().destination)
    }

    @Test fun `off, it writes nothing by itself, but Back up now still works`() = runTest {
        val f = AutoBackupFixture().withFolder()
        f.autoBackup.setEnabled(false)
        assertEquals(AutoBackupOutcome.OFF, f.autoBackup.run())
        assertEquals(AutoBackupOutcome.WRITTEN, f.autoBackup.run(force = true))
    }

    @Test fun `choosing a folder keeps it, gives the old one back and copies at once`() = runTest {
        val f = AutoBackupFixture(AutoBackupRecord(enabled = false)).withFolder("content://old")
        assertTrue(f.autoBackup.chooseFolder(FOLDER))
        val record = f.store.record.value
        assertEquals(FOLDER, record.folderUri)
        assertEquals("Drive", record.folderName)
        assertTrue("choosing a folder turns the copy on", record.enabled)
        assertTrue(record.folderPromptDone)
        assertEquals(listOf("content://old"), f.folder.released)
        assertEquals(1, f.scheduler.now)
    }

    @Test fun `a folder that can't be kept changes nothing`() = runTest {
        val f = AutoBackupFixture()
        f.folder.refuses = true
        assertFalse(f.autoBackup.chooseFolder(FOLDER))
        assertNull(f.store.record.value.folderUri)
        assertEquals(0, f.scheduler.now)
    }

    @Test fun `leaving the app looks for a copy only when one is being kept`() {
        val f = AutoBackupFixture()
        f.autoBackup.onAppLeft()
        assertEquals(0, f.scheduler.afterLeaving)
        f.withFolder()
        f.autoBackup.onAppLeft()
        assertEquals(1, f.scheduler.afterLeaving)
        f.autoBackup.setEnabled(false)
        f.autoBackup.onAppLeft()
        assertEquals(1, f.scheduler.afterLeaving)
    }
}
