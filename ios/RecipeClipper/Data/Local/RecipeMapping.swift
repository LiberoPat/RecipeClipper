import Foundation

// The domain types and the database rows are separate; this is the only place that converts
// between them (Android's RecipeMapping.kt).

extension Recipe {
    func toRecord(viewedAt: Int64) -> RecipeRecord {
        RecipeRecord(
            id: id,
            sourceUrl: sourceUrl,
            title: name,
            imageUrl: image,
            ingredients: ingredients,
            instructions: instructions,
            prepTime: prepTime,
            cookTime: cookTime,
            totalTime: totalTime,
            servings: yield,
            sourceType: sourceType.rawValue,
            lastViewedAt: viewedAt,
            checkedIngredients: checkedIngredients,
            notes: notes,
            language: language,
            cookState: CookStateJSON.encode(cook),
            servingsTarget: servingsTarget
        )
    }
}

extension RecipeRecord {
    func toDomain() -> Recipe {
        Recipe(
            name: title,
            image: imageUrl,
            ingredients: ingredients,
            instructions: instructions,
            prepTime: prepTime,
            cookTime: cookTime,
            totalTime: totalTime,
            yield: servings,
            sourceUrl: sourceUrl,
            sourceType: SourceType(rawValue: sourceType) ?? .blog,
            id: id,
            checkedIngredients: checkedIngredients,
            lastViewedAt: lastViewedAt,
            notes: notes,
            language: language,
            cook: CookStateJSON.decode(cookState),
            servingsTarget: servingsTarget
        )
    }
}

extension ListRecord {
    func toDomain() -> RecipeList {
        RecipeList(
            id: id,
            name: name,
            isBuiltIn: isBuiltIn,
            isFavorites: isFavorites,
            recipeCount: recipeCount,
            containsRecipe: containsRecipe
        )
    }
}

extension RecipeSummaryRecord {
    func toDomain() -> RecipeSummary {
        RecipeSummary(
            id: id,
            title: title,
            imageUrl: imageUrl,
            totalTime: totalTime,
            lastViewedAt: lastViewedAt,
            isSaved: isSaved
        )
    }
}
