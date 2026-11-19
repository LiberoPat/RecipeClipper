package com.example.recipeclipper.ui.settings

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.PaprikaResult
import com.example.recipeclipper.data.backup.PaprikaSummary
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.remote.PaprikaError
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeBackupRepository
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakePaprikaImportRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Settings → Your recipes → Import from Paprika (#382): the ViewModel's state. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsPaprikaImportViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val paprika = FakePaprikaImportRepository()
    private val backups = FakeBackupRepository()

    private fun flags(on: Boolean = true) = FeatureFlags(
        FakeFeatureFlagStore(mapOf(Flag.PAPRIKA_IMPORT.key to on)), FlagRegistry.definitions, isDebug = false
    )

    private fun viewModel(flags: FeatureFlags? = flags(), importer: FakePaprikaImportRepository? = paprika) =
        SettingsViewModel(FakeAppPreferences(), backups, FakeBackupFiles(), FakeAppInfo(), flags, paprika = importer)

    private val summary = PaprikaSummary(
        imported = 12, alreadyHere = 2, skippedForLimit = 0, notRecipes = 1, unreadable = 0,
        photosNotImported = true, freeLimit = null
    )

    @Test fun `the row shows only with the flag on and an importer`() = runTest(mainDispatcherRule.dispatcher) {
        advanceUntilIdle()
        assertTrue(viewModel().also { advanceUntilIdle() }.uiState.value.showsPaprikaImport)
        assertFalse(viewModel(flags(on = false)).also { advanceUntilIdle() }.uiState.value.showsPaprikaImport)
        assertFalse(viewModel(importer = null).also { advanceUntilIdle() }.uiState.value.showsPaprikaImport)
        assertFalse(viewModel(flags = null).uiState.value.showsPaprikaImport)
    }

    @Test fun `a picked file is read, with progress, then its summary shows`() = runTest(mainDispatcherRule.dispatcher) {
        paprika.result = PaprikaResult.Success(summary)
        val gate = CompletableDeferred<Unit>()
        paprika.gate = gate
        val vm = viewModel()

        vm.onPaprikaPicked("content://picked")
        advanceUntilIdle()
        assertEquals(BackupStatus.ImportingPaprika, vm.uiState.value.backup)
        assertTrue(vm.uiState.value.backup.isBusy)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("content://picked"), paprika.imported)
        assertEquals(BackupStatus.ImportedPaprika(summary), vm.uiState.value.backup)
    }

    @Test fun `a refused file shows its cause`() = runTest(mainDispatcherRule.dispatcher) {
        paprika.result = PaprikaResult.Failure(PaprikaError.DAMAGED)
        val vm = viewModel()
        vm.onPaprikaPicked("content://picked")
        advanceUntilIdle()
        assertEquals(BackupStatus.PaprikaFailed(PaprikaError.DAMAGED), vm.uiState.value.backup)
    }

    @Test fun `one thing at a time, so export, import and a second Paprika pick wait`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        paprika.gate = gate
        val vm = viewModel()
        vm.onPaprikaPicked("content://one")
        advanceUntilIdle()

        vm.onPaprikaPicked("content://two")
        vm.onImportPicked("content://three")
        vm.onExport()
        advanceUntilIdle()
        assertEquals(listOf("content://one"), paprika.imported)
        assertEquals(0, backups.exportCalls)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test fun `a preference change mid-import keeps the status`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        paprika.gate = gate
        val preferences = FakeAppPreferences()
        val vm = SettingsViewModel(preferences, backups, FakeBackupFiles(), FakeAppInfo(), flags(), paprika = paprika)
        vm.onPaprikaPicked("content://one")
        advanceUntilIdle()
        preferences.darkWhileCooking = true
        advanceUntilIdle()
        assertEquals(BackupStatus.ImportingPaprika, vm.uiState.value.backup)
        assertTrue(vm.uiState.value.showsPaprikaImport)
        gate.complete(Unit)
        advanceUntilIdle()
    }
}
