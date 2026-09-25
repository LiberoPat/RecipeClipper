package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.local.dao.PantryDao
import com.example.recipeclipper.data.local.entity.PantryItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The pantry (#51) against real SQLite: stock changes, edits, and a delete that restores whole. */
@RunWith(AndroidJUnit4::class)
class PantryDaoTest {

    private lateinit var db: RecipeDatabase
    private lateinit var pantry: PantryDao

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java).build()
        pantry = db.pantryDao()
    }

    @After
    fun close() = db.close()

    private fun item(name: String, inStock: Boolean = true) = PantryItemEntity(
        name = name, quantity = null, language = "en", aisle = "other", inStock = inStock,
        alwaysHave = false, purchasedDay = 10, expiresDay = null, updatedAt = 1
    )

    @Test
    fun restockPutsItBackBoughtOnTheDay() = runBlocking {
        val id = pantry.insert(item("milk", inStock = false))
        pantry.restock(listOf(id), day = 20_000, now = 5)
        val milk = pantry.item(id)!!
        assertTrue(milk.inStock)
        assertEquals(20_000L, milk.purchasedDay)
        assertEquals(5L, milk.updatedAt)

        pantry.setInStock(listOf(id), false, now = 6)
        assertFalse(pantry.item(id)!!.inStock)
        assertEquals(20_000L, pantry.item(id)!!.purchasedDay) // running out keeps the date
    }

    @Test
    fun editChangesOnlyWhatTheSheetShows() = runBlocking {
        val id = pantry.insert(item("oil"))
        pantry.edit(id, "olive oil", "half a bottle", alwaysHave = true, expiresDay = 20_100, now = 7)
        val oil = pantry.item(id)!!
        assertEquals("olive oil", oil.name)
        assertEquals("half a bottle", oil.quantity)
        assertTrue(oil.alwaysHave)
        assertEquals(20_100L, oil.expiresDay)
        assertEquals(10L, oil.purchasedDay)
        assertTrue(oil.inStock)
    }

    @Test
    fun aDeleteRestoresWithItsIdAndUid() = runBlocking {
        val id = pantry.insert(item("rice"))
        val before = pantry.item(id)!!
        pantry.delete(id)
        assertNull(pantry.item(id))
        pantry.put(listOf(before))
        assertEquals(before, pantry.observeItems().first().single())
    }

    @Test
    fun uidsAreUnique() = runBlocking {
        pantry.insert(item("a").copy(uid = "same"))
        val failed = runCatching { pantry.insert(item("b").copy(uid = "same")) }.isFailure
        assertTrue(failed)
    }
}
