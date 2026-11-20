package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.remote.RecipeSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Deleting several recipes at once (#377) through [DefaultRecipeRepository], over an in-memory DAO:
 * every one goes, what each took is returned for one undo, a recipe already gone is skipped, and
 * `delete` is the same path with one id. (The one-transaction guarantee is Room's `@Transaction`;
 * `RecipeDaoTest` runs it on a device.)
 */
class DefaultRecipeRepositoryBulkDeleteTest {

    private object NoLog : ErrorLog {
        override fun error(message: String, cause: Throwable) = Unit
    }

    private object NeverFetched : RecipeSource {
        override suspend fun fetch(url: String): ParseResult = error("nothing is fetched")
    }

    private val dao = InMemoryRecipeDao()
    private val repository = DefaultRecipeRepository(NeverFetched, dao, Clock { 1_000L }, NoLog)

    private fun recipe(id: Long, title: String, basedOnUid: String? = null) = RecipeEntity(
        id = id, sourceUrl = "https://example.com/$id", title = title, imageUrl = null,
        ingredients = listOf("1 egg"), instructions = listOf("Cook."),
        prepTime = null, cookTime = null, totalTime = null, servings = null, sourceType = "BLOG", lastViewedAt = 0L,
        basedOnUid = basedOnUid
    ).also { dao.rows[id] = it }

    @Test fun `every recipe asked for is deleted, and what each took is returned in order`() = runTest {
        val a = recipe(1, "A")
        val b = recipe(2, "B")
        val c = recipe(3, "C")

        val removed = repository.deleteAll(listOf(3, 1))

        assertEquals(listOf(c, a), removed.map { it.entity })
        assertEquals(setOf(2L), dao.rows.keys)
        assertEquals(b, dao.rows.getValue(2))
    }

    @Test fun `an id already gone is skipped, and the rest still go`() = runTest {
        recipe(1, "A")
        recipe(2, "B")

        val removed = repository.deleteAll(listOf(1, 99, 2))

        assertEquals(listOf(1L, 2L), removed.map { it.entity.id })
        assertEquals(emptySet<Long>(), dao.rows.keys)
    }

    @Test fun `delete is the one-id case`() = runTest {
        recipe(1, "A")
        recipe(2, "B")

        assertEquals(1L, repository.delete(1)?.entity?.id)
        assertNull(repository.delete(1))
        assertEquals(setOf(2L), dao.rows.keys)
    }

    @Test fun `deleting an original and its version together keeps nothing dangling`() = runTest {
        val original = recipe(1, "Pancakes")
        val version = recipe(2, "Vegan pancakes", basedOnUid = original.uid)
        val other = recipe(3, "Soup", basedOnUid = original.uid)

        // The original and one of its versions go; the other version stays, unlinked.
        val removed = repository.deleteAll(listOf(1, 2))

        assertEquals(listOf(1L, 2L), removed.map { it.entity.id })
        assertEquals(listOf(2L, 3L), removed.first().versionIds)
        assertNull(dao.rows.getValue(3).basedOnUid)
        assertEquals(other.copy(basedOnUid = null), dao.rows.getValue(3))
        assertEquals(setOf(3L), dao.rows.keys)
        assertEquals(version.title, removed[1].entity.title)
    }
}
