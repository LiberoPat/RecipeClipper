package com.example.recipeclipper.data

import com.example.recipeclipper.data.backup.BackupError
import com.example.recipeclipper.data.backup.BackupJson
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ExistingList
import com.example.recipeclipper.data.backup.ExistingMealType
import com.example.recipeclipper.data.backup.ExistingPantryItem
import com.example.recipeclipper.data.backup.ExistingRecipe
import com.example.recipeclipper.data.backup.ExportedBackup
import com.example.recipeclipper.data.backup.ImportSummary
import com.example.recipeclipper.data.backup.fixture
import com.example.recipeclipper.data.local.dao.BackupDao
import com.example.recipeclipper.data.local.entity.GroceryItemEntity
import com.example.recipeclipper.data.local.entity.ListEntity
import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.local.entity.MealTypeEntity
import com.example.recipeclipper.data.local.entity.MenuEntity
import com.example.recipeclipper.data.local.entity.MenuEntryEntity
import com.example.recipeclipper.data.local.entity.PantryItemEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The repository and [BackupDao.import]'s write-out of a plan, over an in-memory DAO. The SQL
 * itself (insert-or-ignore keeping `addedAt`, the rollback of a failed import) is covered on a
 * device by `BackupDaoTest`, and on iOS by `BackupDaoTests` against real SQLite.
 */
class DefaultBackupRepositoryTest {

    /** Rows in memory, with the same derived columns and IGNORE rule the SQL has. */
    private open class InMemoryBackupDao : BackupDao() {
        val recipes = mutableListOf<RecipeEntity>()
        val lists = mutableListOf<ListEntity>()
        val refs = mutableListOf<RecipeListCrossRef>()
        var writes = 0
        private var nextId = 1000L

        override suspend fun allRecipes() = recipes.sortedWith(compareByDescending<RecipeEntity> { it.lastViewedAt }.thenByDescending { it.id })
        override suspend fun allLists() = lists.sortedWith(compareByDescending<ListEntity> { it.isBuiltIn }.thenBy { it.sortOrder }.thenBy { it.id })
        override suspend fun allCrossRefs() = refs.toList()
        override suspend fun existingRecipes() = recipes.map { r ->
            ExistingRecipe(r.id, r.uid, r.sourceUrl, !r.notes.isNullOrBlank(), refs.any { it.recipeId == r.id })
        }
        override suspend fun existingLists() = allLists().map { ExistingList(it.id, it.uid, it.name, it.isFavorites) }
        override suspend fun maxSortOrder() = lists.maxOfOrNull { it.sortOrder } ?: -1
        override suspend fun insertRecipe(recipe: RecipeEntity): Long {
            writes++
            val id = nextId++
            recipes += recipe.copy(id = id)
            return id
        }
        override suspend fun insertList(list: ListEntity): Long {
            writes++
            val id = nextId++
            lists += list.copy(id = id)
            return id
        }
        override suspend fun addToList(crossRef: RecipeListCrossRef) {
            writes++
            if (refs.none { it.recipeId == crossRef.recipeId && it.listId == crossRef.listId }) refs += crossRef
        }
        override suspend fun fillNote(id: Long, notes: String) {
            writes++
            val i = recipes.indexOfFirst { it.id == id }
            if (i >= 0 && recipes[i].notes.isNullOrBlank()) recipes[i] = recipes[i].copy(notes = notes)
        }

        val pantry = mutableListOf<PantryItemEntity>()
        val groceries = mutableListOf<GroceryItemEntity>()
        override suspend fun allPantry() = pantry.toList()
        override suspend fun allGroceries() = groceries.sortedBy { it.sortOrder }
        override suspend fun existingPantry() = pantry.map { ExistingPantryItem(it.uid, it.name, it.language) }
        override suspend fun existingGroceryUids() = groceries.map { it.uid }
        override suspend fun maxGroceryOrder(listId: Long) = groceries.maxOfOrNull { it.sortOrder } ?: -1
        override suspend fun insertPantryItem(item: PantryItemEntity): Long {
            writes++
            val id = nextId++
            pantry += item.copy(id = id)
            return id
        }
        override suspend fun insertGroceryItem(item: GroceryItemEntity): Long {
            writes++
            val id = nextId++
            groceries += item.copy(id = id)
            return id
        }

