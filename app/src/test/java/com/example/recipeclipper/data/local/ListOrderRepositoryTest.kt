package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.DefaultListRepository
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cook's list order (#381) through [DefaultListRepository] over a real database: stored
 * with `reorderLists`, read by both the Lists screen and the save-to-list sheet, and with the
 * `reorderLists` flag off the order of before it (seeded lists first).
 */
@RunWith(AndroidJUnit4::class)
class ListOrderRepositoryTest {

    private lateinit var db: RecipeDatabase
    private val store = FakeFeatureFlagStore()
    private val flags = FeatureFlags(store, FlagRegistry.definitions, isDebug = true)
    private lateinit var repository: DefaultListRepository

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecipeDatabase::class.java)
            .addCallback(RecipeDatabase.SeedBuiltInLists)
            .build()
        repository = DefaultListRepository(db.listDao(), Clock { 1_000 }, { _, _ -> }, flags)
    }

    @After
    fun close() = db.close()

    private suspend fun names() = repository.observeLists().first().map { it.name }

    @Test
    fun aReorderIsStoredAndBothReadersFollowIt() = runBlocking {
        val ids = repository.observeLists().first().map { it.id }
        repository.reorderLists(ids.reversed())

        val expected = listOf("Snacks", "Breakfast", "Desserts", "Dinner", "Lunch", "Favorites")
        assertEquals(expected, names())
        assertEquals(expected, repository.observeListsFor(5).first().map { it.name })
    }

    @Test
    fun aListCreatedAfterAReorderGoesLast() = runBlocking {
        repository.reorderLists(repository.observeLists().first().map { it.id }.reversed())
        repository.createList("Weeknights")

        assertEquals("Weeknights", names().last())
    }

    @Test
    fun withTheFlagOffSeededListsAreFirstAgainWhateverTheStoredOrder() = runBlocking {
        repository.createList("Weeknights")
        val ids = repository.observeLists().first().map { it.id }
        repository.reorderLists(listOf(ids.last()) + ids.dropLast(1))
        assertEquals("Weeknights", names().first())

        store.setOverride(Flag.REORDER_LISTS.key, false)

        assertEquals(
            listOf("Favorites", "Lunch", "Dinner", "Desserts", "Breakfast", "Snacks", "Weeknights"),
            names()
        )
    }
}
