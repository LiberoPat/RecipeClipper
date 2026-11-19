package com.example.recipeclipper.data

import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.ContentOrigin
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeDraft
import com.example.recipeclipper.data.model.RecipeLink
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeLibraryPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recipe versions (#354) through [DefaultRecipeRepository], over an in-memory
 * [com.example.recipeclipper.data.local.dao.RecipeDao]: JSON from a chat whose `isBasedOn` names a
 * recipe here is saved linked to it by uid; anything else is a plain chat recipe; deleting the
 * original clears the link and keeps the version, and an undo links it again. The SQL is
 * `RecipeDaoTest`'s, on a device.
 */
class RecipeVersionsRepositoryTest {

    private val soupUrl = "https://example.com/soup"

    private val dao = InMemoryRecipeDao()
    private val store = FakeFeatureFlagStore()
    private val flags = FeatureFlags(store, FlagRegistry.definitions, isDebug = true)
    private val library = FakeLibraryPolicy(LibraryLimit.Unlimited)
    private val source = object : RecipeSource {
        override suspend fun fetch(url: String): ParseResult = ParseResult.Success(
            Recipe(
                name = "Soup", image = null, ingredients = listOf("1 onion"), instructions = listOf("Cook."),
                prepTime = null, cookTime = null, totalTime = null, yield = "4", sourceUrl = url
            )
        )
    }
    private val repository = DefaultRecipeRepository(
        source, dao, Clock { 1_000 }, { _, _ -> }, library = library, flags = flags
    )

    private val spicier = RecipeDraft(
        name = "Spicier soup", ingredientsText = "1 onion\n1 chilli", instructionsText = "Cook.\nAdd the chilli."
    )

    private suspend fun original(): Recipe = (repository.importFromUrl(soupUrl) as ParseResult.Success).recipe

    private suspend fun uidOf(id: Long) = dao.get(id)!!.uid

    @Test fun `a chat recipe whose isBasedOn is a saved link is a version of it`() = runTest {
        val soup = original()
        val version = repository.addManual(spicier, fromChat = true, basedOn = soupUrl)!!

        assertEquals(ContentOrigin.CHAT, version.origin)
        assertEquals(uidOf(soup.id), dao.get(version.id)!!.basedOnUid)
        assertEquals(RecipeLink(soup.id, "Soup"), repository.observeBasedOn(version.id).first())
        assertEquals(listOf(RecipeLink(version.id, "Spicier soup")), repository.observeVersions(soup.id).first())
        assertNull("the original is not a version of anything", repository.observeBasedOn(soup.id).first())
    }

    @Test fun `a typed recipe is found by its manual link`() = runTest {
        val typed = repository.addManual(RecipeDraft(name = "Toast", ingredientsText = "Bread"))!!
        val version = repository.addManual(spicier, fromChat = true, basedOn = typed.sourceUrl)!!

        assertEquals(uidOf(typed.id), dao.get(version.id)!!.basedOnUid)
    }

    @Test fun `a link the AI cleaned or dirtied differently still finds the recipe`() = runTest {
        val soup = original()
        val version = repository.addManual(
            spicier, fromChat = true, basedOn = "  http://EXAMPLE.com/soup?utm_source=chat#top "
        )!!

        assertEquals(uidOf(soup.id), dao.get(version.id)!!.basedOnUid)
    }

    @Test fun `an isBasedOn that matches nothing is ignored`() = runTest {
        original()
        val version = repository.addManual(spicier, fromChat = true, basedOn = "https://example.com/stew")!!

        assertNull(dao.get(version.id)!!.basedOnUid)
        assertEquals(ContentOrigin.CHAT, version.origin)
    }

    @Test fun `no claim, a blank claim, or text that did not come from a chat links nothing`() = runTest {
        original()
        val none = repository.addManual(spicier, fromChat = true, basedOn = null)!!
        val blank = repository.addManual(spicier.copy(name = "B"), fromChat = true, basedOn = "   ")!!
        val plain = repository.addManual(spicier.copy(name = "C"), fromChat = false, basedOn = soupUrl)!!

        assertEquals(listOf(null, null, null), listOf(none, blank, plain).map { dao.get(it.id)!!.basedOnUid })
    }

    @Test fun `with the flag off, or no flags, the link is not saved`() = runTest {
        original()
        store.setOverride(Flag.RECIPE_VERSIONS.key, false)
        val off = repository.addManual(spicier, fromChat = true, basedOn = soupUrl)!!
        assertNull(dao.get(off.id)!!.basedOnUid)

        val unflagged = DefaultRecipeRepository(source, dao, Clock { 1_000 }, { _, _ -> })
        val none = unflagged.addManual(spicier.copy(name = "B"), fromChat = true, basedOn = soupUrl)!!
        assertNull(dao.get(none.id)!!.basedOnUid)
    }

    @Test fun `a version counts for the free limit like any recipe`() = runTest {
        library.limit = LibraryLimit.Capped(2)
        original()
        val first = repository.addManual(spicier, fromChat = true, basedOn = soupUrl)!!
        assertTrue(first.id > 0)

        val third = repository.addManual(spicier.copy(name = "Third"), fromChat = true, basedOn = soupUrl)!!
        assertEquals("not kept at the limit", 0L, third.id)
        assertEquals(2, dao.rows.size)
    }

    @Test fun `deleting the original keeps the version and clears its link`() = runTest {
        val soup = original()
        val version = repository.addManual(spicier, fromChat = true, basedOn = soupUrl)!!

        repository.delete(soup.id)

        val kept = dao.get(version.id)!!
        assertEquals("Spicier soup", kept.title)
        assertNull(kept.basedOnUid)
        assertNull(repository.observeBasedOn(version.id).first())
    }

    @Test fun `undoing the delete of the original links its versions again`() = runTest {
        val soup = original()
        val version = repository.addManual(spicier, fromChat = true, basedOn = soupUrl)!!
        val deleted = repository.delete(soup.id)!!
        assertNull(dao.get(version.id)!!.basedOnUid)

        assertEquals(RecipeRepository.RestoreOutcome.RESTORED, repository.restore(deleted))

        assertEquals(deleted.entity.uid, dao.get(version.id)!!.basedOnUid)
        assertEquals(listOf(RecipeLink(version.id, "Spicier soup")), repository.observeVersions(soup.id).first())
    }

    @Test fun `deleting a version leaves its original, which lists no versions`() = runTest {
        val soup = original()
        val version = repository.addManual(spicier, fromChat = true, basedOn = soupUrl)!!

        repository.delete(version.id)

        assertNotNull(dao.get(soup.id))
        assertEquals(emptyList<RecipeLink>(), repository.observeVersions(soup.id).first())
    }

    @Test fun `editing a version or its original keeps the link`() = runTest {
        val soup = original()
        val version = repository.addManual(spicier, fromChat = true, basedOn = soupUrl)!!

        repository.saveEdit(version.id, spicier.copy(name = "Spicy soup"))
        repository.saveEdit(soup.id, RecipeDraft(name = "Plain soup", ingredientsText = "1 onion"))

        assertEquals(uidOf(soup.id), dao.get(version.id)!!.basedOnUid)
        assertEquals(RecipeLink(soup.id, "Plain soup"), repository.observeBasedOn(version.id).first())
        assertEquals(listOf("Spicy soup"), repository.observeVersions(soup.id).first().map { it.title })
    }

    @Test fun `a change of a version is a version of that version`() = runTest {
        val soup = original()
        val first = repository.addManual(spicier, fromChat = true, basedOn = soupUrl)!!
        val second = repository.addManual(spicier.copy(name = "Even spicier"), fromChat = true, basedOn = first.sourceUrl)!!

        assertEquals(uidOf(first.id), dao.get(second.id)!!.basedOnUid)
        assertEquals(listOf(first.id), repository.observeVersions(soup.id).first().map { it.id })
        assertEquals(listOf(second.id), repository.observeVersions(first.id).first().map { it.id })
    }
}
