package com.example.recipeclipper.widgets

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.R
import com.example.recipeclipper.data.DecisionRepository
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.DecisionQuestion
import com.example.recipeclipper.data.model.Decisions
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.PlannedMeal
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The home-screen widgets (#309) drawn over fakes, as the launcher inflates them (Robolectric). */
@RunWith(AndroidJUnit4::class)
class HomeWidgetsTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = AppWidgetManager.getInstance(app)
    private val plan = FakeMealPlanRepository()
    private val groceries = FakeGroceryRepository()
    private val calendar = FakePlanCalendar()
    private val store = FakeFeatureFlagStore()
    private val noDecisions = object : DecisionRepository {
        override fun observe() = flowOf(Decisions.NONE)
        override suspend fun decide(questions: Collection<DecisionQuestion>) {}
    }
    private val widgets = HomeWidgets(
        app, plan, groceries, noDecisions, calendar, FeatureFlags(store, FlagRegistry.definitions, isDebug = true)
    )
    private var tonight = 0
    private var list = 0

    private fun place(provider: Class<*>, id: Int): Int {
        val component = ComponentName(app, provider)
        shadowOf(manager).addInstalledProvider(AppWidgetProviderInfo().apply {
            this.provider = component
            initialLayout = R.layout.widget_list
        })
        shadowOf(manager).setAllowedToBindAppWidgets(true)
        manager.bindAppWidgetIdIfAllowed(id, component)
        return id
    }

    @Before
    fun placeBoth() {
        tonight = place(TonightWidgetProvider::class.java, 1)
        list = place(GroceriesWidgetProvider::class.java, 2)
    }

    private fun texts(id: Int): List<String> {
        val out = mutableListOf<String>()
        fun walk(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view is TextView) out += view.text.toString()
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(shadowOf(manager).getViewFor(id))
        return out
    }

    private fun meal(id: Long, day: Long, type: Long, title: String) =
        PlannedMeal(id, day, type, recipeId = id, title = title, imageUrl = null, servings = null, note = null)

    @Test
    fun tonightShowsTodaysMealsUnderTheirMealType() = runBlocking {
        val today = calendar.today
        plan.meals.value = listOf(
            meal(1, today, FakeMealPlanRepository.DINNER, "Focaccia"),
            meal(2, today + 1, FakeMealPlanRepository.DINNER, "Tomorrow's soup"),
            meal(3, today, FakeMealPlanRepository.BREAKFAST, "Porridge")
        )
        widgets.update()

        assertEquals(listOf("Tonight", "Breakfast", "Porridge", "Dinner", "Focaccia"), texts(tonight))
    }

    @Test
    fun theListShowsWhatsLeftToBuyAndSaysWhenNothingIs() = runBlocking {
        widgets.update()
        assertEquals(listOf("Groceries", "Nothing left to buy"), texts(list))

        groceries.add(listOf(NewGroceryLine("2 onions", "en"), NewGroceryLine("paper towels", "en")))
        widgets.update()
        assertEquals(listOf("Groceries", "2 onions", "paper towels"), texts(list))
    }

    @Test
    fun aLongListEndsWithHowManyMore() = runBlocking {
        groceries.add((1..20).map { NewGroceryLine("item $it", "en") })
        widgets.update()

        val shown = texts(list)
        assertEquals("+${20 - (shown.size - 2)} more", shown.last())
    }

    @Test
    fun withTheFlagOffOnlyTheAppsNameShows() = runBlocking {
        store.setOverride(Flag.WIDGETS.key, false)
        groceries.add(listOf(NewGroceryLine("2 onions", "en")))
        widgets.update()

        assertEquals(listOf("Recipe Clipper"), texts(list))
        assertEquals(listOf("Recipe Clipper"), texts(tonight))
    }
}
