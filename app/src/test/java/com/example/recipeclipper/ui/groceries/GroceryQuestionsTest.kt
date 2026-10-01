package com.example.recipeclipper.ui.groceries

import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.DecisionQuestion
import com.example.recipeclipper.data.model.Decisions
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.fake.FakeDecisionRepository
import com.example.recipeclipper.fake.FakeGroceryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GroceryQuestions] on its own (#104, #234): the model's questions about the list, each asked once. */
@OptIn(ExperimentalCoroutinesApi::class)
class GroceryQuestionsTest {

    private val repository = FakeGroceryRepository()
    private val furikake = DecisionQuestion.aisle("furikake", "en")

    private fun TestScope.questions(decisions: FakeDecisionRepository) =
        GroceryQuestions(CoroutineScope(StandardTestDispatcher(testScheduler)), decisions, repository) { repository.items.value }

    @Test fun `an item in Other is filed where the model decided, and asked about once`() = runTest {
        repository.add(listOf(NewGroceryLine("2 tbsp furikake", "en")))
        val decisions = FakeDecisionRepository(mapOf(furikake to "spices"))
        val questions = questions(decisions)

        questions.ask(repository.items.value, Decisions.NONE)
        advanceUntilIdle()
        questions.ask(repository.items.value, Decisions.NONE)
        advanceUntilIdle()

        assertEquals(Aisle.SPICES, repository.items.value.single().aisle)
        assertEquals(1, decisions.asked.count { it == furikake })
    }

    @Test fun `a ticked item is not asked about`() = runTest {
        repository.add(listOf(NewGroceryLine("2 tbsp furikake", "en")))
        repository.setChecked(repository.items.value.map { it.id }, true)
        val decisions = FakeDecisionRepository(mapOf(furikake to "spices"))

        questions(decisions).ask(repository.items.value, Decisions.NONE)
        advanceUntilIdle()

        assertTrue(decisions.asked.isEmpty())
        assertEquals(Aisle.OTHER, repository.items.value.single().aisle)
    }

    @Test fun `an item with no answer stays in Other`() = runTest {
        repository.add(listOf(NewGroceryLine("1 jar gochugaru flakes", "en")))

        questions(FakeDecisionRepository()).ask(repository.items.value, Decisions.NONE)
        advanceUntilIdle()

        assertEquals(Aisle.OTHER, repository.items.value.single().aisle)
    }
}
