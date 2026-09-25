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
import org.junit.Assert.assertNull
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

    /**
     * Version 3 adds the personal note. Every recipe from before has none, and nothing else
     * about it — content, ticks, list membership — may change on the way.
     */
    @Test
    fun migration2To3AddsAnEmptyNoteAndKeepsEverythingElse() {
        helper.createDatabase(name, 2).use { db ->
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
                   '["Simmer."]', NULL, NULL, NULL, '4', 'BLOG', 123, '[0]')
                """.trimIndent()
            )
            db.execSQL("INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (7, 1, 5)")
        }

        helper.runMigrationsAndValidate(name, 3, true, RecipeDatabase.MIGRATION_2_3)

        val db = openMigrated()
        runBlocking {
            val recipe = db.recipeDao().get(7)
            assertEquals("Adobo", recipe?.title)
            assertEquals(listOf("1 cup soy sauce"), recipe?.ingredients)
            assertEquals(setOf(0), recipe?.checkedIngredients)
            assertEquals(123L, recipe?.lastViewedAt)
            assertNull(recipe?.notes)
            assertEquals(listOf(1L), db.recipeDao().crossRefsFor(7).map { it.listId })

            // And the new column is writable and survives a re-share.
            db.recipeDao().setNotes(7, "Less salt")
            db.recipeDao().upsert(recipe!!.copy(id = 0, title = "Chicken adobo"), 50)
            assertEquals("Less salt", db.recipeDao().get(7)?.notes)
        }
    }

    /**
     * Version 4 gives every recipe and list a stable uid (#26). Existing rows get distinct
     * UUID-shaped ones and keep everything else; new rows get theirs from the entity default.
     */
    @Test
    fun migration3To4GivesEveryRowADistinctUid() {
        helper.createDatabase(name, 3).use { db ->
            listOf("Favorites", "Lunch").forEachIndexed { index, listName ->
                db.execSQL(
                    "INSERT INTO lists (id, name, isBuiltIn, isFavorites, sortOrder, createdAt) VALUES (?, ?, 1, ?, ?, 0)",
                    arrayOf<Any>(index + 1, listName, if (index == 0) 1 else 0, index)
                )
            }
            for (id in 1..3) {
                db.execSQL(
                    """
                    INSERT INTO recipes
                      (id, sourceUrl, title, imageUrl, ingredients, instructions, prepTime, cookTime,
                       totalTime, servings, sourceType, lastViewedAt, checkedIngredients, notes)
                    VALUES (?, ?, 'R', NULL, '[]', '[]', NULL, NULL, NULL, NULL, 'BLOG', ?, '[]', 'n')
                    """.trimIndent(),
                    arrayOf<Any>(id, "https://example.com/$id", id * 10)
                )
            }
            db.execSQL("INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (1, 1, 5)")
        }

        helper.runMigrationsAndValidate(name, 4, true, RecipeDatabase.MIGRATION_3_4)

        val db = openMigrated()
        runBlocking {
            val snapshot = db.backupDao().snapshot()
            val uids = snapshot.recipes.map { it.uid } + snapshot.lists.map { it.uid }
            assertEquals(5, uids.toSet().size)
            val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
            assertTrue(uids.all { uuid.matches(it) })
            assertEquals("n", db.recipeDao().get(1)?.notes)
            assertEquals(listOf(1L), db.recipeDao().crossRefsFor(1).map { it.listId })

            // A re-share keeps the uid; a new list gets one of its own.
            val before = db.recipeDao().get(2)!!
            db.recipeDao().upsert(before.copy(id = 0, uid = "should-not-win", title = "New title"), 50)
            assertEquals(before.uid, db.recipeDao().get(2)?.uid)
            db.listDao().create("Mine", ListDao.NO_RECIPE, 0)
            assertEquals(3, db.backupDao().snapshot().lists.map { it.uid }.toSet().size)
        }
    }

    /**
     * Version 5 adds the recipe's language (#14): a real version-4 row keeps its content, note
     * and uid, has no language, and a re-share fills it in.
     */
    @Test
    fun migration4To5AddsNoLanguageAndKeepsEverythingElse() {
        helper.createDatabase(name, 4).use { db ->
            db.execSQL(
                """
                INSERT INTO recipes
                  (id, sourceUrl, title, imageUrl, ingredients, instructions, prepTime,
                   cookTime, totalTime, servings, sourceType, lastViewedAt, checkedIngredients, notes, uid)
                VALUES
                  (7, 'https://example.com/a', 'Adobo', NULL, '["1 cup soy sauce"]',
                   '["Simmer."]', NULL, NULL, NULL, '4', 'BLOG', 123, '[0]', 'Less salt', 'uid-7')
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(name, 5, true, RecipeDatabase.MIGRATION_4_5)

        val db = openMigrated()
        runBlocking {
            val recipe = db.recipeDao().get(7)
            assertEquals("Adobo", recipe?.title)
            assertEquals(setOf(0), recipe?.checkedIngredients)
            assertEquals("Less salt", recipe?.notes)
            assertEquals("uid-7", recipe?.uid)
            assertNull(recipe?.language)

            // A re-share fills the language in and keeps the note and uid.
            db.recipeDao().upsert(recipe!!.copy(id = 0, language = "en-us"), 50)
            assertEquals("en-us", db.recipeDao().get(7)?.language)
            assertEquals("Less salt", db.recipeDao().get(7)?.notes)
            assertEquals("uid-7", db.recipeDao().get(7)?.uid)
        }
    }

    /**
     * Version 6 adds saved cook progress and the chosen servings (#10). A recipe from before
     * has neither (it opens at step one, at its own yield), and nothing else about it changes:
     * its uid, note, ticks and list membership come across as they were.
     */
    @Test
    fun migration5To6AddsNoCookStateAndKeepsEverythingElse() {
        helper.createDatabase(name, 5).use { db ->
            db.execSQL(
                "INSERT INTO lists (id, name, isBuiltIn, isFavorites, sortOrder, createdAt, uid) " +
                        "VALUES (1, 'Favorites', 1, 1, 0, 0, 'list-uid')"
            )
            db.execSQL(
                """
                INSERT INTO recipes
                  (id, sourceUrl, title, imageUrl, ingredients, instructions, prepTime,
                   cookTime, totalTime, servings, sourceType, lastViewedAt, checkedIngredients, notes, uid, language)
                VALUES
                  (7, 'https://example.com/a', 'Adobo', NULL, '["1 cup soy sauce"]',
                   '["Simmer for 30 minutes."]', NULL, NULL, NULL, '4', 'BLOG', 123, '[0]', 'Less salt',
                   'recipe-uid', 'en')
                """.trimIndent()
            )
            db.execSQL("INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (7, 1, 5)")
        }

        helper.runMigrationsAndValidate(name, 6, true, RecipeDatabase.MIGRATION_5_6)

        val db = openMigrated()
        runBlocking {
            val recipe = db.recipeDao().get(7)
            assertEquals("Adobo", recipe?.title)
            assertEquals("recipe-uid", recipe?.uid)
            assertEquals("en", recipe?.language)
            assertEquals(setOf(0), recipe?.checkedIngredients)
            assertEquals("Less salt", recipe?.notes)
            assertNull(recipe?.cookState)
            assertNull(recipe?.servingsTarget)
            assertEquals(listOf(1L), db.recipeDao().crossRefsFor(7).map { it.listId })

            // And the new columns are writable.
            db.recipeDao().setCookState(7, """{"active":true,"currentStep":0,"doneSteps":[],"timers":[]}""")
            db.recipeDao().setServingsTarget(7, 6)
            assertEquals(6, db.recipeDao().get(7)?.servingsTarget)
            assertEquals(1, db.recipeDao().cookStates().size)
        }
    }

    /**
     * Version 7 adds whose words a recipe is (#29). Everything stored before was parsed from
     * its link and never edited: PARSED, no editedAt, and nothing else changes.
     */
    @Test
    fun migration6To7MakesEveryRecipeParsedAndUneditedAndKeepsEverythingElse() {
        helper.createDatabase(name, 6).use { db ->
            db.execSQL(
                """
                INSERT INTO recipes
                  (id, sourceUrl, title, imageUrl, ingredients, instructions, prepTime,
                   cookTime, totalTime, servings, sourceType, lastViewedAt, checkedIngredients, notes, uid,
                   language, cookState, servingsTarget)
                VALUES
                  (7, 'https://example.com/a', 'Adobo', NULL, '["1 cup soy sauce"]',
                   '["Simmer for 30 minutes."]', NULL, NULL, NULL, '4', 'BLOG', 123, '[0]', 'Less salt',
                   'recipe-uid', 'en', NULL, 6)
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(name, 7, true, RecipeDatabase.MIGRATION_6_7)

        val db = openMigrated()
        runBlocking {
            val recipe = db.recipeDao().get(7)
            assertEquals("Adobo", recipe?.title)
            assertEquals("recipe-uid", recipe?.uid)
            assertEquals("Less salt", recipe?.notes)
            assertEquals(6, recipe?.servingsTarget)
            assertEquals("PARSED", recipe?.contentOrigin)
            assertNull(recipe?.editedAt)
        }
    }

    /**
     * Version 8 adds the week meal plan (#49): two new tables, the four seeded meal types with
     * their keys, and nothing existing changes. A recipe from before can be planned at once.
     */
    @Test
    fun migration7To8AddsTheMealPlanAndKeepsEverythingElse() {
        helper.createDatabase(name, 7).use { db ->
            db.execSQL(
                """
                INSERT INTO recipes
                  (id, sourceUrl, title, imageUrl, ingredients, instructions, prepTime,
                   cookTime, totalTime, servings, sourceType, lastViewedAt, checkedIngredients, notes, uid,
                   language, cookState, servingsTarget, contentOrigin, editedAt)
                VALUES
                  (7, 'https://example.com/a', 'Adobo', NULL, '["1 cup soy sauce"]',
                   '["Simmer for 30 minutes."]', NULL, NULL, NULL, '4', 'BLOG', 123, '[0]', 'Less salt',
                   'recipe-uid', 'en', NULL, 6, 'EDITED', 99)
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(name, 8, true, RecipeDatabase.MIGRATION_7_8)

        val db = openMigrated()
        runBlocking {
            val recipe = db.recipeDao().get(7)
            assertEquals("Adobo", recipe?.title)
            assertEquals("EDITED", recipe?.contentOrigin)
            assertEquals(6, recipe?.servingsTarget)

            val types = db.mealPlanDao().observeMealTypes().first()
            assertEquals(listOf("Breakfast", "Lunch", "Dinner", "Snack"), types.map { it.name })
            assertEquals(listOf("breakfast", "lunch", "dinner", "snack"), types.map { it.builtInKey })
            assertEquals(4, types.map { it.uid }.toSet().size)

            val dinner = types.single { it.builtInKey == "dinner" }.id
            db.mealPlanDao().add(
                com.example.recipeclipper.data.local.entity.MealPlanEntryEntity(
                    day = 20_000, mealTypeId = dinner, recipeId = 7, servings = 4, note = null,
                    sortOrder = 0, updatedAt = 1
                )
            )
            assertEquals(listOf("Adobo"), db.mealPlanDao().observeDays(20_000, 20_006).first().map { it.title })
        }
    }

    /** A version-1 install goes all the way to the current version in one open. */
    @Test
    fun migration1ToCurrentRunsEveryStep() {
        helper.createDatabase(name, 1).close()

        helper.runMigrationsAndValidate(name, 8, true, *RecipeDatabase.ALL_MIGRATIONS)

        val db = openMigrated()
        val lists = runBlocking { db.listDao().observeLists(ListDao.NO_RECIPE).first() }
        assertEquals(listOf("Breakfast", "Snacks"), lists.map { it.name })
        val types = runBlocking { db.mealPlanDao().observeMealTypes().first() }
        assertEquals(4, types.size)
    }

    /** Opens the migrated file through Room so the DAOs can read it. */
    private fun openMigrated(): RecipeDatabase =
        Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RecipeDatabase::class.java,
            name
        )
            .addMigrations(*RecipeDatabase.ALL_MIGRATIONS)
            .build()
            .also { helper.closeWhenFinished(it) }
}
