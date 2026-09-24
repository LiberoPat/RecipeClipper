package com.example.recipeclipper

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * `viewModelScope` posts to `Dispatchers.Main`, which doesn't exist on the JVM. Applying this
 * with `@get:Rule` installs [dispatcher] in its place for the duration of the test.
 *
 * [dispatcher] is exposed so a test can pass it to `runTest(mainDispatcherRule.dispatcher) { }`:
 * that puts the test body's own virtual clock on the *same* scheduler `viewModelScope`
 * launches onto, which the timer tests need — otherwise `advanceTimeBy` in the test and the
 * ViewModel's own `delay` calls would tick two unrelated virtual clocks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
