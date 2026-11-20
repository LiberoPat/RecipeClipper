package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.DefaultMealPlanRepository
import com.example.recipeclipper.data.ErrorLog
import com.example.recipeclipper.data.MealPlanRepository
import com.example.recipeclipper.data.local.dao.MealPlanDao
import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.PlanShift.Choice
import com.example.recipeclipper.data.model.PlanShift.Move
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Skip tonight (#398) against real SQLite, through the repository: every move of a skip lands in
 * one transaction (or none do), the plan order and meal types survive, and Undo gives back the
 * rows exactly as they were.
 */
@RunWith(AndroidJUnit4::class)
class SkipTonightRepositoryTest {

    private lateinit var db: RecipeDatabase
    private lateinit var recipes: RecipeDao
    private lateinit var dao: MealPlanDao
    private lateinit var repo: DefaultMealPlanRepository
    private val errors = mutableListOf<String>()

    private val today = 20_000L
    private val lastDay = today + 730
    private var now = 1_000L

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .addCallback(RecipeDatabase.SeedBuiltInLists) // seeds the meal types too
            .build()
        recipes = db.recipeDao()
        dao = db.mealPlanDao()
        repo = DefaultMealPlanRepository(dao, db.menuDao(), Clock { now++ }, ErrorLog { message, _ -> errors += message })
    }

    @After
    fun close() = db.close()

    private suspend fun typeId(key: String) = dao.observeMealTypes().first().single { it.builtInKey == key }.id

    private suspend fun makeRecipe(n: Int) = recipes.upsert(
        RecipeEntity(
            sourceUrl = "https://a.com/$n", title = "Recipe $n", imageUrl = null, ingredients = listOf("1 cup flour"),
            instructions = listOf("Bake."), prepTime = null, cookTime = null, totalTime = null,
            servings = "4", sourceType = "BLOG", lastViewedAt = n.toLong()
        ),
        50, today = today
    )

    /** Plans a meal and returns its id; [recipe] null plans a note. */
    private suspend fun plan(day: Long, type: String = "dinner", recipe: Int? = null, note: String? = null): Long {
        val typeId = typeId(type)
        if (recipe != null) repo.addRecipe(makeRecipe(recipe), day, typeId, servings = 3) else repo.addNote(note ?: "note", day, typeId)
        return dao.entriesFrom(day).filter { it.day == day && it.mealTypeId == typeId }.maxByOrNull { it.id }!!.id
    }

    private suspend fun days() = dao.entriesFrom(0).map { it.id to it.day }

    @Test
    fun pushingTheWeekMovesTheSkippedMealAndEveryLaterOneAndKeepsTheirTypes() = runBlocking {
        val tonight = plan(today, recipe = 1)
        val lunch = plan(today + 1, "lunch", recipe = 2)
        val note = plan(today + 3, note = "Eat out")
        val typesBefore = dao.entriesFrom(0).associate { it.id to it.mealTypeId }

        val skipped = repo.skip(tonight, Choice.PUSH_WEEK, today, lastDay)

        assertNotNull(skipped)
        assertEquals(
            listOf(Move(tonight, today, today + 1), Move(lunch, today + 1, today + 2), Move(note, today + 3, today + 4)),
            skipped!!.result.moved
        )
        assertEquals(listOf(tonight to today + 1, lunch to today + 2, note to today + 4), days())
        assertEquals(typesBefore, dao.entriesFrom(0).associate { it.id to it.mealTypeId })
    }

    @Test
    fun aMealBeforeTheSkippedOneNeverMoves() = runBlocking {
        val earlier = plan(today, recipe = 1)
        val skippedId = plan(today + 1, recipe = 2)
        val later = plan(today + 2, recipe = 3)

        repo.skip(skippedId, Choice.PUSH_WEEK, today, lastDay)

        assertEquals(listOf(earlier to today, skippedId to today + 2, later to today + 3), days())
    }

    @Test
    fun undoGivesBackExactlyWhatWasThere() = runBlocking {
        val first = plan(today, recipe = 1)
        plan(today, recipe = 2) // two dinners in one slot: their order must survive
        plan(today + 2, "lunch", note = "Leftovers")
        val before = dao.entriesFrom(0)

        val skipped = repo.skip(first, Choice.PUSH_WEEK, today, lastDay)!!
        assertTrue(dao.entriesFrom(0) != before)
        repo.undoSkip(skipped)

        assertEquals(before, dao.entriesFrom(0)) // days, order, updatedAt, uid: all of it
    }

    @Test
    fun nextFreeDayMovesOnlyThatMealToTheFirstDayWithoutItsType() = runBlocking {
        val tonight = plan(today, recipe = 1)
        plan(today + 1, recipe = 2)
        plan(today + 2, note = "Eat out")
        val lunch = plan(today + 3, "lunch", recipe = 3)
        val before = dao.entriesFrom(0)

        val skipped = repo.skip(tonight, Choice.NEXT_FREE_DAY, today, lastDay)!!

        assertEquals(listOf(Move(tonight, today, today + 3)), skipped.result.moved)
        assertEquals(today + 3, dao.entry(tonight)!!.day)
        assertEquals(today + 3, dao.entry(lunch)!!.day) // the lunch that day is still there, untouched
        assertEquals(before.size, dao.entriesFrom(0).size)

        repo.undoSkip(skipped)
        assertEquals(before, dao.entriesFrom(0))
    }

    @Test
    fun removeDropsTheMealNotTheRecipeAndUndoPutsItBack() = runBlocking {
        val tonight = plan(today, recipe = 1)
        val other = plan(today + 1, recipe = 2)
        val before = dao.entriesFrom(0)
        val recipeId = before.first { it.id == tonight }.recipeId!!

        val skipped = repo.skip(tonight, Choice.REMOVE, today, lastDay)!!

        assertEquals(listOf(tonight), skipped.result.removed)
        assertEquals(listOf(other to today + 1), days())
        assertNotNull(recipes.get(recipeId))

        repo.undoSkip(skipped)
        assertEquals(before, dao.entriesFrom(0))
    }

    @Test
    fun aMealInThePastCannotBeSkipped() = runBlocking {
        val yesterday = plan(today - 1, recipe = 1)
        val before = dao.entriesFrom(0)

        for (choice in Choice.entries) assertNull(repo.skip(yesterday, choice, today, lastDay))

        assertEquals(before, dao.entriesFrom(0))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun aMealThatIsGoneHasNothingToSkip() = runBlocking {
        assertNull(repo.skip(12_345, Choice.PUSH_WEEK, today, lastDay))
    }

    @Test
    fun theMealsMovedStayOneTransactionWhenAWriteFails() = runBlocking {
        val tonight = plan(today, recipe = 1)
        plan(today + 1, recipe = 2)
        val last = plan(today + 2, recipe = 3)
        val before = dao.entriesFrom(0)
        // The third move cannot be written: the first two must not stay.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse BEFORE UPDATE OF day ON meal_plan_entries WHEN NEW.id = $last " +
                "BEGIN SELECT RAISE(ABORT, 'refused'); END"
        )

        val skipped = repo.skip(tonight, Choice.PUSH_WEEK, today, lastDay)

        assertNull(skipped)
        assertEquals(listOf("skipMeal failed"), errors)
        assertEquals(before, dao.entriesFrom(0))
    }

    @Test
    fun theSecondSkipsUndoDoesNotTouchTheFirst() = runBlocking {
        val a = plan(today, recipe = 1)
        val b = plan(today + 4, recipe = 2)
        repo.skip(a, Choice.NEXT_FREE_DAY, today, lastDay) // a: today+1
        val second = repo.skip(b, Choice.NEXT_FREE_DAY, today, lastDay)!! // b: today+5

        repo.undoSkip(second)

        assertEquals(listOf(a to today + 1, b to today + 4), days())
    }
}
