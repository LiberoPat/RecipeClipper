package com.example.recipeclipper.data.remote

/**
 * Which app an import reads (#382 Paprika, #390 Recipe Keeper). Both come in as [PaprikaRecipe]s
 * through the same repository, merger and summary; the source only decides how a file is read
 * (its archive) and the prefix of a recipe's own key when it has no link ([keyPrefix], so
 * `manual:paprika-…` and `manual:recipekeeper-…` can never meet).
 */
enum class ImportSource(val keyPrefix: String) {
    PAPRIKA("paprika"),
    RECIPE_KEEPER("recipekeeper")
}
