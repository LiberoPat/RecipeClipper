package com.example.recipeclipper.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge rules (#26), first against the shared fixture — the same existing state and the same
 * expected plan as the iOS `BackupMergerTests` — then one rule at a time.
 */
class BackupMergerTest {

    /** The phone the fixture is imported into. Mirrored exactly in the iOS test. */
    private val hereRecipes = listOf(
        ExistingRecipe(id = 1, uid = "e-soup", sourceUrl = "https://example.com/soup", hasNotes = false, isListed = false),
        ExistingRecipe(id = 2, uid = "e-bread", sourceUrl = "https://example.com/bread", hasNotes = true, isListed = true),
        ExistingRecipe(id = 3, uid = "e-older", sourceUrl = "https://example.com/older", hasNotes = false, isListed = false)
    )
    private val hereLists = listOf(
        ExistingList(id = 1, uid = "l-fav", name = "Favorites", isFavorites = true),
        ExistingList(id = 2, uid = "l-lunch", name = "Lunch", isFavorites = false),
        ExistingList(id = 10, uid = "l-week", name = "Weeknight", isFavorites = false)
    )
    private val herePantry = listOf(
        ExistingPantryItem(uid = "p-here", name = "Butter", language = "en"),
        ExistingPantryItem(uid = "x-salt", name = "Salt", language = "en")
    )
    private val hereGroceries = setOf("g-here")
    private val hereMealTypes = listOf(
        ExistingMealType(id = 1, uid = "mt-breakfast", name = "Breakfast", builtInKey = "breakfast"),
        ExistingMealType(id = 4, uid = "mt-dinner", name = "Supper", builtInKey = "dinner"),
        ExistingMealType(id = 7, uid = "mt-brunch", name = "Brunch", builtInKey = null)
    )
    private val herePlan = setOf("m-here")
    private val today = 20720L

    private fun uids(): () -> String {
        var n = 0
        return { "gen-${++n}" }
    }

    private fun plan(
        backup: Backup,
        recipes: List<ExistingRecipe> = hereRecipes,
        lists: List<ExistingList> = hereLists,
        maxSortOrder: Int = 6,
        historyLimit: Int = 2,
        pantry: List<ExistingPantryItem> = herePantry,
        groceries: Set<String> = hereGroceries,
        mealTypes: List<ExistingMealType> = hereMealTypes,
        maxMealTypeSortOrder: Int = 4,
        planUids: Set<String> = herePlan,
        menuUids: Set<String> = emptySet(),
        menuEntryUids: Set<String> = emptySet()
    ) = BackupMerger.plan(
        backup, recipes, lists, maxSortOrder, historyLimit, uids(), pantry, groceries,
        mealTypes, maxMealTypeSortOrder, planUids, today, menuUids, menuEntryUids
    )

    @Test fun `the shared fixture merges into the expected plan`() {
        val plan = plan(decodeOrFail(fixture("backup-v1.json")))

        // r-pie (listed) and the salad (unlisted, newest) are new. The salad's file id collides
        // with a recipe uid here, so it gets a fresh one. r-old is unlisted and doesn't fit.
        assertEquals(listOf("r-pie", "gen-1"), plan.newRecipes.map { it.id })
        val pie = plan.newRecipes[0]
        assertEquals("https://example.com/pie", pie.sourceUrl)
        assertEquals("Apple Pie", pie.title) // the first copy in the file wins
        assertEquals(setOf(0), pie.checkedIngredients) // 5 is past the last ingredient
        assertEquals("Use cold butter.", pie.notes) // the duplicate's note fills an empty one
        val salad = plan.newRecipes[1]
        assertEquals("https://example.com/salad", salad.sourceUrl)
        assertEquals("BLOG", salad.sourceType) // unknown source type
        assertEquals(null, salad.notes) // blank note is no note
        assertEquals(1789000000900L, salad.lastViewedAt)

        // The soup here had no note and gets the imported one; the bread keeps its own.
        assertEquals(listOf(NoteUpdate(1, "Less salt.\nDouble the onion.")), plan.noteUpdates)

        // "Favorites" (not isFavorites) is a new user list; the two "Party food"s are one list.
        assertEquals(
            listOf(
                NewList("f-fakefav", "Favorites", isBuiltIn = false, sortOrder = 7, createdAt = 1700000000200L),
                NewList("f-party", "Party food", isBuiltIn = false, sortOrder = 8, createdAt = 1700000000300L)
            ),
            plan.newLists
        )

        val e = { id: Long -> Target.Existing(id) }
        val n = { uid: String -> Target.New(uid) }
        assertEquals(
            listOf(
                PlannedMembership(e(1), e(1), 10),          // soup -> Faves = Favorites by flag
                PlannedMembership(n("r-pie"), n("f-party"), 11),
                // pie-dup -> PARTY FOOD is the same pair again: dropped
                PlannedMembership(n("r-pie"), e(1), 13),
                PlannedMembership(e(2), e(2), 14),          // bread -> Lunch by name
                PlannedMembership(e(2), e(10), 15),         // bread -> "Midweek" by uid = Weeknight
                PlannedMembership(n("r-pie"), n("f-fakefav"), 16)
            ),
            plan.memberships
        )

        // Pantry: p-here's uid and "salt" are here, " FLOUR " repeats "Flour": only flour comes in, trimmed.
        assertEquals(listOf("p-flour"), plan.newPantry.map { it.id })
        assertEquals("Flour", plan.newPantry[0].name)
        assertEquals("half a bag", plan.newPantry[0].quantity)

        // Groceries: by uid only. The tomatoes keep the soup here, the apples the pie being
        // written; the beef's recipe was skipped for history and the milk's was never in the file.
        assertEquals(
            listOf("g-tomatoes" to e(1), "g-apples" to n("r-pie"), "g-beef" to null, "g-milk" to null),
            plan.newGroceries.map { it.item.id to it.recipe }
        )

        // Meal types: the seeded Dinner by key (renamed "Supper" here), " brunch " by name; a
        // user type called "Dinner" never joins the seeded one, so it and "Tea" are created.
        assertEquals(
            listOf(NewMealType("t-fakedinner", "Dinner", 5, 0), NewMealType("t-tea", "Tea", 6, 1789000000000L)),
            plan.newMealTypes
        )

        // The plan: the soup's dinner keeps the soup here; the note has no meal type in the file,
        // so it's Dinner; the pie's (planned through its duplicate) keeps the pie being written.
        // The stew was skipped for history and r-missing was never in the file: both dropped.
        // m-here is here already.
        assertEquals(
            listOf(
                Triple("m-soup", e(4), e(1)),
                Triple("m-note", e(4), null),
                Triple("m-pie", n("t-fakedinner"), n("r-pie"))
            ),
            plan.newPlanEntries.map { Triple(it.entry.id, it.mealType, it.recipe) }
        )
        assertEquals(6, plan.newPlanEntries[0].entry.servings)
        assertEquals("Leftovers", plan.newPlanEntries[1].entry.note)

        assertEquals(
            ImportSummary(
                recipesAdded = 2, listsAdded = 2, recipesAlreadyHere = 2, recipesSkipped = 1,
                pantryAdded = 1, groceriesAdded = 4, mealsAdded = 3, mealTypesAdded = 2
            ),
            plan.summary
        )
    }

    @Test fun `importing the same file twice adds nothing the second time`() {
        val backup = decodeOrFail(fixture("backup-v1.json"))
        val first = plan(backup, historyLimit = 50)
        // The phone after the first import: every new recipe and list is now "here".
        var nextId = 100L
        val recipes = hereRecipes.map { if (it.id == 1L) it.copy(isListed = true, hasNotes = true) else it } +
            first.newRecipes.map { ExistingRecipe(nextId++, it.id, it.sourceUrl, it.notes != null, isListed = true) }
        val lists = hereLists + first.newLists.map { ExistingList(nextId++, it.uid, it.name, false) }

        val pantry = herePantry + first.newPantry.map { ExistingPantryItem(it.id, it.name, it.language) }
        val groceries = hereGroceries + first.newGroceries.map { it.item.id }

        var typeId = 200L
        val mealTypes = hereMealTypes + first.newMealTypes.map { ExistingMealType(typeId++, it.uid, it.name, null) }
        val planUids = herePlan + first.newPlanEntries.map { it.entry.id }

        val second = plan(
            backup, recipes = recipes, lists = lists, maxSortOrder = 8, historyLimit = 50, pantry = pantry,
            groceries = groceries, mealTypes = mealTypes, maxMealTypeSortOrder = 6, planUids = planUids
        )

        assertTrue(second.newRecipes.isEmpty())
        assertTrue(second.newLists.isEmpty())
        assertTrue(second.noteUpdates.isEmpty())
        assertEquals(0, second.summary.recipesAdded)
        assertEquals(0, second.summary.listsAdded)
        assertTrue(second.newPantry.isEmpty())
        assertTrue(second.newGroceries.isEmpty())
        assertTrue(second.newMealTypes.isEmpty())
        assertTrue(second.newPlanEntries.isEmpty())
    }

    @Test fun `a recipe planned for today or later comes in like a listed one`() {
        fun recipe(id: String) = BackupRecipe(
            id, "https://example.com/$id", "BLOG", id, null, listOf("1 egg"), listOf("Cook."),
            null, null, null, null, lastViewedAt = 5, checkedIngredients = emptySet(), notes = null
        )
        fun entry(id: String, day: Long, recipeId: String) = BackupPlanEntry(id, day, null, recipeId, null, null, 0, 0)
        val backup = Backup(
            0, listOf(recipe("r-today"), recipe("r-past")), emptyList(), emptyList(),
            mealPlan = listOf(entry("m-today", today, "r-today"), entry("m-past", today - 1, "r-past"))
        )
        // No free place in history: the recipe planned for today still comes in; the past one doesn't.
        val plan = plan(backup, recipes = emptyList(), historyLimit = 0)
        assertEquals(listOf("r-today"), plan.newRecipes.map { it.id })
        assertEquals(1, plan.summary.recipesSkipped)
        assertEquals(
            listOf(Triple("m-today", Target.Existing(4) as Target, Target.New("r-today") as Target?)),
            plan.newPlanEntries.map { Triple(it.entry.id, it.mealType, it.recipe) }
        )
    }

    @Test fun `a pantry name here in another language is a different item`() {
        val backup = Backup(
            0, emptyList(), emptyList(), emptyList(),
            pantry = listOf(BackupPantryItem("p", "Butter", null, "de", "dairy", true, false, null, null, 0))
        )
        assertEquals(listOf("p"), plan(backup, recipes = emptyList()).newPantry.map { it.id })
    }

    @Test fun `favorites maps by the flag whatever either list is called`() {
        val backup = Backup(0, emptyList(), listOf(BackupList("x", "Mes favoris", true, true, 0, 0)), emptyList())
        val renamedHere = listOf(ExistingList(5, "l-fav", "Keepers", isFavorites = true))
        val plan = plan(backup, recipes = emptyList(), lists = renamedHere)
        assertTrue(plan.newLists.isEmpty())
    }

    @Test fun `no list here ever joins Favorites by name or uid`() {
        val backup = Backup(
            0, emptyList(),
            listOf(BackupList("l-fav", "favorites", isFavorites = false, isBuiltIn = false, sortOrder = 0, createdAt = 0)),
            emptyList()
        )
        val plan = plan(backup, recipes = emptyList())
        assertEquals(1, plan.newLists.size)
        assertEquals("gen-1", plan.newLists[0].uid) // l-fav is taken here
        assertEquals("favorites", plan.newLists[0].name)
    }

    @Test fun `list names match trimmed and case-insensitively`() {
        val backup = Backup(0, emptyList(), listOf(BackupList("new", "  WEEKNIGHT ", false, false, 0, 0)), emptyList())
        assertTrue(plan(backup, recipes = emptyList()).newLists.isEmpty())
    }

    @Test fun `unlisted recipes only fill free places in history and never push one out`() {
        val here = (1..48L).map { ExistingRecipe(it, "u$it", "https://h.example/$it", false, isListed = false) }
        val incoming = (1..5).map { i ->
            BackupRecipe("f$i", "https://f.example/$i", "BLOG", "F$i", null, emptyList(), emptyList(),
                null, null, null, null, lastViewedAt = i * 10L, checkedIngredients = emptySet(), notes = null)
        }
        val plan = BackupMerger.plan(Backup(0, incoming, emptyList(), emptyList()), here, emptyList(), 0, 50, uids())
        assertEquals(listOf("f4", "f5"), plan.newRecipes.map { it.id }.sorted()) // the two most recent
        assertEquals(3, plan.summary.recipesSkipped)
    }

    @Test fun `on the free tier every recipe here counts, listed ones too (#107)`() {
        // 15 here, 10 of them listed: only 5 places are free under 20, not 20 - 5.
        val here = (1..15L).map { ExistingRecipe(it, "u$it", "https://h.example/$it", false, isListed = it <= 10) }
        val incoming = (1..8).map { i ->
            BackupRecipe("f$i", "https://f.example/$i", "BLOG", "F$i", null, emptyList(), emptyList(),
                null, null, null, null, lastViewedAt = i * 10L, checkedIngredients = emptySet(), notes = null)
        }
        val plan = BackupMerger.plan(
            Backup(0, incoming, emptyList(), emptyList()), here, emptyList(), 0, 20, uids(), countsEveryRecipe = true
        )
        assertEquals(listOf("f4", "f5", "f6", "f7", "f8"), plan.newRecipes.map { it.id }.sorted())
        assertEquals(3, plan.summary.recipesSkipped)
        assertEquals(20, plan.summary.freeLimit)
    }

    @Test fun `a library over the free limit takes no unlisted recipes, and loses none (#107)`() {
        val here = (1..30L).map { ExistingRecipe(it, "u$it", "https://h.example/$it", false, isListed = false) }
        val incoming = listOf(
            BackupRecipe("f1", "https://f.example/1", "BLOG", "F1", null, emptyList(), emptyList(),
                null, null, null, null, 0, emptySet(), null)
        )
        val plan = BackupMerger.plan(
            Backup(0, incoming, emptyList(), emptyList()), here, emptyList(), 0, 20, uids(), countsEveryRecipe = true
        )
        assertTrue(plan.newRecipes.isEmpty())
        assertEquals(1, plan.summary.recipesSkipped)
    }

    @Test fun `recipes in a list always come in, even when history is full`() {
        val here = (1..50L).map { ExistingRecipe(it, "u$it", "https://h.example/$it", false, isListed = false) }
        val incoming = listOf(
            BackupRecipe("f1", "https://f.example/1", "BLOG", "F1", null, emptyList(), emptyList(),
                null, null, null, null, 0, emptySet(), null)
        )
        val backup = Backup(0, incoming, listOf(BackupList("L", "Dinner", false, true, 0, 0)), listOf(BackupMembership("f1", "L", 5)))
        val plan = BackupMerger.plan(backup, here, emptyList(), 0, 50, uids())
        assertEquals(listOf("f1"), plan.newRecipes.map { it.id })
        assertEquals(0, plan.summary.recipesSkipped)
    }

    @Test fun `typed-in recipes always come in, even when history is full`() {
        val here = (1..50L).map { ExistingRecipe(it, "u$it", "https://h.example/$it", false, isListed = false) }
        val incoming = listOf(
            BackupRecipe("f1", "manual:abc", "BLOG", "F1", null, emptyList(), listOf("Mix."),
                null, null, null, null, 0, emptySet(), null, contentOrigin = "MANUAL")
        )
        val plan = BackupMerger.plan(Backup(0, incoming, emptyList(), emptyList()), here, emptyList(), 0, 50, uids())
        assertEquals(listOf("f1"), plan.newRecipes.map { it.id })
        assertEquals(0, plan.summary.recipesSkipped)
    }

    @Test fun `a recipe here that the file puts in a list frees its place in history`() {
        val here = (1..50L).map { ExistingRecipe(it, "u$it", "https://h.example/$it", false, isListed = false) }
        val incoming = listOf(
            BackupRecipe("a", "https://h.example/1", "BLOG", "Here", null, emptyList(), emptyList(), null, null, null, null, 0, emptySet(), null),
            BackupRecipe("b", "https://f.example/new", "BLOG", "New", null, emptyList(), emptyList(), null, null, null, null, 0, emptySet(), null)
        )
        val backup = Backup(0, incoming, listOf(BackupList("L", "Dinner", false, true, 0, 0)), listOf(BackupMembership("a", "L", 5)))
        val plan = BackupMerger.plan(backup, here, emptyList(), 0, 50, uids())
        assertEquals(listOf("b"), plan.newRecipes.map { it.id })
    }

    @Test fun `an existing note is never replaced`() {
        val incoming = listOf(
            BackupRecipe("a", "https://example.com/bread", "BLOG", "Bread", null, emptyList(), emptyList(),
                null, null, null, null, 0, emptySet(), "theirs")
        )
        val plan = plan(Backup(0, incoming, emptyList(), emptyList()))
        assertTrue(plan.noteUpdates.isEmpty())
        assertEquals(1, plan.summary.recipesAlreadyHere)
    }

    // --- Menus (#52)

    private fun menuRecipe(id: String) = BackupRecipe(
        id, "https://example.com/$id", "BLOG", id, null, listOf("1 egg"), listOf("Cook."),
        null, null, null, null, lastViewedAt = 5, checkedIngredients = emptySet(), notes = null
    )

    private fun menuEntry(id: String, menuId: String, recipeId: String?, note: String? = null, type: String? = null) =
        BackupMenuEntry(id, menuId, 2, type, recipeId, if (recipeId != null) 4 else null, note, 0, 9)

    @Test fun `a menu comes in with its meals, and its recipes like listed ones`() {
        val backup = Backup(
            0, listOf(menuRecipe("r-menu")), emptyList(), emptyList(),
            mealTypes = listOf(BackupMealType("mt-brunch", "Brunch", null, 4, 0)),
            menus = listOf(BackupMenu("menu-a", " Week A ", 9)),
            menuEntries = listOf(
                menuEntry("me-1", "menu-a", "r-menu", type = "mt-brunch"),
                menuEntry("me-2", "menu-a", null, note = "Leftovers")
            )
        )
        // No free place in history: the menu's recipe still comes in.
        val plan = plan(backup, recipes = emptyList(), historyLimit = 0)
        assertEquals(listOf("r-menu"), plan.newRecipes.map { it.id })
        assertEquals(1, plan.summary.menusAdded)
        val menu = plan.newMenus.single()
        assertEquals("Week A", menu.menu.name)
        assertEquals(
            listOf(Triple("me-1", Target.Existing(7) as Target, Target.New("r-menu") as Target?),
                Triple("me-2", Target.Existing(4) as Target, null)),
            menu.entries.map { Triple(it.entry.id, it.mealType, it.recipe) }
        )
        assertEquals(4, menu.entries[0].entry.servings)
        assertEquals("Leftovers", menu.entries[1].entry.note)
    }

    @Test fun `a menu already here is left as it is`() {
        val backup = Backup(
            0, emptyList(), emptyList(), emptyList(),
            menus = listOf(BackupMenu("menu-a", "Renamed there", 9)),
            menuEntries = listOf(menuEntry("me-1", "menu-a", null, note = "Soup"))
        )
        val plan = plan(backup, menuUids = setOf("menu-a"), menuEntryUids = setOf("me-1"))
        assertTrue(plan.newMenus.isEmpty())
        assertEquals(0, plan.summary.menusAdded)
    }

    @Test fun `a menu whose meals can't come in is dropped, and a taken meal uid is replaced`() {
        val backup = Backup(
            0, emptyList(), emptyList(), emptyList(),
            menus = listOf(BackupMenu("menu-a", "A", 9), BackupMenu("menu-b", "B", 9)),
            menuEntries = listOf(
                menuEntry("me-1", "menu-a", "r-missing"),
                menuEntry("me-2", "menu-b", null, note = "Soup")
            )
        )
        val plan = plan(backup, menuEntryUids = setOf("me-2"))
        assertEquals(listOf("menu-b"), plan.newMenus.map { it.menu.id })
        assertEquals("gen-1", plan.newMenus.single().entries.single().entry.id)
    }
}
