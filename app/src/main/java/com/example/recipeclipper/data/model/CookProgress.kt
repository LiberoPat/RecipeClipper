package com.example.recipeclipper.data.model

/**
 * A step timer as it is saved. [endsAt] is the wall-clock deadline (epoch ms) while the timer
 * runs, and null while it is paused or has been reset; [remainingSeconds] is what a paused
 * timer had left. A running timer's remaining time is always recomputed from [endsAt], so it
 * keeps counting while the app is closed.
 */
data class SavedTimer(
    val totalSeconds: Int,
    val remainingSeconds: Int,
    val endsAt: Long?
)

/**
 * Where a cook stands on one recipe, persisted so closing the app mid-cook loses nothing:
 * whether cook mode is on, the current step, the steps struck off, and the step timers.
 * Step indexes point into the recipe's instructions, which is why a re-share that changes the
 * steps drops this (see `RecipeDao.upsert`). The chosen servings are saved separately
 * ([Recipe.servingsTarget]) because they survive a change of steps.
 */
data class CookProgress(
    val active: Boolean = false,
    val currentStep: Int = 0,
    val doneSteps: Set<Int> = emptySet(),
    val timers: Map<Int, SavedTimer> = emptyMap()
) {
    val isEmpty: Boolean get() = this == CookProgress()
}

/** One running timer somewhere in the database: what an alarm needs to announce it. */
data class StepAlarm(
    val recipeId: Long,
    val recipeTitle: String,
    val step: Int,
    val endsAt: Long
)
