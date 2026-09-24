package com.example.recipeclipper.ui.recipe

import android.content.res.Resources
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.RecipeShareText

/** The shared message body's words, read from `strings.xml` in the phone's language. */
fun shareLabels(resources: Resources) = RecipeShareText.Labels(
    serves = { resources.getString(R.string.share_serves, it) },
    makes = { resources.getString(R.string.share_makes, it) },
    scaled = { line, original -> resources.getString(R.string.share_scaled, line, original) },
    prep = resources.getString(R.string.label_prep),
    cook = resources.getString(R.string.label_cook),
    total = resources.getString(R.string.label_total),
    ingredients = resources.getString(R.string.share_heading_ingredients),
    instructions = resources.getString(R.string.share_heading_instructions)
)