        val mealTypes = mutableListOf<MealTypeEntity>()
        val plan = mutableListOf<MealPlanEntryEntity>()
        override suspend fun allMealTypes() = mealTypes.sortedWith(compareBy<MealTypeEntity> { it.sortOrder }.thenBy { it.id })
        override suspend fun allPlanEntries() = plan.toList()
        override suspend fun existingMealTypes() = allMealTypes().map { ExistingMealType(it.id, it.uid, it.name, it.builtInKey) }
        override suspend fun maxMealTypeOrder() = mealTypes.maxOfOrNull { it.sortOrder } ?: -1
        override suspend fun existingPlanUids() = plan.map { it.uid }
        override suspend fun nextPlanOrder(day: Long, mealTypeId: Long) =
            (plan.filter { it.day == day && it.mealTypeId == mealTypeId }.maxOfOrNull { it.sortOrder } ?: -1) + 1
        override suspend fun insertMealType(type: MealTypeEntity): Long {
            writes++
            val id = nextId++
            mealTypes += type.copy(id = id)
            return id
        }
        override suspend fun insertPlanEntry(entry: MealPlanEntryEntity): Long {
            writes++
            val id = nextId++
            plan += entry.copy(id = id)
            return id
        }

        val menus = mutableListOf<MenuEntity>()
        val menuEntries = mutableListOf<MenuEntryEntity>()
        override suspend fun allMenus() = menus.toList()
        override suspend fun allMenuEntries() = menuEntries.toList()
        override suspend fun existingMenuUids() = menus.map { it.uid }
        override suspend fun existingMenuEntryUids() = menuEntries.map { it.uid }
        override suspend fun insertMenu(menu: MenuEntity): Long {
            writes++
            val id = nextId++
            menus += menu.copy(id = id)
            return id
        }
        override suspend fun insertMenuEntry(entry: MenuEntryEntity): Long {
            writes++
            val id = nextId++
            menuEntries += entry.copy(id = id)
            return id
        }

        val cookedPhotos = mutableListOf<com.example.recipeclipper.data.local.entity.CookedPhotoEntity>()
        override suspend fun allCookedPhotos() = cookedPhotos.toList()
        override suspend fun existingCookedPhotoUids() = cookedPhotos.map { it.uid }
        override suspend fun insertCookedPhoto(photo: com.example.recipeclipper.data.local.entity.CookedPhotoEntity): Long {
            writes++
            val id = nextId++
            cookedPhotos += photo.copy(id = id)
            return id
        }
    }

    private class RecordingLog : ErrorLog {
        val messages = mutableListOf<String>()
        override fun error(message: String, cause: Throwable) {
            messages += message
        }
    }

    private fun recipe(id: Long, uid: String, url: String, notes: String? = null, viewed: Long = 0) = RecipeEntity(
        id = id, uid = uid, sourceUrl = url, title = "R$id", imageUrl = null, ingredients = listOf("1 egg"),
        instructions = listOf("Cook."), prepTime = null, cookTime = null, totalTime = null, servings = null,
        sourceType = "BLOG", lastViewedAt = viewed, notes = notes
    )

    private fun list(id: Long, uid: String, name: String, favorites: Boolean = false, order: Int = 0) =
        ListEntity(id = id, uid = uid, name = name, isBuiltIn = true, isFavorites = favorites, sortOrder = order, createdAt = 0)

