package com.example.recipeclipper.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.backup.AutoBackupPolicy
import com.example.recipeclipper.data.backup.AutoBackupRecord
import com.example.recipeclipper.fake.AutoBackupFixture
import com.example.recipeclipper.fake.AutoBackupFixture.Companion.FOLDER
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings → Your recipes' automatic backup copy (#150): the switch, the folder, "Last backed
 * up", "Back up now", and the lines that say something is wrong. A real ViewModel and
 * [com.example.recipeclipper.data.AutoBackup] over fakes.
 */
@RunWith(AndroidJUnit4::class)
class SettingsAutoBackupTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(fixture: AutoBackupFixture): SettingsViewModel {
        val viewModel = SettingsViewModel(
            FakeAppPreferences(), fixture.repository, FakeBackupFiles(), FakeAppInfo(),
            autoBackup = fixture.autoBackup, clock = fixture.clock
        )
        compose.setContent { SettingsScreen(onBack = {}, viewModel = viewModel) }
        return viewModel
    }

    private fun scrollTo(text: String) =
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = true))

    private fun backUpNow() = compose.onNode(hasText("Back up now") and hasClickAction())

    @Test
    fun onByDefaultWithNoFolderYet() {
        show(AutoBackupFixture())

        scrollTo("Back up now")
        compose.onNode(hasText("Automatic backup copy") and hasClickAction()).assertIsOn()
        compose.onNodeWithText("Not chosen yet", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Not backed up yet").assertIsDisplayed()
        backUpNow().assertIsNotEnabled()
    }

    @Test
    fun backUpNowWritesACopyAndShowsItsDate() {
        val fixture = AutoBackupFixture().withFolder()
        show(fixture)

        scrollTo("Back up now")
        compose.onNodeWithText("Drive").assertIsDisplayed()
        backUpNow().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { fixture.folder.files.isNotEmpty() }

        scrollTo("Last backed up")
        compose.onNodeWithText("Last backed up", substring = true).assertIsDisplayed()
        assertEquals(listOf(AutoBackupPolicy.fileName(fixture.time)), fixture.folder.files.keys.toList())
    }

    @Test
    fun turningItOffIsKept() {
        val fixture = AutoBackupFixture().withFolder()
        show(fixture)

        scrollTo("Automatic backup copy")
        compose.onNode(hasText("Automatic backup copy") and hasClickAction()).performClick()
        compose.onNode(hasText("Automatic backup copy") and hasClickAction()).assertIsOff()
        assertFalse(fixture.store.record.value.enabled)
    }

    @Test
    fun aFolderWhosePermissionWentSaysSo() {
        val fixture = AutoBackupFixture().withFolder()
        fixture.folder.granted.clear()
        show(fixture)

        scrollTo("can no longer reach")
        compose.onNodeWithText("can no longer reach this folder", substring = true).assertIsDisplayed()
    }

    @Test
    fun anOldCopyWithNothingCopyingNudges() {
        val fixture = AutoBackupFixture(AutoBackupRecord(enabled = false))
        fixture.store.update { it.copy(lastBackupAt = fixture.time - AutoBackupPolicy.NUDGE_AFTER_MS - 1) }
        show(fixture)

        scrollTo("more than 30 days old")
        compose.onNodeWithText("more than 30 days old", substring = true).assertIsDisplayed()
    }

    @Test
    fun aRecentCopyThatIsStillBeingKeptDoesNotNudge() {
        val fixture = AutoBackupFixture().withFolder()
        fixture.store.update { it.copy(lastBackupAt = fixture.time - AutoBackupPolicy.NUDGE_AFTER_MS - 1) }
        show(fixture)

        scrollTo("Back up now")
        compose.onNodeWithText("more than 30 days old", substring = true).assertDoesNotExist()
    }

    @Test
    fun aPickedFolderIsKeptAndARefusedOneSaysSo() {
        val fixture = AutoBackupFixture()
        val viewModel = show(fixture)

        fixture.folder.refuses = true
        compose.runOnIdle { viewModel.onBackupFolderPicked(FOLDER) }
        scrollTo("can't be used")
        compose.onNodeWithText("can't be used for backups", substring = true).assertIsDisplayed()

        fixture.folder.refuses = false
        compose.runOnIdle { viewModel.onBackupFolderPicked(FOLDER) }
        compose.waitForIdle()
        assertEquals(FOLDER, fixture.store.record.value.folderUri)
        assertTrue(fixture.store.record.value.folderPromptDone)
        compose.onNodeWithText("can't be used for backups", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Drive").assertIsDisplayed()
    }
}
