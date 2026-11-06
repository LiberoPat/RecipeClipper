package com.example.recipeclipper.widgets

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.R
import com.example.recipeclipper.data.GroceryRepository
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.GroceryItem
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.PlannedMeal
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/** The home-screen widgets (#309) drawn over fakes, as the launcher inflates them (Robolectric). */
@RunWith(AndroidJUnit4::class)
class HomeWidgetsTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val host = WidgetHost(app)
    private val plan = FakeMealPlanRepository()
    private val groceries = FakeGroceryRepository()
    private val calendar = FakePlanCalendar()
    private val store = FakeFeatureFlagStore()
    private val flags = FeatureFlags(store, FlagRegistry.definitions, isDebug = true)
    private val widgets = HomeWidgets(app, plan, groceries, calendar, flags)
    private var tonight = 0
    private var list = 0

    @Before
    fun placeBoth() {
        tonight = host.place(TonightWidgetProvider::class.java, 1)
        list = host.place(GroceriesWidgetProvider::class.java, 2)
    }

    private fun meal(id: Long, day: Long, type: Long, title: String) =
        PlannedMeal(id, day, type, recipeId = id, title = title, imageUrl = null, servings = null, note = null)

    private suspend fun addItems(count: Int) = groceries.add((1..count).map { NewGroceryLine("item $it", "en") })

    @Test
    fun tonightShowsTodaysMealsUnderTheirMealType() = runBlocking {
        val today = calendar.today
        plan.meals.value = listOf(
            meal(1, today, FakeMealPlanRepository.DINNER, "Focaccia"),
            meal(2, today + 1, FakeMealPlanRepository.DINNER, "Tomorrow's soup"),
            meal(3, today, FakeMealPlanRepository.BREAKFAST, "Porridge")
        )
        widgets.update()

        assertEquals(listOf("Tonight", "Breakfast", "Porridge", "Dinner", "Focaccia"), host.texts(tonight))
    }

    @Test
    fun theListShowsWhatsLeftToBuyAndSaysWhenNothingIs() = runBlocking {
        widgets.update()
        assertEquals(listOf("Groceries", "Nothing left to buy"), host.texts(list))

        groceries.add(listOf(NewGroceryLine("2 onions", "en"), NewGroceryLine("paper towels", "en")))
        widgets.update()
        assertEquals(listOf("Groceries", "2 onions", "paper towels"), host.texts(list))
    }

    @Test
    fun withTheFlagOffOnlyTheAppsNameShows() = runBlocking {
        store.setOverride(Flag.WIDGETS.key, false)
        groceries.add(listOf(NewGroceryLine("2 onions", "en")))
        widgets.update()

        assertEquals(listOf("Recipe Clipper"), host.texts(list))
        assertEquals(listOf("Recipe Clipper"), host.texts(tonight))
    }

    @Test
    fun aLongListEndsWithHowManyMore() = runBlocking {
        addItems(20)
        widgets.update()

        // No height from the launcher: four rows, the last one the count.
        assertEquals(listOf("Groceries", "item 1", "item 2", "item 3", "+17 more"), host.texts(list))
    }

    // #329: one row high used to show "+20 more" and name nothing.
    @Test
    fun withRoomForOneRowTheFirstItemIsNamedWithTheCountBesideIt() = runBlocking {
        val today = calendar.today
        plan.meals.value = listOf(
            meal(1, today, FakeMealPlanRepository.DINNER, "Focaccia"),
            meal(2, today, FakeMealPlanRepository.BREAKFAST, "Porridge")
        )
        addItems(20)
        host.resize(list, portrait = 80)
        host.resize(tonight, portrait = 96)
        widgets.update()

        assertEquals(listOf("Groceries", "item 1", "+19 more"), host.texts(list))
        assertEquals(listOf("Tonight", "Breakfast", "Porridge", "+1 more"), host.texts(tonight))
    }

    @Test
    fun aListThatFitsItsOneRowHasNoCount() = runBlocking {
        addItems(1)
        host.resize(list, portrait = 80)
        widgets.update()

        assertEquals(listOf("Groceries", "item 1"), host.texts(list))
    }

    // #329: rows are sized in sp, so the budget is too.
    @Test
    fun theRowBudgetFollowsTheHeightAndTheFontScale() {
        val asSet: (Float) -> Float = { it }
        val doubled: (Float) -> Float = { it * 2 }
        assertEquals(6, HomeWidgets.rowsThatFit(210, HomeWidgets.LINE_ROW, asSet))
        assertEquals(2, HomeWidgets.rowsThatFit(210, HomeWidgets.LINE_ROW, doubled))
        assertEquals(3, HomeWidgets.rowsThatFit(210, HomeWidgets.MEAL_ROW, asSet))
        assertEquals(1, HomeWidgets.rowsThatFit(210, HomeWidgets.MEAL_ROW, doubled))
        assertEquals("never fewer than one", 1, HomeWidgets.rowsThatFit(60, HomeWidgets.MEAL_ROW, doubled))
        assertEquals("never a wall of rows", 12, HomeWidgets.rowsThatFit(2000, HomeWidgets.LINE_ROW, asSet))
        assertEquals("no height given", 4, HomeWidgets.rowsThatFit(0, HomeWidgets.LINE_ROW, asSet))
    }

    @Test
    fun largerTextShowsFewerRowsAndStillSaysHowManyMore() = runBlocking {
        addItems(20)
        host.resize(list, portrait = 210)
        widgets.update()
        assertEquals(listOf("Groceries", "item 1", "item 2", "item 3", "item 4", "item 5", "+15 more"), host.texts(list))

        RuntimeEnvironment.setFontScale(2f)
        widgets.update()
        val large = host.texts(list)
        assertTrue("fewer rows at twice the size, was $large", large.size < 7)
        assertEquals("item 1", large[1])
        assertEquals("+${20 - (large.size - 2)} more", large.last())
    }

    // #329: landscape is as high as the launcher's least height, not its most.
    @Test
    fun turnedToLandscapeTheWidgetShowsTheRowsThatFitThere() = runBlocking {
        addItems(20)
        host.resize(list, portrait = 300, landscape = 110)
        widgets.update()
        assertEquals(listOf("Groceries") + (1..9).map { "item $it" } + "+11 more", host.texts(list))

        RuntimeEnvironment.setQualifiers("+land")
        host.inflateAgain(list)
        assertEquals(listOf("Groceries", "item 1", "+19 more"), host.texts(list))
    }

    // #329: the colour was set when drawn, so it stayed the light theme's after dark came.
    @Test
    fun howManyMoreTakesTheThemesColourWhenTheSystemGoesDark() = runBlocking {
        addItems(20)
        widgets.update()
        val light = app.getColor(R.color.widget_muted)

        RuntimeEnvironment.setQualifiers("+night")
        host.inflateAgain(list)

        val more = host.textViews(list).last()
        assertEquals("+17 more", more.text.toString())
        assertTrue("the dark theme has its own muted", app.getColor(R.color.widget_muted) != light)
        assertEquals(app.getColor(R.color.widget_muted), more.currentTextColor)
    }

    /** A list whose first read is slow: it has its snapshot, then waits at [gate] to hand it over. */
    private class SlowFirstRead(
        private val inner: GroceryRepository, private val gate: CompletableDeferred<Unit>
    ) : GroceryRepository by inner {
        private var reads = 0
        override fun observeItems(): Flow<List<GroceryItem>> = flow {
            val snapshot = inner.observeItems().first()
            if (reads++ == 0) gate.await()
            emit(snapshot)
        }
    }

    // #329: a redraw from the app and one from a provider could overlap, the slower one drawing
    // the list as it was before the faster one's.
    @Test
    fun anOlderReadIsNeverDrawnOverANewerOne() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val racing = HomeWidgets(app, plan, SlowFirstRead(groceries, gate), calendar, flags)
        groceries.add(listOf(NewGroceryLine("2 onions", "en")))

        val slow = launch { racing.update() }
        yield() // It has read "2 onions", and waits.
        groceries.add(listOf(NewGroceryLine("paper towels", "en")))
        val fast = launch { racing.update() }
        yield()
        gate.complete(Unit)
        joinAll(slow, fast)

        assertEquals(listOf("Groceries", "2 onions", "paper towels"), host.texts(list))
    }
}
