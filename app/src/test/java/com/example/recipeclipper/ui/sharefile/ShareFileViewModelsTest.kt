package com.example.recipeclipper.ui.sharefile

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.backup.BackupError
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ImportSummary
import com.example.recipeclipper.data.backup.PantryDestination
import com.example.recipeclipper.data.backup.ShareChoice
import com.example.recipeclipper.data.backup.fixture
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeShareFileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** "Send as file" and the sheet a received file opens (#149, phase 2), over fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareFileViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val files = FakeBackupFiles()
    private val share = FakeShareFileRepository()
    private val inbox = ReceivedFileInbox()
    private val uri = "content://messages/attachment/Sheet-pan chicken.recipeclipper"

    private fun flags(mealPlan: Boolean) =
        FeatureFlags(FakeFeatureFlagStore(mapOf("mealPlan" to mealPlan)), FlagRegistry.definitions, isDebug = false)

    private fun receiver(mealPlan: Boolean = true) = ReceiveFileViewModel(files, share, flags(mealPlan), inbox)

    // --- Sending

    @Test
    fun `a recipe is written as a file named for it, for the share sheet`() = runTest(mainDispatcherRule.dispatcher) {
        share.recipeFiles[7] = "{json}"
        val vm = SendFileViewModel(share, files)

        vm.sendRecipe(7, "Sheet-pan chicken")
        advanceUntilIdle()

        assertEquals(listOf("{json}" to "Sheet-pan chicken.recipeclipper"), files.shared)
        assertEquals(SentFile(files.shareUri!!, "Sheet-pan chicken.recipeclipper"), vm.uiState.value.file)
        vm.onSent()
        assertNull(vm.uiState.value.file)
    }

    @Test
    fun `a file that can't be made or written says so`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = SendFileViewModel(share, files)
        vm.sendGroceries("Groceries")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.failed)
        assertTrue(files.shared.isEmpty())
        vm.onFailureShown()

        share.groceriesFile = "{json}"
        files.shareUri = null
        vm.sendGroceries("Groceries")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.failed)
        assertEquals(listOf("{json}" to "Groceries.recipeclipper"), files.shared)
    }

    // --- Receiving

    @Test
    fun `a file opened with the app shows what's inside, every row ticked`() = runTest(mainDispatcherRule.dispatcher) {
        files.files[uri] = fixture("share-v1.recipeclipper")
        val vm = receiver()

        inbox.offer(uri)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.open)
        assertEquals(listOf("Sheet-pan chicken", "Grandma's lemon cake"), state.recipes.map { it.text })
        assertEquals(listOf("2 lb chicken thighs", "1 lemon", "baking paper"), state.groceries.map { it.text })
        // Each grocery item names its recipe.
        assertEquals(listOf("Sheet-pan chicken", "Sheet-pan chicken", null), state.groceries.map { it.detail })
        assertEquals(listOf("Basmati rice"), state.pantry.map { it.text })
        assertEquals(6, state.tickedCount)
        assertNull(inbox.pending.value)
    }

    @Test
    fun `without the tabs only the recipes are offered`() = runTest(mainDispatcherRule.dispatcher) {
        files.files[uri] = fixture("share-v1.recipeclipper")
        val vm = receiver(mealPlan = false)
        vm.open(uri)
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.recipes.size)
        assertTrue(vm.uiState.value.groceries.isEmpty() && vm.uiState.value.pantry.isEmpty())
    }

    @Test
    fun `a file that can't be read, or isn't one, says why`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = receiver()
        vm.open(uri)
        advanceUntilIdle()
        assertEquals(BackupError.ReadFailed, vm.uiState.value.error)

        files.files[uri] = "{\"hello\": 1}"
        vm.open(uri)
        advanceUntilIdle()
        assertEquals(BackupError.NotABackup, vm.uiState.value.error)
        vm.onAdd()
        advanceUntilIdle()
        assertTrue(share.received.isEmpty())
    }

    @Test
    fun `Add merges what is ticked, pantry items where the receiver chose`() = runTest(mainDispatcherRule.dispatcher) {
        files.files[uri] = fixture("share-v1.recipeclipper")
        val vm = receiver()
        vm.open(uri)
        advanceUntilIdle()

        vm.onToggle("r:r-cake")
        vm.onToggle("g:g-paper")
        vm.onPantryTo(PantryDestination.GROCERIES)
        vm.onAdd()
        advanceUntilIdle()

        assertEquals(
            listOf(ShareChoice(setOf("r-chicken"), setOf("g-thighs", "g-lemon"), setOf("p-rice"), PantryDestination.GROCERIES)),
            share.received
        )
        assertEquals(ReceivedWhere.GROCERIES, vm.uiState.value.added)
        // A second tap adds nothing more.
        vm.onAdd()
        advanceUntilIdle()
        assertEquals(1, share.received.size)
    }

    @Test
    fun `recipes alone show the recipes, and pantry items alone the pantry`() = runTest(mainDispatcherRule.dispatcher) {
        files.files[uri] = fixture("share-v1.recipeclipper")
        val vm = receiver()
        vm.open(uri)
        advanceUntilIdle()
        listOf("g:g-thighs", "g:g-lemon", "g:g-paper", "p:p-rice").forEach(vm::onToggle)
        vm.onAdd()
        advanceUntilIdle()
        assertEquals(ReceivedWhere.RECIPES, vm.uiState.value.added)

        vm.onDismiss()
        vm.open(uri)
        advanceUntilIdle()
        listOf("r:r-chicken", "r:r-cake", "g:g-thighs", "g:g-lemon", "g:g-paper").forEach(vm::onToggle)
        vm.onAdd()
        advanceUntilIdle()
        assertEquals(ReceivedWhere.PANTRY, vm.uiState.value.added)
    }

    @Test
    fun `a failed save keeps the sheet open, and a full free library says what was left out`() =
        runTest(mainDispatcherRule.dispatcher) {
            files.files[uri] = fixture("share-v1.recipeclipper")
            val vm = receiver()
            vm.open(uri)
            advanceUntilIdle()

            share.receiveResult = BackupResult.Failure(BackupError.SaveFailed)
            vm.onAdd()
            advanceUntilIdle()
            assertEquals(BackupError.SaveFailed, vm.uiState.value.error)
            assertNull(vm.uiState.value.added)
            assertFalse(vm.uiState.value.adding)

            share.receiveResult = BackupResult.Success(ImportSummary(1, 0, 0, recipesSkipped = 1, freeLimit = 20))
            vm.onAdd()
            advanceUntilIdle()
            assertEquals(1 to 20, vm.uiState.value.skippedFree)
            assertTrue(vm.uiState.value.open)
        }
}
