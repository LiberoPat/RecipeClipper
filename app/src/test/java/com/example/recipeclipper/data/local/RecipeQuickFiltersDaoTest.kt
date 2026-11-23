package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.DefaultRecipeRepository
import com.example.recipeclipper.data.ErrorLog
import com.example.recipeclipper.data.local.dao.ListDao
import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.RecipeFilter
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.data.local.entity.CookedPhotoEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.RecipeQuickFilter
import com.example.recipeclipper.data.model.RecipeQuickFilters
import com.example.recipeclipper.data.model.RecipeSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The data Recipes' quick filters read (#378), against real SQLite: the summary rows carry the
 * stored times and language, "Not cooked yet" is exactly the Cooked view turned round, and the
 * pantry chip's lines (every recipe's, as stored) come out of one query. The search keeps its
 * `instr(lower(...))` rules underneath all of it.
 */
@RunWith(AndroidJUnit4::class)
class RecipeQuickFiltersDaoTest {

    private lateinit var db: RecipeDatabase
    private lateinit var recipes: RecipeDao

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecipeDatabase::class.java)
            .addCallback(RecipeDatabase.SeedBuiltInLists)
            .allowMainThreadQueries()
            .build()
        recipes = db.recipeDao()
    }

    @After
    fun close() = db.close()

    private fun recipe(
        url: String, title: String = "Recipe $url", lines: List<String> = listOf("1 cup flour"),
        prep: String? = null, cook: String? = null, total: String? = null, language: String? = "en"
    ) = RecipeEntity(
        sourceUrl = url, title = title, imageUrl = null, ingredients = lines,
        instructions = listOf("Bake."), prepTime = prep, cookTime = cook, totalTime = total,
        servings = "4", sourceType = "BLOG", lastViewedAt = 1, language = language
    )

    private suspend fun all(query: String = ""): List<RecipeSummary> =
        recipes.observeHistory(query, RecipeDao.LISTED_ANY, 0, 0, 0).first().map { row ->
            RecipeSummary(
                row.id, row.title, row.imageUrl, row.totalTime, row.lastViewedAt, row.isSaved,
                lastCookedDay = row.lastCookedDay, prepTime = row.prepTime, cookTime = row.cookTime, language = row.language
            )
        }

    @Test
    fun theSummaryRowsCarryTheStoredTimesAndLanguage() = runBlocking {
        recipes.upsert(recipe("https://a.com/a", prep = "10m", cook = "15m"), LibraryLimit.Unlimited)
        recipes.upsert(recipe("https://a.com/b", total = "1h 5m", language = "de"), LibraryLimit.Unlimited)

        val byUrl = all().associateBy { it.title }
        val quick = byUrl.getValue("Recipe https://a.com/a")
        assertEquals("10m", quick.prepTime)
        assertEquals("15m", quick.cookTime)
        assertEquals(null, quick.totalTime)
        assertEquals("en", quick.language)
        assertEquals("1h 5m", byUrl.getValue("Recipe https://a.com/b").totalTime)
        assertEquals("de", byUrl.getValue("Recipe https://a.com/b").language)
        // And the filter reads them as the stored text says: 25 minutes is 30 or less, 1h 5m isn't.
        assertEquals(
            listOf("Recipe https://a.com/a"),
            RecipeQuickFilters.apply(all(), setOf(RecipeQuickFilter.THIRTY_OR_LESS)).map { it.title }
        )
    }

    @Test
    fun notCookedIsExactlyTheCookedViewTurnedRound() = runBlocking {
        val photographed = recipes.upsert(recipe("https://a.com/photo"), LibraryLimit.Unlimited)
        val marked = recipes.upsert(recipe("https://a.com/marked"), LibraryLimit.Unlimited)
        val never = recipes.upsert(recipe("https://a.com/never"), LibraryLimit.Unlimited)
        // A photo (#116) and "Mark as cooked" with no photo (#173) both count as cooked.
        db.cookedPhotoDao().insert(CookedPhotoEntity(recipeId = photographed, fileName = "a.jpg", day = 20_000, note = null, createdAt = 1, updatedAt = 1))
        db.cookedPhotoDao().insert(CookedPhotoEntity(recipeId = marked, fileName = null, day = 20_001, note = null, createdAt = 1, updatedAt = 1))

        val cookedView = recipes.observeHistory("", RecipeDao.LISTED_ANY, 1, 0, 0).first().map { it.id }.toSet()
        val notCooked = RecipeQuickFilters.apply(all(), setOf(RecipeQuickFilter.NOT_COOKED)).map { it.id }.toSet()
        assertEquals(setOf(photographed, marked), cookedView)
        assertEquals(setOf(never), notCooked)
        assertEquals("the two partition the library", all().map { it.id }.toSet(), cookedView + notCooked)
    }

    @Test
    fun theSearchStillUsesInstrNeverLikeUnderTheChips() = runBlocking {
        recipes.upsert(recipe("https://a.com/pct", title = "100% Whole Wheat", total = "20m"), LibraryLimit.Unlimited)
        recipes.upsert(recipe("https://a.com/other", title = "1000 Layer Cake", total = "20m"), LibraryLimit.Unlimited)

        val found = RecipeQuickFilters.apply(all("100%"), setOf(RecipeQuickFilter.THIRTY_OR_LESS))
        assertEquals(listOf("100% Whole Wheat"), found.map { it.title })
    }

    /**
     * The filter sheet's views (#454) through the real repository and SQL: one view selects what its own screen
     * does, and several together select exactly the recipes every one of those screens holds, the search
     * (`instr`, a literal `%`) still applying.
     */
    @Test
    fun theSheetsViewsTogetherSelectWhatEveryViewsScreenHolds() = runBlocking {
        val repository = DefaultRecipeRepository(
            source = object : RecipeSource { override suspend fun fetch(url: String): ParseResult = error("nothing is fetched") },
            recipeDao = recipes,
            clock = Clock { 1L },
            log = object : ErrorLog { override fun error(message: String, cause: Throwable) = Unit }
        )
        fun origin(entity: RecipeEntity, origin: String) = entity.copy(contentOrigin = origin)
        val typedLoose = recipes.upsert(origin(recipe("manual:a", title = "100% Typed loose"), "MANUAL"), LibraryLimit.Unlimited)
        val typedListed = recipes.upsert(origin(recipe("manual:b", title = "Typed listed"), "MANUAL"), LibraryLimit.Unlimited)
        val chatLoose = recipes.upsert(origin(recipe("https://chat/c", title = "Chat loose"), "CHAT"), LibraryLimit.Unlimited)
        val parsedCooked = recipes.upsert(recipe("https://a.com/d", title = "Parsed cooked"), LibraryLimit.Unlimited)
        val sample = recipes.upsert(origin(recipe("manual:sample", title = "Sample"), "MANUAL"), LibraryLimit.Unlimited)
        val favorites = db.listDao().observeLists(ListDao.NO_RECIPE).first().single { it.isFavorites }.id
        db.listDao().addToList(RecipeListCrossRef(typedListed, favorites, 1))
        for (id in listOf(typedLoose, parsedCooked)) {
            db.cookedPhotoDao().insert(CookedPhotoEntity(recipeId = id, fileName = null, day = 20_000, note = null, createdAt = 1, updatedAt = 1))
        }

        suspend fun ids(views: Set<RecipeFilter>, query: String = "") = repository.observeHistory(query, views).first().map { it.id }.toSet()
        suspend fun view(filter: RecipeFilter) = repository.observeHistory("", filter).first().map { it.id }.toSet()

        for (filter in RecipeFilter.views) assertEquals(filter.name, view(filter), ids(setOf(filter)))
        assertEquals(setOf(typedLoose, chatLoose, parsedCooked, sample), view(RecipeFilter.NOT_IN_A_LIST))
        assertEquals(setOf(typedLoose, typedListed), view(RecipeFilter.TYPED))

        val combos = listOf(
            setOf(RecipeFilter.NOT_IN_A_LIST, RecipeFilter.TYPED),
            setOf(RecipeFilter.NOT_IN_A_LIST, RecipeFilter.COOKED),
            setOf(RecipeFilter.COOKED, RecipeFilter.TYPED),
            setOf(RecipeFilter.NOT_IN_A_LIST, RecipeFilter.CHAT),
            setOf(RecipeFilter.NOT_IN_A_LIST, RecipeFilter.COOKED, RecipeFilter.TYPED)
        )
        for (combo in combos) {
            assertEquals(combo.toString(), combo.map { view(it) }.reduce { a, b -> a intersect b }, ids(combo))
        }
        assertEquals(setOf(typedLoose), ids(setOf(RecipeFilter.NOT_IN_A_LIST, RecipeFilter.COOKED, RecipeFilter.TYPED)))
        assertEquals(emptySet<Long>(), ids(setOf(RecipeFilter.CHAT, RecipeFilter.COOKED)))
        // The search still applies inside, as a literal substring.
        assertEquals(setOf(typedLoose), ids(setOf(RecipeFilter.NOT_IN_A_LIST, RecipeFilter.TYPED), query = "100%"))
        // Nothing asked: every recipe.
        assertEquals(setOf(typedLoose, typedListed, chatLoose, parsedCooked, sample), ids(emptySet()))
    }

    @Test
    fun everyRecipesLinesComeOutAsStoredInOneQuery() = runBlocking {
        val soup = recipes.upsert(recipe("https://a.com/soup", title = "Soup", lines = listOf("1 onion", "2 carrots")), LibraryLimit.Unlimited)
        val kuchen = recipes.upsert(
            recipe("https://a.com/kuchen", title = "Kuchen", lines = listOf("200 g Mehl"), language = "de"), LibraryLimit.Unlimited
        )

        val rows = recipes.observeRecipeLines().first()
        assertEquals(listOf(soup, kuchen), rows.map { it.id })
        assertEquals(listOf("1 onion", "2 carrots"), rows[0].ingredients)
        assertEquals("de", rows[1].language)
        assertEquals("Kuchen", rows[1].title)
    }
}
