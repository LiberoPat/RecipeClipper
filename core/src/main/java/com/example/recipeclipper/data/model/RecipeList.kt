package com.example.recipeclipper.data.model

/**
 * A list of recipes, as a screen needs it. The domain counterpart of `ListEntity`, kept
 * separate from it for the same reason [Recipe] is: the database's shape is not the UI's.
 *
 * [isFavorites] is carried through from the column rather than matched on the name, so it
 * survives a rename and would survive translation.
 *
 * [containsRecipe] answers "is the recipe I'm looking at already in this list?" and is
 * meaningful only where a recipe was named in the query — the save-to-list sheet. Elsewhere
 * it is false and unused.
 */
data class RecipeList(
    val id: Long,
    val name: String,
    val isBuiltIn: Boolean,
    val isFavorites: Boolean,
    val recipeCount: Int,
    val containsRecipe: Boolean = false
)
