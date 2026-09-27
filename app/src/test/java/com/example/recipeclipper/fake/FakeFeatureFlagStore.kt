package com.example.recipeclipper.fake

import com.example.recipeclipper.data.flags.FeatureFlagStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An in-memory [FeatureFlagStore]. */
class FakeFeatureFlagStore(initial: Map<String, Boolean> = emptyMap()) : FeatureFlagStore {
    private val _overrides = MutableStateFlow(initial)
    override val overrides: StateFlow<Map<String, Boolean>> = _overrides.asStateFlow()

    override fun setOverride(key: String, value: Boolean?) {
        _overrides.value = if (value == null) _overrides.value - key else _overrides.value + (key to value)
    }

    override fun clear() {
        _overrides.value = emptyMap()
    }
}
