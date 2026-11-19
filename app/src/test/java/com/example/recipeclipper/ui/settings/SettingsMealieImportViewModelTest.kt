package com.example.recipeclipper.ui.settings

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.MealieResult
import com.example.recipeclipper.data.backup.MealieSummary
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.remote.MealieError
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeBackupRepository
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeMealieImportRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Settings → Your recipes → Import from Mealie (#391): the ViewModel's state. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsMealieImportViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val mealie = FakeMealieImportRepository()
    private val backups = FakeBackupRepository()

    private fun flags(on: Boolean = true) = FeatureFlags(
        FakeFeatureFlagStore(mapOf(Flag.MEALIE_IMPORT.key to on)), FlagRegistry.definitions, isDebug = false
    )

    private fun viewModel(flags: FeatureFlags? = flags(), importer: FakeMealieImportRepository? = mealie) =
        SettingsViewModel(FakeAppPreferences(), backups, FakeBackupFiles(), FakeAppInfo(), flags, mealie = importer)

    private val summary = MealieSummary(
        imported = 12, alreadyHere = 2, skippedForLimit = 0, notRecipes = 1, unreadable = 0,
        photosLeftOut = 2, freeLimit = null, listsMade = 3
    )

    @Test fun `the row shows only with the flag on and an importer`() = runTest(mainDispatcherRule.dispatcher) {
        advanceUntilIdle()
        assertTrue(viewModel().also { advanceUntilIdle() }.uiState.value.showsMealieImport)
        assertFalse(viewModel(flags(on = false)).also { advanceUntilIdle() }.uiState.value.showsMealieImport)
        assertFalse(viewModel(importer = null).also { advanceUntilIdle() }.uiState.value.showsMealieImport)
        assertFalse(viewModel(flags = null).uiState.value.showsMealieImport)
    }

    @Test fun `a picked file is read, with progress, then its summary shows`() = runTest(mainDispatcherRule.dispatcher) {
        mealie.result = MealieResult.Success(summary)
        val gate = CompletableDeferred<Unit>()
        mealie.gate = gate
        val vm = viewModel()

        vm.onMealiePicked("content://picked")
        advanceUntilIdle()
        assertEquals(BackupStatus.ImportingMealie, vm.uiState.value.backup)
        assertTrue(vm.uiState.value.backup.isBusy)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("content://picked"), mealie.imported)
        assertEquals(BackupStatus.ImportedMealie(summary), vm.uiState.value.backup)
    }

    @Test fun `the filing switch is on by default, is this visit's choice, and goes to the import`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.mealieFiling)

        vm.onMealiePicked("content://one")
        advanceUntilIdle()
        vm.onMealieFilingChange(false)
        vm.onMealiePicked("content://two")
        advanceUntilIdle()
        assertEquals(listOf(true, false), mealie.filings)
        assertFalse(vm.uiState.value.mealieFiling)
    }

    @Test fun `the switch survives a preference change`() = runTest(mainDispatcherRule.dispatcher) {
        val preferences = FakeAppPreferences()
        val vm = SettingsViewModel(preferences, backups, FakeBackupFiles(), FakeAppInfo(), flags(), mealie = mealie)
        vm.onMealieFilingChange(false)
        preferences.darkWhileCooking = true
        advanceUntilIdle()
        assertFalse(vm.uiState.value.mealieFiling)
    }

    @Test fun `a refused file shows its cause`() = runTest(mainDispatcherRule.dispatcher) {
        mealie.result = MealieResult.Failure(MealieError.DAMAGED)
        val vm = viewModel()
        vm.onMealiePicked("content://picked")
        advanceUntilIdle()
        assertEquals(BackupStatus.MealieFailed(MealieError.DAMAGED), vm.uiState.value.backup)
    }

    @Test fun `one thing at a time, so export, import and a second Mealie pick wait`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        mealie.gate = gate
        val vm = viewModel()
        vm.onMealiePicked("content://one")
        advanceUntilIdle()

        vm.onMealiePicked("content://two")
        vm.onImportPicked("content://three")
        vm.onExport()
        advanceUntilIdle()
        assertEquals(listOf("content://one"), mealie.imported)
        assertEquals(0, backups.exportCalls)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test fun `a preference change mid-import keeps the status`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        mealie.gate = gate
        val preferences = FakeAppPreferences()
        val vm = SettingsViewModel(preferences, backups, FakeBackupFiles(), FakeAppInfo(), flags(), mealie = mealie)
        vm.onMealiePicked("content://one")
        advanceUntilIdle()
        preferences.darkWhileCooking = true
        advanceUntilIdle()
        assertEquals(BackupStatus.ImportingMealie, vm.uiState.value.backup)
        assertTrue(vm.uiState.value.showsMealieImport)
        gate.complete(Unit)
        advanceUntilIdle()
    }
}
