package com.example.recipeclipper.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recipes",
    indices = [Index(value = ["sourceUrl"], unique = true)]
)
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUrl: String,          // unique: re-sharing a link upserts instead of duplicating
    val title: String,
    val imageUrl: String?,
    val ingredients: List<String>,  // TypeConverter (JSON)
    val instructions: List<String>,
    val prepTime: String?,
    val cookTime: String?,
    val totalTime: String?,
    val servings: String?,          // the recipe's yield text as published
    val sourceType: String,         // BLOG | REDDIT, for re-fetch
    val lastViewedAt: Long,
    val checkedIngredients: Set<Int> = emptySet(),
    val notes: String? = null       // the user's own note; kept across re-shares
)

@Entity(tableName = "lists")
data class ListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    // Seeded on first run, and sorts before user-created lists. Deliberately NOT "undeletable"
    // — that is isFavorites' job, and only Favorites is protected.
    val isBuiltIn: Boolean,
    val isFavorites: Boolean,       // exactly one row; a column, never a name match
    val sortOrder: Int,
    val createdAt: Long
)

/** "Saved" means "has at least one of these". There is deliberately no isSaved column. */
@Entity(
    tableName = "recipe_list_cross_ref",
    primaryKeys = ["recipeId", "listId"],
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipeId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ListEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("listId")]
)
data class RecipeListCrossRef(
    val recipeId: Long,
    val listId: Long,
    val addedAt: Long
)