    /** The phone the shared fixture is imported into, as in BackupMergerTest. */
    private fun fixturePhone() = InMemoryBackupDao().apply {
        recipes += recipe(1, "e-soup", "https://example.com/soup")
        recipes += recipe(2, "e-bread", "https://example.com/bread", notes = "mine")
        recipes += recipe(3, "e-older", "https://example.com/older")
        lists += list(1, "l-fav", "Favorites", favorites = true, order = 0)
        lists += list(2, "l-lunch", "Lunch", order = 1)
        lists += list(10, "l-week", "Weeknight", order = 6).copy(isBuiltIn = false)
        refs += RecipeListCrossRef(2, 1, addedAt = 1)
        groceries += GroceryItemEntity(id = 7, text = "bread", language = "en", aisle = "bakery", sortOrder = 4,
            recipeId = null, plannedDay = null, updatedAt = 0, uid = "g-here")
        mealTypes += MealTypeEntity(id = 1, name = "Breakfast", builtInKey = "breakfast", sortOrder = 0, updatedAt = 0, uid = "mt-breakfast")
        mealTypes += MealTypeEntity(id = 3, name = "Supper", builtInKey = "dinner", sortOrder = 2, updatedAt = 0, uid = "mt-dinner")
        mealTypes += MealTypeEntity(id = 7, name = "Brunch", builtInKey = null, sortOrder = 4, updatedAt = 0, uid = "mt-brunch")
        plan += MealPlanEntryEntity(id = 9, day = 20720, mealTypeId = 3, recipeId = 1, servings = null, note = null,
            sortOrder = 0, updatedAt = 0, uid = "m-here")
    }

    private val clock = Clock { 1_790_000_000_000L }

