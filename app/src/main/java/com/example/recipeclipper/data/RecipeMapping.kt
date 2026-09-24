package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.dao.ListRow
import com.example.recipeclipper.data.local.dao.RecipeSummaryRow
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.SourceType

// The domain Recipe and the Room entity are separate types; this is the only place that
// converts between them.

internal fun Recipe.toEntity(viewedAt: Long) = RecipeEntity(
    id = id,
    sourceUrl = sourceUrl,
    title = name,
    imageUrl = image,
    ingredients = ingredients,
    instructions = instructions,
    prepTime = prepTime,
    cookTime = cookTime,
    totalTime = totalTime,
    servings = yield,
    sourceType = sourceType.name,
    lastViewedAt = viewedAt,
    checkedIngredients = checkedIngredients,
    notes = notes,
    language = language
)

internal fun RecipeEntity.toDomain() = Recipe(
    name = title,
    image = imageUrl,
    ingredients = ingredients,
    instructions = instructions,
    prepTime = prepTime,
    cookTime = cookTime,
    totalTime = totalTime,
    yield = servings,
    sourceUrl = sourceUrl,
    sourceType = SourceType.values().firstOrNull { it.name == sourceType } ?: SourceType.BLOG,
    id = id,
    checkedIngredients = checkedIngredients,
    lastViewedAt = lastViewedAt,
    notes = notes,
    language = language
)

internal fun ListRow.toDomain() = RecipeList(
    id = id,
    name = name,
    isBuiltIn = isBuiltIn,
    isFavorites = isFavorites,
    recipeCount = recipeCount,
    containsRecipe = containsRecipe
)

internal fun RecipeSummaryRow.toDomain() = RecipeSummary(
    id = id,
    title = title,
    imageUrl = imageUrl,
    totalTime = totalTime,
    lastViewedAt = lastViewedAt,
    isSaved = isSaved
)
