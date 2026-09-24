package com.example.recipeclipper.data

import kotlinx.coroutines.flow.Flow

/**
 * Whether the device has a usable network. An interface so neither the source nor a ViewModel
 * touches `ConnectivityManager` (a `Context` thing) directly, and so tests can drive it:
 * [AndroidConnectivity] is the real implementation, bound in `di/PlatformModule`.
 */
interface Connectivity {

    /** Right now: is there a network that claims internet access? */
    fun isOnline(): Boolean

    /** The current state on collection, then every change. Never repeats a value. */
    val online: Flow<Boolean>
}
