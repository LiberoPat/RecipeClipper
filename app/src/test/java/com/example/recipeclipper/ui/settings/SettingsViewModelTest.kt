package com.example.recipeclipper.ui.settings

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.data.backup.BackupError
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ExportedBackup
import com.example.recipeclipper.data.backup.ImportSummary
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeBackupRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [SettingsUiState] is a plain `MutableStateFlow`, not `stateIn(WhileSubscribed(...))`, and the
 * ViewModel collects `AppPreferences.settings` itself, so it holds its value with no collector
 * needed — unlike [com.example.recipeclipper.ui.home.HomeViewModelTest] and
 * [com.example.recipeclipper.ui.history.HistoryViewModelTest], these tests don't need
 * `collectEagerly`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test fun `state is seeded from preferences on construction`() = runTest(mainDispatcherRule.dispatcher) {
        val preferences = FakeAppPreferences(
            unitSystem = UnitSystem.METRIC,
            convertLiquids = true,
            temperatureUnit = TemperatureUnit.CELSIUS,
            darkWhileCooking = true
        )
        val vm = SettingsViewModel(preferences, FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo())

        assertEquals(UnitSystem.METRIC, vm.uiState.value.unitSystem)
        assertTrue(vm.uiState.value.convertLiquids)
        assertEquals(TemperatureUnit.CELSIUS, vm.uiState.value.temperatureUnit)
        assertTrue(vm.uiState.value.darkWhileCooking)
    }

    @Test fun `the version shows and its seventh tap opens Developer settings, then counts again`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = SettingsViewModel(FakeAppPreferences(), FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo(appVersion = "2.3 (7)"))

            assertEquals("2.3 (7)", vm.uiState.value.appVersion)
            repeat(SettingsViewModel.DEVELOPER_TAPS - 1) { assertFalse(vm.onVersionTapped()) }
            assertTrue(vm.onVersionTapped())
            repeat(SettingsViewModel.DEVELOPER_TAPS - 1) { assertFalse(vm.onVersionTapped()) }
            assertTrue(vm.onVersionTapped())
        }

    @Test fun `defaults match AppPreferences defaults`()= runTest(mainDispatcherRule.dispatcher) {
        val vm = SettingsViewModel(FakeAppPreferences(), FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo())

        assertEquals(UnitSystem.AS_WRITTEN, vm.uiState.value.unitSystem)
        assertFalse(vm.uiState.value.convertLiquids)
        assertEquals(TemperatureUnit.AS_WRITTEN, vm.uiState.value.temperatureUnit)
        assertFalse(vm.uiState.value.darkWhileCooking)
    }

    @Test fun `onUnitSystemChange writes through and updates state`() = runTest(mainDispatcherRule.dispatcher) {
        val preferences = FakeAppPreferences()
        val vm = SettingsViewModel(preferences, FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo())

        vm.onUnitSystemChange(UnitSystem.METRIC)

        assertEquals(UnitSystem.METRIC, vm.uiState.value.unitSystem)
        assertEquals(UnitSystem.METRIC, preferences.unitSystem)
    }

    @Test fun `onConvertLiquidsChange writes through and updates state`() = runTest(mainDispatcherRule.dispatcher) {
        val preferences = FakeAppPreferences()
        val vm = SettingsViewModel(preferences, FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo())

        vm.onConvertLiquidsChange(true)

        assertTrue(vm.uiState.value.convertLiquids)
        assertTrue(preferences.convertLiquids)
    }

    @Test fun `onTemperatureUnitChange writes through and updates state independently of unit system`() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakeAppPreferences()
            val vm = SettingsViewModel(preferences, FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo())

            vm.onTemperatureUnitChange(TemperatureUnit.FAHRENHEIT)

            assertEquals(TemperatureUnit.FAHRENHEIT, vm.uiState.value.temperatureUnit)
            assertEquals(TemperatureUnit.FAHRENHEIT, preferences.temperatureUnit)
            // Independent: changing the oven temperature choice must not touch the unit system.
            assertEquals(UnitSystem.AS_WRITTEN, vm.uiState.value.unitSystem)
        }

    @Test fun `onDarkWhileCookingChange writes through and updates state`() = runTest(mainDispatcherRule.dispatcher) {
        val preferences = FakeAppPreferences()
        val vm = SettingsViewModel(preferences, FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo())

        vm.onDarkWhileCookingChange(true)

        assertTrue(vm.uiState.value.darkWhileCooking)
        assertTrue(preferences.darkWhileCooking)
    }

    @Test fun `a change written elsewhere while Settings is open reaches its state`() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakeAppPreferences()
            val vm = SettingsViewModel(preferences, FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo())
            advanceUntilIdle()

            // e.g. the recipe screen's units dropdown, with Settings on the back stack
            preferences.unitSystem = UnitSystem.OUNCES
            preferences.temperatureUnit = TemperatureUnit.CELSIUS
            advanceUntilIdle()

            assertEquals(UnitSystem.OUNCES, vm.uiState.value.unitSystem)
            assertEquals(TemperatureUnit.CELSIUS, vm.uiState.value.temperatureUnit)
        }

    @Test fun `a setter's write echoing back through settings leaves the state as set`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = SettingsViewModel(FakeAppPreferences(), FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo())
            advanceUntilIdle()

            vm.onUnitSystemChange(UnitSystem.METRIC)
            vm.onConvertLiquidsChange(true)
            advanceUntilIdle()

            assertEquals(SettingsUiState(unitSystem = UnitSystem.METRIC, convertLiquids = true, appVersion = "1.0 (1)"), vm.uiState.value)
        }

    // --- Your recipes: export and import (#26)

    private val backups = FakeBackupRepository()
    private val files = FakeBackupFiles()
    private fun backupVm() = SettingsViewModel(FakeAppPreferences(), backups, files, FakeAppInfo())

    @Test fun `export writes the file and hands its uri to the screen to share`() = runTest(mainDispatcherRule.dispatcher) {
        backups.exportResult = BackupResult.Success(ExportedBackup("{\"x\":1}", exportedAt = 42L, recipeCount = 3))
        val vm = backupVm()

        vm.onExport()
        assertEquals(BackupStatus.Exporting, vm.uiState.value.backup)
        advanceUntilIdle()

        assertEquals(listOf("{\"x\":1}" to 42L), files.written)
        assertEquals(BackupStatus.ReadyToShare("content://test/exports/recipe-clipper.json"), vm.uiState.value.backup)
        vm.onExportShared()
        assertEquals(BackupStatus.Idle, vm.uiState.value.backup)
    }

    @Test fun `an export the repository refuses is a cause, and nothing is written`() = runTest(mainDispatcherRule.dispatcher) {
        backups.exportResult = BackupResult.Failure(BackupError.ExportFailed)
        val vm = backupVm()
        vm.onExport()
        advanceUntilIdle()
        assertEquals(BackupStatus.Failed(BackupError.ExportFailed), vm.uiState.value.backup)
        assertTrue(files.written.isEmpty())
    }

    @Test fun `a file that can't be written is ExportFailed`() = runTest(mainDispatcherRule.dispatcher) {
        files.writeUri = null
        val vm = backupVm()
        vm.onExport()
        advanceUntilIdle()
        assertEquals(BackupStatus.Failed(BackupError.ExportFailed), vm.uiState.value.backup)
    }

    @Test fun `import reads the picked file and shows the summary`() = runTest(mainDispatcherRule.dispatcher) {
        files.files["content://picked"] = "the file"
        val summary = ImportSummary(recipesAdded = 12, listsAdded = 3, recipesAlreadyHere = 1, recipesSkipped = 0)
        backups.importResult = BackupResult.Success(summary)
        val vm = backupVm()

        vm.onImportPicked("content://picked")
        assertEquals(BackupStatus.Importing, vm.uiState.value.backup)
        advanceUntilIdle()

        assertEquals(listOf("the file"), backups.importedTexts)
        assertEquals(BackupStatus.Imported(summary), vm.uiState.value.backup)
    }

    @Test fun `a file that can't be read never reaches the repository`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = backupVm()
        vm.onImportPicked("content://missing")
        advanceUntilIdle()
        assertEquals(BackupStatus.Failed(BackupError.ReadFailed), vm.uiState.value.backup)
        assertTrue(backups.importedTexts.isEmpty())
    }

    @Test fun `a refused import shows its cause`() = runTest(mainDispatcherRule.dispatcher) {
        files.files["content://picked"] = "x"
        backups.importResult = BackupResult.Failure(BackupError.NewerVersion(2))
        val vm = backupVm()
        vm.onImportPicked("content://picked")
        advanceUntilIdle()
        assertEquals(BackupStatus.Failed(BackupError.NewerVersion(2)), vm.uiState.value.backup)
    }

    @Test fun `a second tap while one is running is ignored`() = runTest(mainDispatcherRule.dispatcher) {
        files.files["content://picked"] = "x"
        val gate = CompletableDeferred<Unit>()
        backups.importGate = gate
        val vm = backupVm()

        vm.onImportPicked("content://picked")
        advanceUntilIdle()
        vm.onImportPicked("content://picked")
        vm.onExport()
        advanceUntilIdle()
        assertEquals(1, backups.importedTexts.size)
        assertEquals(0, backups.exportCalls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.backup is BackupStatus.Imported)
    }

    @Test fun `a preference change mid-import keeps the import's status`() = runTest(mainDispatcherRule.dispatcher) {
        files.files["content://picked"] = "x"
        val gate = CompletableDeferred<Unit>()
        backups.importGate = gate
        val preferences = FakeAppPreferences()
        val vm = SettingsViewModel(preferences, backups, files, FakeAppInfo())

        vm.onImportPicked("content://picked")
        advanceUntilIdle()
        preferences.unitSystem = UnitSystem.OUNCES
        advanceUntilIdle()

        assertEquals(UnitSystem.OUNCES, vm.uiState.value.unitSystem)
        assertEquals(BackupStatus.Importing, vm.uiState.value.backup)
        gate.complete(Unit)
        advanceUntilIdle()
    }
}
