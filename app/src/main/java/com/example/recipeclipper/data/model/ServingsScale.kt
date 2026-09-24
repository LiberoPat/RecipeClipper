package com.example.recipeclipper.data.model

/**
 * [base] is what the recipe was written for; [target] is what the user has picked. Domain,
 * not UI: [RecipeShareText.format] used to take these as two loose `Int?` because this type
 * lived in `ui/recipe/RecipeViewModel.kt` and a data-layer file can't depend on a UI one —
 * moving it here is what let that function take the real type back.
 */
data class ServingsScale(val base: Int, val target: Int)
