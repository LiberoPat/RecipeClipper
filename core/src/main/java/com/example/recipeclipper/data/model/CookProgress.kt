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
 * steps drops this (see `RecipeDao.upsert`), except when the only change is that its last steps
 * became tips (#432, [keptWhenTipsSplitOff]). The chosen servings are saved separately
 * ([Recipe.servingsTarget]) because they survive a change of steps.
 */
data class CookProgress(
    val active: Boolean = false,
    val currentStep: Int = 0,
    val doneSteps: Set<Int> = emptySet(),
    val timers: Map<Int, SavedTimer> = emptyMap()
) {
    val isEmpty: Boolean get() = this == CookProgress()

    /**
     * This progress on a recipe with only [stepCount] steps: done marks and timers on a step past
     * the last are dropped (a dropped running timer never rings: the alarm re-checks the
     * database), and the current step moves back to the last step. No steps at all, no progress.
     */
    fun clampedTo(stepCount: Int): CookProgress {
        if (stepCount <= 0) return CookProgress()
        val steps = 0 until stepCount
        return copy(
            currentStep = currentStep.coerceIn(0, stepCount - 1),
            doneSteps = doneSteps.filterTo(mutableSetOf()) { it in steps },
            timers = timers.filterKeys { it in steps }
        )
    }

    companion object {
        /**
         * True when the steps changed only because their last ones are now tips (#432): a recipe
         * saved before tips existed, its tips stored as its last steps, refreshed by a re-share.
         * Its step indexes still mean the same steps, so its cook progress stays, [clampedTo] the
         * steps left. Compared as the steps are stored, exactly; any other change is not this.
         */
        fun keptWhenTipsSplitOff(oldSteps: List<String>, newSteps: List<String>, newTips: List<String>): Boolean =
            newTips.isNotEmpty() && oldSteps == newSteps + newTips
    }
}

/** One running timer somewhere in the database: what an alarm needs to announce it. */
data class StepAlarm(
    val recipeId: Long,
    val recipeTitle: String,
    val step: Int,
    val endsAt: Long
)
