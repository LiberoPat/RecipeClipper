package com.example.recipeclipper.ui.recipe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** [OrderedWrites] on its own (#234): the recipe screen's one queue for cook progress and servings. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrderedWritesTest {

    private val log = mutableListOf<String>()

    private fun TestScope.screenScope() = CoroutineScope(StandardTestDispatcher(testScheduler))

    @Test fun `writes land one at a time, in the order they were made`() = runTest {
        val writes = OrderedWrites(screenScope())

        writes.enqueue { log += "slow start"; delay(100); log += "slow end" }
        writes.enqueue { log += "quick start"; delay(10); log += "quick end" }
        advanceUntilIdle()

        assertEquals(listOf("slow start", "slow end", "quick start", "quick end"), log)
    }

    @Test fun `a write made while the queue drains goes behind it`() = runTest {
        val writes = OrderedWrites(screenScope())

        writes.enqueue { delay(100); log += "first" }
        runCurrent()
        writes.enqueue { log += "second" }
        advanceUntilIdle()

        assertEquals(listOf("first", "second"), log)
    }

    @Test fun `writes that never got to run still land, in order, after the screen is left`() = runTest {
        val scope = screenScope()
        val writes = OrderedWrites(scope)

        writes.enqueue { log += "a" }
        writes.enqueue { log += "b" }
        scope.cancel() // the writer was launched but never ran
        writes.finishAfterClose()
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), log)
    }

    @Test fun `a write in flight when the screen is left finishes before the rest`() = runTest {
        val scope = screenScope()
        val writes = OrderedWrites(scope)

        writes.enqueue { delay(100); log += "in flight" }
        writes.enqueue { log += "queued" }
        runCurrent()
        scope.cancel()
        writes.finishAfterClose()
        advanceUntilIdle()

        assertEquals(listOf("in flight", "queued"), log)
    }

    @Test fun `leaving with nothing queued writes nothing`() = runTest {
        val scope = screenScope()
        val writes = OrderedWrites(scope)
        writes.enqueue { log += "done" }
        advanceUntilIdle()

        scope.cancel()
        writes.finishAfterClose()
        advanceUntilIdle()

        assertEquals(listOf("done"), log)
    }
}
