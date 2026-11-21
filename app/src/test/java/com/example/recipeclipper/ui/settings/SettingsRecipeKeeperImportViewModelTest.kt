package com.example.recipeclipper.ui.settings

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.PaprikaResult
import com.example.recipeclipper.data.backup.PaprikaSummary
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.remote.ImportSource
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

/** Settings → Your recipes → Import from Recipe Keeper (#390): the ViewModel's state. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRecipeKeeperImportViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val importer = FakePaprikaImportRepository()

    private fun flags(keeper: Boolean = true, paprika: Boolean = true) = FeatureFlags(
        FakeFeatureFlagStore(mapOf(Flag.RECIPE_KEEPER_IMPORT.key to keeper, Flag.PAPRIKA_IMPORT.key to paprika)),
        FlagRegistry.definitions, isDebug = false
    )

    private fun viewModel(flags: FeatureFlags? = flags(), repository: FakePaprikaImportRepository? = importer) =
        SettingsViewModel(FakeAppPreferences(), FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo(), flags, paprika = repository)

    private val summary = PaprikaSummary(
        imported = 7, alreadyHere = 1, skippedForLimit = 0, notRecipes = 2, unreadable = 0,
        photosLeftOut = 1, freeLimit = null, listsMade = 4
    )

    @Test fun `the row shows only with its own flag on and an importer, whatever Paprika's flag says`() = runTest(mainDispatcherRule.dispatcher) {
        assertTrue(viewModel().also { advanceUntilIdle() }.uiState.value.showsRecipeKeeperImport)
        assertTrue(viewModel(flags(paprika = false)).also { advanceUntilIdle() }.uiState.value.showsRecipeKeeperImport)
        assertFalse(viewModel(flags(paprika = false)).also { advanceUntilIdle() }.uiState.value.showsPaprikaImport)
        assertFalse(viewModel(flags(keeper = false)).also { advanceUntilIdle() }.uiState.value.showsRecipeKeeperImport)
        assertTrue(viewModel(flags(keeper = false)).also { advanceUntilIdle() }.uiState.value.showsPaprikaImport)
        assertFalse(viewModel(repository = null).also { advanceUntilIdle() }.uiState.value.showsRecipeKeeperImport)
        assertFalse(viewModel(flags = null).uiState.value.showsRecipeKeeperImport)
    }

    @Test fun `a picked file is read as Recipe Keeper's, with progress, then its summary shows`() = runTest(mainDispatcherRule.dispatcher) {
        importer.result = PaprikaResult.Success(summary)
        val gate = CompletableDeferred<Unit>()
        importer.gate = gate
        val vm = viewModel()

        vm.onRecipeKeeperPicked("content://picked")
        advanceUntilIdle()
        assertEquals(BackupStatus.ImportingRecipeKeeper, vm.uiState.value.backup)
        assertTrue(vm.uiState.value.backup.isBusy)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("content://picked"), importer.imported)
        assertEquals(listOf(ImportSource.RECIPE_KEEPER), importer.sources)
        assertEquals(BackupStatus.ImportedRecipeKeeper(summary), vm.uiState.value.backup)
    }

    @Test fun `the one filing switch goes to both imports`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onRecipeKeeperPicked("content://one")
        advanceUntilIdle()
        vm.onPaprikaFilingChange(false)
        vm.onRecipeKeeperPicked("content://two")
        advanceUntilIdle()
        vm.onPaprikaPicked("content://three")
        advanceUntilIdle()
        assertEquals(listOf(true, false, false), importer.filings)
        assertEquals(listOf(ImportSource.RECIPE_KEEPER, ImportSource.RECIPE_KEEPER, ImportSource.PAPRIKA), importer.sources)
    }

    @Test fun `a refused file shows its cause`() = runTest(mainDispatcherRule.dispatcher) {
        importer.result = PaprikaResult.Failure(PaprikaError.NOT_A_RECIPE_KEEPER_FILE)
        val vm = viewModel()
        vm.onRecipeKeeperPicked("content://picked")
        advanceUntilIdle()
        assertEquals(BackupStatus.RecipeKeeperFailed(PaprikaError.NOT_A_RECIPE_KEEPER_FILE), vm.uiState.value.backup)
    }

    @Test fun `one thing at a time, so a Paprika pick waits for a Recipe Keeper one`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        importer.gate = gate
        val vm = viewModel()
        vm.onRecipeKeeperPicked("content://one")
        advanceUntilIdle()

        vm.onPaprikaPicked("content://two")
        vm.onRecipeKeeperPicked("content://three")
        advanceUntilIdle()
        assertEquals(listOf("content://one"), importer.imported)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test fun `a preference change mid-import keeps the status and the row`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        importer.gate = gate
        val preferences = FakeAppPreferences()
        val vm = SettingsViewModel(preferences, FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo(), flags(), paprika = importer)
        vm.onRecipeKeeperPicked("content://one")
        advanceUntilIdle()
        preferences.darkWhileCooking = true
        advanceUntilIdle()
        assertEquals(BackupStatus.ImportingRecipeKeeper, vm.uiState.value.backup)
        assertTrue(vm.uiState.value.showsRecipeKeeperImport)
        gate.complete(Unit)
        advanceUntilIdle()
    }
}
