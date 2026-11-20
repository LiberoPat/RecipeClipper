package com.example.recipeclipper.data.local

/**
 * What Ask an AI's "Plan my week" remembers between openings (#392): the meal types ticked last
 * time, each by [com.example.recipeclipper.data.model.MealType.key] (a seeded type's `builtInKey`,
 * else its `uid`), never by name, which the cook can change. The start day and the source are
 * not remembered: a plan never silently starts in the past. In the settings file
 * (`unit_preferences`, so it is backed up with them) under `plan_my_week_meals`, as iOS's
 * UserDefaults. Its own interface, like [UseUpLog]; [SharedPrefsAppPreferences] implements it.
 */
interface PlanChoices {
    /** The type keys ticked last time, or null when nothing was ever chosen (Dinner, then). */
    var planMealKeys: List<String>?

    companion object {
        /** Stored comma-separated: a key is a lowercase word or a uid, never holding a comma. */
        fun decode(text: String?): List<String>? =
            text?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.takeIf { it.isNotEmpty() }

        fun encode(keys: List<String>): String = keys.joinToString(",")
    }
}