    @Test fun `importing the shared fixture writes the plan onto the right rows`() = runTest {
        val dao = fixturePhone()
        val result = DefaultBackupRepository(dao, clock, RecordingLog()).import(fixture("backup-v1.json"))

        // The real history limit (50): both unlisted new recipes fit.
        assertEquals(
            BackupResult.Success(
                ImportSummary(
                    recipesAdded = 3, listsAdded = 2, recipesAlreadyHere = 2, recipesSkipped = 0,
                    pantryAdded = 3, groceriesAdded = 4, mealsAdded = 4, mealTypesAdded = 2
                )
            ),
            result
        )
        // The plan (#49): at the end of each day and meal type, on the right rows. With room in
        // history the stew comes in, so its meal does too; r-missing's is dropped, m-here is here.
        val tea = dao.mealTypes.single { it.uid == "t-tea" }
        val fakeDinner = dao.mealTypes.single { it.uid == "t-fakedinner" }
        assertEquals(listOf("Tea", "Dinner"), listOf(tea.name, fakeDinner.name))
        assertEquals(listOf(6, 5), listOf(tea.sortOrder, fakeDinner.sortOrder))
        assertTrue(dao.mealTypes.none { it.builtInKey == null && it.uid == "t-dinner" })
        val meals = dao.plan.filter { it.uid != "m-here" }
        assertEquals(listOf("m-soup", "m-old", "m-note", "m-pie"), meals.map { it.uid })
        assertEquals(
            MealPlanEntryEntity(id = meals[0].id, day = 20720, mealTypeId = 3, recipeId = 1, servings = 6, note = null,
                sortOrder = 1, updatedAt = 1789000000000, uid = "m-soup"),
            meals[0]
        )
        assertEquals(listOf(tea.id, dao.recipes.single { it.uid == "r-old" }.id), listOf(meals[1].mealTypeId, meals[1].recipeId))
        assertEquals(listOf(3L, null, "Leftovers"), listOf(meals[2].mealTypeId, meals[2].recipeId, meals[2].note))
        assertEquals(
            listOf<Any?>(fakeDinner.id, dao.recipes.single { it.uid == "r-pie" }.id, 4),
            listOf<Any?>(meals[3].mealTypeId, meals[3].recipeId, meals[3].servings)
        )
        // Pantry and groceries (#51): the rows as the file had them, groceries after the list's
        // own and pointing at the right recipe rows.
        assertEquals(listOf("p-flour", "p-salt", "p-here"), dao.pantry.map { it.uid })
        assertEquals(PantryItemEntity(id = dao.pantry[0].id, name = "Flour", quantity = "half a bag", language = "en",
            aisle = "baking", inStock = true, alwaysHave = false, purchasedDay = 20700, expiresDay = 20900,
            updatedAt = 1789000000000, uid = "p-flour"), dao.pantry[0])
        val added = dao.groceries.filter { it.uid != "g-here" }
        assertEquals(listOf("g-tomatoes", "g-apples", "g-beef", "g-milk"), added.map { it.uid })
        assertEquals(listOf(5, 6, 7, 8), added.map { it.sortOrder })
        assertEquals(listOf(1L, dao.recipes.single { it.uid == "r-pie" }.id, dao.recipes.single { it.uid == "r-old" }.id, null), added.map { it.recipeId })
        assertEquals(20720L, added[0].plannedDay)
        val pie = dao.recipes.single { it.sourceUrl == "https://example.com/pie" }
        assertEquals("r-pie", pie.uid)
        assertEquals("Use cold butter.", pie.notes)
        val salad = dao.recipes.single { it.sourceUrl == "https://example.com/salad" }
        assertTrue("a colliding uid is replaced", salad.uid != "e-bread")
        assertEquals("Less salt.\nDouble the onion.", dao.recipes.single { it.id == 1L }.notes)
        assertEquals("mine", dao.recipes.single { it.id == 2L }.notes)

        val party = dao.lists.single { it.uid == "f-party" }
        assertEquals("Party food", party.name)
        assertEquals(8, party.sortOrder)
        assertTrue(dao.lists.none { it.isFavorites && it.id != 1L })

        fun has(recipeId: Long, listId: Long) = dao.refs.any { it.recipeId == recipeId && it.listId == listId }
        assertTrue(has(1, 1))
        assertTrue(has(pie.id, party.id))
        assertTrue(has(pie.id, 1))
        assertTrue(has(2, 2))
        assertTrue(has(2, 10))
        assertTrue(has(pie.id, dao.lists.single { it.uid == "f-fakefav" }.id))
        // bread was already in Favorites at addedAt 1; nothing rewrote it
        assertEquals(1L, dao.refs.single { it.recipeId == 2L && it.listId == 1L }.addedAt)
        assertEquals(3 + 3, dao.recipes.size)
    }

    @Test fun `a file that can't be read touches nothing`() = runTest {
        val dao = fixturePhone()
        val repo = DefaultBackupRepository(dao, clock, RecordingLog())
        assertEquals(BackupResult.Failure(BackupError.NotABackup), repo.import("{}"))
        assertEquals(BackupResult.Failure(BackupError.NewerVersion(2)), repo.import(fixture("backup-v2-newer.json")))
        assertEquals(0, dao.writes)
    }

    @Test fun `a database failure is SaveFailed, logged`() = runTest {
        val log = RecordingLog()
        val dao = object : InMemoryBackupDao() {
            override suspend fun insertList(list: ListEntity): Long = throw IllegalStateException("disk full")
        }
        val result = DefaultBackupRepository(dao, clock, log).import(fixture("backup-v1.json"))
        assertEquals(BackupResult.Failure(BackupError.SaveFailed), result)
        assertEquals(listOf("import failed"), log.messages)
    }

