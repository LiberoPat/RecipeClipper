package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.SampleRecipe
import com.example.recipeclipper.data.remote.RecipeSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tour's sample (#151) through [DefaultRecipeRepository]: its times are saved formatted, and
 * one saved before #179 with the file's ISO times ("PT10M") is formatted at the next launch
 * (iOS: DataRepositoryTests). Over an in-memory [com.example.recipeclipper.data.local.dao.RecipeDao].
 */
class DefaultRecipeRepositorySampleTest {

    private object NoLog : ErrorLog {
        override fun error(message: String, cause: Throwable) = Unit
    }

    private object NeverFetched : RecipeSource {
        override suspend fun fetch(url: String): ParseResult = error("the sample is never fetched")
    }

    private val dao = InMemoryRecipeDao()
    private val repository = DefaultRecipeRepository(NeverFetched, dao, Clock { 1_000L }, NoLog)

    private fun times(id: Long) = dao.rows.getValue(id).let { listOf(it.prepTime, it.cookTime, it.totalTime) }

    /** The sample as the app saved it before #179: the file's times as written. */
    private suspend fun savedBefore179(language: String, editedAt: Long? = null): Long {
        val sample = SampleRecipe.forLanguage(language)
            .copy(prepTime = "PT10M", cookTime = "PT25M", totalTime = "PT35M", editedAt = editedAt)
        return repository.addSample(sample)!!
    }

    @Test fun `the sample is saved with its times formatted`() = runTest {
        val id = repository.addSample(SampleRecipe.forLanguage("en"))!!
        assertEquals(listOf("10m", "25m", "35m"), times(id))
    }

    @Test fun `a sample saved with ISO times is formatted in its language, and nothing else changes`() = runTest {
        val id = savedBefore179("fr")
        val before = dao.rows.getValue(id)

        repository.formatSampleTimes()

        assertEquals(
            before.copy(prepTime = "10min", cookTime = "25min", totalTime = "35min"),
            dao.rows.getValue(id)
        )
    }

    @Test fun `once formatted, a launch writes nothing`() = runTest {
        savedBefore179("en")
        repository.formatSampleTimes()
        val writes = dao.writes

        repository.formatSampleTimes()

        assertEquals(writes, dao.writes)
    }

    @Test fun `an edited sample keeps the times the user saved`() = runTest {
        val id = savedBefore179("en", editedAt = 800L)

        repository.formatSampleTimes()

        assertEquals(listOf("PT10M", "PT25M", "PT35M"), times(id))
    }

    @Test fun `with no sample there is nothing to do`() = runTest {
        repository.formatSampleTimes()
        assertEquals(0, dao.writes)
    }
}
