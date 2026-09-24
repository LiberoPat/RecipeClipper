package com.example.recipeclipper.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "recipes",
    indices = [Index(value = ["sourceUrl"], unique = true), Index(value = ["uid"], unique = true)]
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
    val notes: String? = null,      // the user's own note; kept across re-shares
    /** Stable across devices and exports (#26): what an export file calls this recipe. Never
     *  changes once written — re-sharing keeps it, and an import keeps the file's. */
    @ColumnInfo(defaultValue = "")
    val uid: String = newUid(),
    val language: String? = null,   // the recipe's language tag (#14); null before version 5
    val cookState: String? = null,  // CookProgress as JSON (CookStateJson); kept if steps unchanged
    val servingsTarget: Int? = null, // the chosen servings; null = the recipe's own yield
    /** [com.example.recipeclipper.data.model.ContentOrigin] by name (#29): PARSED is the
     *  source's words, anything else the user's version, never refreshed by a re-share. */
    @ColumnInfo(defaultValue = "PARSED")
    val contentOrigin: String = "PARSED",
    val editedAt: Long? = null      // when the user last saved an edit; null if never
)

@Entity(tableName = "lists", indices = [Index(value = ["uid"], unique = true)])
data class ListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    // Seeded on first run, and sorts before user-created lists. Deliberately NOT "undeletable"
    // — that is isFavorites' job, and only Favorites is protected.
    val isBuiltIn: Boolean,
    val isFavorites: Boolean,       // exactly one row; a column, never a name match
    val sortOrder: Int,
    val createdAt: Long,
    /** Stable across devices and exports (#26); survives a rename, unlike [name]. */
    @ColumnInfo(defaultValue = "")
    val uid: String = newUid()
)

/** A fresh stable id for a new row. The same form the migrations backfill with. */
fun newUid(): String = UUID.randomUUID().toString()

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