    @Test fun `cancellation is never swallowed`() = runTest {
        val dao = object : InMemoryBackupDao() {
            override suspend fun existingRecipes(): List<ExistingRecipe> = throw CancellationException("gone")
        }
        try {
            DefaultBackupRepository(dao, clock, RecordingLog()).import(fixture("backup-v1.json"))
            fail("expected cancellation")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test fun `export carries uids, content and memberships, and imports back unchanged`() = runTest {
        val dao = fixturePhone()
        dao.recipes[0] = dao.recipes[0].copy(checkedIngredients = setOf(0), notes = "salt", imageUrl = "https://example.com/i.jpg")
        val exported = (DefaultBackupRepository(dao, clock, RecordingLog()).export() as BackupResult.Success<ExportedBackup>).value

        assertEquals(1_790_000_000_000L, exported.exportedAt)
        assertEquals(3, exported.recipeCount)
        val backup = (BackupJson.decode(exported.json) as BackupResult.Success).value
        assertEquals(listOf("e-soup", "e-bread", "e-older").sorted(), backup.recipes.map { it.id }.sorted())
        val soup = backup.recipes.single { it.id == "e-soup" }
        assertEquals(setOf(0), soup.checkedIngredients)
        assertEquals("salt", soup.notes)
        assertEquals("https://example.com/i.jpg", soup.imageUrl)
        assertEquals(listOf("l-fav", "l-lunch", "l-week"), backup.lists.map { it.id })
        assertTrue(backup.lists.single { it.id == "l-fav" }.isFavorites)
        assertEquals(1, backup.memberships.size)
        assertEquals("e-bread", backup.memberships[0].recipeId)
        assertEquals("l-fav", backup.memberships[0].listId)
        assertEquals(listOf("mt-breakfast", "mt-dinner", "mt-brunch"), backup.mealTypes.map { it.id })
        assertEquals("dinner", backup.mealTypes[1].builtInKey)
        assertEquals(listOf("m-here"), backup.mealPlan.map { it.id })
        assertEquals("mt-dinner", backup.mealPlan[0].mealTypeId)
        assertEquals("e-soup", backup.mealPlan[0].recipeId)

        // Into the same phone: everything is already here.
        val again = DefaultBackupRepository(dao, clock, RecordingLog()).import(exported.json)
        assertEquals(BackupResult.Success(ImportSummary(0, 0, 3, 0)), again)
    }

    @Test fun `an export that can't be read out is ExportFailed`() = runTest {
        val dao = object : InMemoryBackupDao() {
            override suspend fun allRecipes(): List<RecipeEntity> = throw IllegalStateException("locked")
        }
        assertEquals(BackupResult.Failure(BackupError.ExportFailed), DefaultBackupRepository(dao, clock, RecordingLog()).export())
    }

    @Test fun `a menu goes out and comes back into another phone onto the right rows`() = runTest {
        val from = fixturePhone().apply {
            menus += MenuEntity(id = 5, name = "Week A", updatedAt = 2, uid = "menu-a")
            menuEntries += MenuEntryEntity(id = 6, menuId = 5, dayOffset = 1, mealTypeId = 7, recipeId = 2, servings = 3,
                note = null, sortOrder = 0, updatedAt = 2, uid = "me-1")
        }
        val exported = (DefaultBackupRepository(from, clock, RecordingLog()).export() as BackupResult.Success<ExportedBackup>).value

        val to = InMemoryBackupDao().apply {
            mealTypes += MealTypeEntity(id = 1, name = "Dinner", builtInKey = "dinner", sortOrder = 2, updatedAt = 0, uid = "mt-other")
        }
        DefaultBackupRepository(to, clock, RecordingLog()).import(exported.json)
        val menu = to.menus.single()
        assertEquals("menu-a" to "Week A", menu.uid to menu.name)
        val entry = to.menuEntries.single()
        val brunch = to.mealTypes.single { it.uid == "mt-brunch" }
        val bread = to.recipes.single { it.uid == "e-bread" }
        assertEquals(listOf(menu.id, brunch.id, bread.id, 1L), listOf(entry.menuId, entry.mealTypeId, entry.recipeId, entry.dayOffset.toLong()))
        assertEquals("me-1", entry.uid)
    }
}
