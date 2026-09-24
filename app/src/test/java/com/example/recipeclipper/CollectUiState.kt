package com.example.recipeclipper

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * `stateIn(SharingStarted.WhileSubscribed(5_000))` (used by `HomeViewModel.uiState` and
 * `HistoryViewModel.uiState`) emits nothing without a collector, so `uiState.value` would
 * otherwise sit on its initial value forever in a test and every assertion would fail
 * looking like broken logic. Call this once, right after creating the ViewModel, then
 * `advanceUntilIdle()` before asserting anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.collectEagerly(flow: StateFlow<*>) {
    backgroundScope.launch(UnconfinedTestDispatcher()) { flow.collect {} }
}
