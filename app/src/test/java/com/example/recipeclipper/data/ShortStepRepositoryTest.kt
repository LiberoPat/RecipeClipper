package com.example.recipeclipper.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.local.RecipeDatabase
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.fake.FakeStepShortener
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Chef mode's cache (#100) against real SQLite: checked, keyed by step text and language, pruned. */
@RunWith(AndroidJUnit4::class)
class ShortStepRepositoryTest {

    private lateinit var db: RecipeDatabase
    private val model = FakeStepShortener()
    private lateinit var repository: DefaultShortStepRepository

    private val bake = "Bake for 25 to 30 minutes, until the top is golden and springy."
    private val whisk = "Whisk the eggs with the sugar until pale, thick and doubled."
    private val serve = "Serve warm."

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java).build()
        repository = DefaultShortStepRepository(db.shortStepDao(), model, Clock { 5 }) { _, e -> throw e }
    }

    @After
    fun close() = db.close()

    private fun recipe(steps: List<String>, language: String = "en"): Recipe = runBlocking {
        val id = db.recipeDao().upsert(
            RecipeEntity(
                sourceUrl = "https://example.com/cake", title = "Cake", imageUrl = null,
                ingredients = listOf("2 eggs"), instructions = steps, prepTime = null, cookTime = null,
                totalTime = null, servings = "4", sourceType = "BLOG", lastViewedAt = 1, language = language
            ),
            HISTORY_LIMIT
        )
        db.recipeDao().get(id)!!.toDomain()
    }

    private fun shown(recipe: Recipe) = runBlocking { repository.observe(recipe).first() }

    @Test fun `short steps that pass the check are saved, the rest show as written and aren't asked again`() = runBlocking {
        model.written[bake] = "Bake 25–30 min until golden."
        model.written[whisk] = "Whisk 3 eggs with sugar."
        val cake = recipe(listOf(bake, whisk, serve))
        repository.fill(cake)

        assertEquals(listOf("Bake 25–30 min until golden.", null, null), shown(cake))
        // "Serve warm." is too short to send; the failed whisk step is saved as failed.
        assertEquals(listOf(bake, whisk), model.asked)
        repository.fill(cake)
        assertEquals(listOf(bake, whisk), model.asked)
    }

    @Test fun `a step the model couldn't do now is asked again next time`() = runBlocking {
        val cake = recipe(listOf(bake))
        repository.fill(cake)
        model.written[bake] = "Bake 25–30 min until golden."
        repository.fill(cake)

        assertEquals(listOf(bake, bake), model.asked)
        assertEquals(listOf("Bake 25–30 min until golden."), shown(cake))
    }

    @Test fun `a changed step is written again and the old one is dropped`() = runBlocking {
        model.written[bake] = "Bake 25–30 min until golden."
        repository.fill(recipe(listOf(bake)))
        val longer = "Bake for 35 to 40 minutes, until the top is golden and springy."
        model.written[longer] = "Bake 35–40 min until golden."
        val edited = recipe(listOf(longer, bake))
        repository.fill(edited)

        assertEquals(listOf("Bake 35–40 min until golden.", "Bake 25–30 min until golden."), shown(edited))
        repository.fill(recipe(listOf(longer)))
        assertEquals(1, db.shortStepDao().observe(edited.id, "en").first().size)
    }

    @Test fun `rows are per language and go with their recipe`() = runBlocking {
        model.written[bake] = "Bake 25–30 min until golden."
        val cake = recipe(listOf(bake))
        repository.fill(cake)
        assertEquals(listOf<String?>(null), shown(cake.copy(language = "de")))

        db.recipeDao().delete(cake.id)
        assertEquals(0, db.shortStepDao().observe(cake.id, "en").first().size)
    }
}
