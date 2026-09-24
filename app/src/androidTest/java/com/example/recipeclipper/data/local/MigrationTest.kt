package com.example.recipeclipper.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.recipeclipper.data.local.dao.ListDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migrations run against a real database opened at the old version, from the schema exported
 * to `app/schemas`. This is why that directory is committed.
 *
 * CLAUDE.md forbids destructive migration, so every version bump has to be proved to carry
 * existing data across — a recipe someone saved months ago is the whole point of the app.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RecipeDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    /**
     * Version 1 never had Breakfast or Snacks. They are seeded in `onCreate`, which does not
     * run again on an upgrade, so without the migration an existing install would never get
     * them — that is the whole reason [RecipeDatabase.MIGRATION_1_2] exists.
     */
    @Test
    fun migration1To2AddsBreakfastAndSnacks() {
        helper.createDatabase(name, 1).use { db ->
            // Version 1's own four seeded lists, written by hand: the callback that normally
            // seeds them doesn't run through MigrationTestHelper.
            listOf("Favorites", "Lunch", "Dinner", "Desserts").forEachIndexed { index, listName ->
                db.execSQL(
                    "INSERT INTO lists (name, isBuiltIn, isFavorites, sortOrder, createdAt) " +
                            "VALUES (?, 1, ?, ?, 0)",
                    arrayOf<Any>(listName, if (index == 0) 1 else 0, index)
                )
            }
        }

        helper.runMigrationsAndValidate(name, 2, true, RecipeDatabase.MIGRATION_1_2)

        val lists = runBlocking { openMigrated().listDao().observeLists(ListDao.NO_RECIPE).first() }
        assertEquals(
            listOf("Favorites", "Lunch", "Dinner", "Desserts", "Breakfast", "Snacks"),
            lists.map { it.name }
        )
        assertEquals(1, lists.count { it.isFavorites })
        assertTrue(lists.all { it.isBuiltIn })
    }

    /** A recipe and its list membership must survive the upgrade untouched. */
    @Test
    fun migration1To2KeepsExistingRecipesAndMembership() {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO lists (id, name, isBuiltIn, isFavorites, sortOrder, createdAt) " +
                        "VALUES (1, 'Favorites', 1, 1, 0, 0)"
            )
            db.execSQL(
                """
                INSERT INTO recipes
                  (id, sourceUrl, title, imageUrl, ingredients, instructions, prepTime,
                   cookTime, totalTime, servings, sourceType, lastViewedAt, checkedIngredients)
                VALUES
                  (7, 'https://example.com/a', 'Adobo', NULL, '["1 cup soy sauce"]',
                   '["Simmer."]', NULL, NULL, NULL, '4', 'BLOG', 123, '[]')
                """.trimIndent()
            )
            db.execSQL("INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (7, 1, 5)")
        }

        helper.runMigrationsAndValidate(name, 2, true, RecipeDatabase.MIGRATION_1_2)

        val db = openMigrated()
        runBlocking {
            val recipe = db.recipeDao().get(7)
            assertEquals("Adobo", recipe?.title)
            assertEquals(listOf("1 cup soy sauce"), recipe?.ingredients)
            assertEquals(123L, recipe?.lastViewedAt)
            // Still in Favorites, and Favorites still holds exactly it.
            assertEquals(listOf(1L), db.recipeDao().crossRefsFor(7).map { it.listId })
            assertEquals(listOf(7L), db.listDao().observeRecipesIn(1).first().map { it.id })
        }
    }

    /** New lists added after an upgrade sort after the seeded block, not into the middle of it. */
    @Test
    fun migration1To2LeavesUserListsInPlace() {
        helper.createDatabase(name, 1).use { db ->
            listOf("Favorites", "Lunch", "Dinner", "Desserts").forEachIndexed { index, listName ->
                db.execSQL(
                    "INSERT INTO lists (name, isBuiltIn, isFavorites, sortOrder, createdAt) " +
                            "VALUES (?, 1, ?, ?, 0)",
                    arrayOf<Any>(listName, if (index == 0) 1 else 0, index)
                )
            }
            db.execSQL(
                "INSERT INTO lists (name, isBuiltIn, isFavorites, sortOrder, createdAt) " +
                        "VALUES ('Weeknights', 0, 0, 4, 0)"
            )
        }

        helper.runMigrationsAndValidate(name, 2, true, RecipeDatabase.MIGRATION_1_2)

        val lists = runBlocking { openMigrated().listDao().observeLists(ListDao.NO_RECIPE).first() }
        assertEquals(
            listOf("Favorites", "Lunch", "Dinner", "Desserts", "Breakfast", "Snacks", "Weeknights"),
            lists.map { it.name }
        )
    }

    /** Opens the migrated file through Room so the DAOs can read it. */
    private fun openMigrated(): RecipeDatabase =
        Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RecipeDatabase::class.java,
            name
        )
            .addMigrations(RecipeDatabase.MIGRATION_1_2)
            .build()
            .also { helper.closeWhenFinished(it) }
}
