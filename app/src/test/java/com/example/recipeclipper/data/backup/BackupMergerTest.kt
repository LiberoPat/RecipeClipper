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

    private fun uids(): () -> String {
        var n = 0
        return { "gen-${++n}" }
    }

    private fun plan(
        backup: Backup,
        recipes: List<ExistingRecipe> = hereRecipes,
        lists: List<ExistingList> = hereLists,
        maxSortOrder: Int = 6,
        historyLimit: Int = 2
    ) = BackupMerger.plan(backup, recipes, lists, maxSortOrder, historyLimit, uids())

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

        assertEquals(
            ImportSummary(recipesAdded = 2, listsAdded = 2, recipesAlreadyHere = 2, recipesSkipped = 1),
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

        val second = plan(backup, recipes = recipes, lists = lists, maxSortOrder = 8, historyLimit = 50)

        assertTrue(second.newRecipes.isEmpty())
        assertTrue(second.newLists.isEmpty())
        assertTrue(second.noteUpdates.isEmpty())
        assertEquals(0, second.summary.recipesAdded)
        assertEquals(0, second.summary.listsAdded)
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
}
