package com.example.recipeclipper.ui.askai

import android.content.res.Resources
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.AskAiPrompt
import java.text.DateFormatSymbols

/**
 * The prompt's words (#351), read from `strings.xml` in the phone's language: the prompt is
 * written in the UI language and asks for the recipe in it. The weekday names are the locale's
 * own (1 = Sunday, as [DateFormatSymbols.getWeekdays] and `PlanDays` number them).
 */
fun askAiWords(resources: Resources): AskAiPrompt.Words {
    val weekdays = DateFormatSymbols.getInstance(resources.configuration.locales[0]).weekdays
    return AskAiPrompt.Words(
        language = resources.getString(R.string.ai_prompt_language),
        weekday = { weekdays[it] },
        wantToCook = { resources.getString(R.string.ai_prompt_want_to_cook, it) },
        changeThis = { resources.getString(R.string.ai_prompt_change_this, it) },
        recipeBelow = resources.getString(R.string.ai_prompt_recipe_below),
        whatCanIMake = resources.getString(R.string.ai_prompt_what_can_i_make),
        useUpFirst = { resources.getString(R.string.ai_prompt_use_up_first, it) },
        inPantry = { resources.getString(R.string.ai_prompt_in_pantry, it) },
        planIntro = resources.getString(R.string.ai_prompt_plan_intro),
        favourites = { resources.getString(R.string.ai_prompt_favourites, it) },
        cookedLately = { resources.getString(R.string.ai_prompt_cooked_lately, it) },
        alsoWish = { resources.getString(R.string.ai_prompt_also, it) },
        replyOne = resources.getString(R.string.ai_prompt_reply_one),
        replyPlan = resources.getString(R.string.ai_prompt_reply_plan, AskAiPrompt.DAY_FIELD),
        replyChanged = resources.getString(R.string.ai_prompt_reply_changed),
        recipeFields = resources.getString(R.string.ai_prompt_recipe_fields),
        unitsAsWritten = resources.getString(R.string.ai_prompt_units_as_written),
        unitsMetric = resources.getString(R.string.ai_prompt_units_metric),
        unitsOunces = resources.getString(R.string.ai_prompt_units_ounces),
        temperatureCelsius = resources.getString(R.string.ai_prompt_temperature_c),
        temperatureFahrenheit = resources.getString(R.string.ai_prompt_temperature_f),
        writeIn = { resources.getString(R.string.ai_prompt_write_in, it) },
        noteOutside = resources.getString(R.string.ai_prompt_note_outside)
    )
}
