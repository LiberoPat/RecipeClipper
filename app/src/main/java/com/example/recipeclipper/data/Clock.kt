package com.example.recipeclipper.data

/**
 * The current wall-clock time, as a seam: [RecipeRepository] and `RecipeViewModel` both used
 * to call `System.currentTimeMillis()` inline (the ViewModel held it as a private
 * `() -> Long` field). Injecting this instead lets a test line up wall-clock deadlines with
 * its dispatcher's virtual time, which is impossible while either reads the real clock.
 *
 * A `fun interface` rather than injecting `() -> Long` directly: Hilt would bind a bare
 * function type as `Function0<Long>`, which needs a qualifier to disambiguate and reads worse
 * at call sites than the named `clock.now()`.
 */
fun interface Clock {
    fun now(): Long
}
