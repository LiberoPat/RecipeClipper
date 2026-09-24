package com.example.recipeclipper.fake

import com.example.recipeclipper.data.Connectivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Connectivity a test flips by hand. Online by default, like most test runs' premise. */
class FakeConnectivity(online: Boolean = true) : Connectivity {
    val state = MutableStateFlow(online)

    override fun isOnline(): Boolean = state.value

    override val online: Flow<Boolean> get() = state
}
