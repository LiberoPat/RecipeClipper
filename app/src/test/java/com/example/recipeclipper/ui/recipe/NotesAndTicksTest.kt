package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** [NotesAndTicks] on its own (#234): ticks written as they change, the note once typing pauses. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesAndTicksTest {

    private val repository = FakeRecipeRepository()

    @Test fun `every tick is written as it changes`() = runTest {
        val writes = NotesAndTicks(CoroutineScope(StandardTestDispatcher(testScheduler)), repository, DELAY)

        writes.ticked(5L, setOf(1))
        writes.ticked(5L, setOf(1, 2))
        advanceUntilIdle()

        assertEquals(listOf(5L to setOf(1), 5L to setOf(1, 2)), repository.setCheckedCalls)
    }

    @Test fun `the note is written once, after typing pauses`() = runTest {
        val writes = NotesAndTicks(CoroutineScope(StandardTestDispatcher(testScheduler)), repository, DELAY)

        writes.noteChanged(5L, "N")
        advanceTimeBy(DELAY - 1)
        writes.noteChanged(5L, "Ne")
        advanceTimeBy(DELAY - 1)
        runCurrent()
        assertEquals(emptyList<Pair<Long, String>>(), repository.setNotesCalls)

        advanceTimeBy(2)
        assertEquals(listOf(5L to "Ne"), repository.setNotesCalls)
    }

    @Test fun `a note still waiting is written when the screen is left`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val writes = NotesAndTicks(scope, repository, DELAY)

        writes.noteChanged(5L, "Less salt")
        scope.cancel()
        writes.flushAfterClose(5L)
        advanceUntilIdle()

        assertEquals(listOf(5L to "Less salt"), repository.setNotesCalls)
    }

    @Test fun `a note already written is not written again on leaving`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val writes = NotesAndTicks(scope, repository, DELAY)

        writes.noteChanged(5L, "Less salt")
        advanceUntilIdle()
        scope.cancel()
        writes.flushAfterClose(5L)
        advanceUntilIdle()

        assertEquals(listOf(5L to "Less salt"), repository.setNotesCalls)
    }

    @Test fun `leaving with no recipe on screen writes nothing`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val writes = NotesAndTicks(scope, repository, DELAY)

        writes.noteChanged(5L, "Less salt")
        scope.cancel()
        writes.flushAfterClose(null)
        advanceUntilIdle()

        assertEquals(emptyList<Pair<Long, String>>(), repository.setNotesCalls)
    }

    private companion object {
        const val DELAY = 500L
    }
}
