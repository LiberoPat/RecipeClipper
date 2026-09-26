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

/**
 * One line on the grocery list (#50). [text] is the line as written: a recipe's line as the
 * reading view showed it (scaled and converted), or what was typed. [language] is the tag
 * whose words read it (null: none, so it is never named or combined). [aisle] is an `Aisle`
 * key, chosen from the aisle table when added and changed only by the user. [listId] is
 * [DEFAULT_LIST] until there are several lists: a `grocery_lists` table can come later with
 * this as its key. A recipe's items stay when it is deleted; they just lose their source
 * (SET NULL). [uid] and [updatedAt] are for export and a later sync (#53).
 */
@Entity(
    tableName = "grocery_items",
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipeId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["uid"], unique = true),
        Index("listId"),
        Index("recipeId")
    ]
)
data class GroceryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long = DEFAULT_LIST,
    val text: String,
    val language: String?,
    val aisle: String,
    val checked: Boolean = false,
    val sortOrder: Int,             // the order added, within the list
    val recipeId: Long?,            // the recipe it came from, if any
    val plannedDay: Long?,          // the planned day it came from (an epoch day), if any
    val updatedAt: Long,
    val uid: String = newUid()
) {
    companion object {
        const val DEFAULT_LIST = 1L
    }
}

/**
 * One pantry item (#51). [name] as typed, read with [language]'s words; [quantity] free text
 * as written; [aisle] an `Aisle` key, chosen when added. [purchasedDay] and [expiresDay] are
 * epoch days. [alwaysHave] marks a staple. No foreign keys: an item belongs to no recipe.
 * [uid] and [updatedAt] are for export and a later sync (#53).
 */
@Entity(tableName = "pantry_items", indices = [Index(value = ["uid"], unique = true)])
data class PantryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val quantity: String?,
    val language: String?,
    val aisle: String,
    val inStock: Boolean,
    val alwaysHave: Boolean,
    val purchasedDay: Long?,
    val expiresDay: Long?,
    val updatedAt: Long,
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

/**
 * A meal type (#49). Seeded rows carry a [builtInKey] ("breakfast", "lunch", "dinner",
 * "snack") that survives a rename; only rows without one can be deleted, a guard kept in the
 * SQL. [uid] and [updatedAt] are for export and a later sync (#53).
 */
@Entity(tableName = "meal_types", indices = [Index(value = ["uid"], unique = true)])
data class MealTypeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val builtInKey: String?,
    val sortOrder: Int,
    val updatedAt: Long,
    val uid: String = newUid()
)

/**
 * One meal on the plan (#49): a recipe with its planned servings, or a note, on a local
 * calendar [day] (an epoch day, see `PlanDays`). A recipe's entries go with it when it is
 * deleted (cascade). A meal type can't be deleted from under its entries (no cascade):
 * deleting one first moves them to Dinner, in the same transaction.
 */
@Entity(
    tableName = "meal_plan_entries",
    foreignKeys = [
        ForeignKey(
            entity = MealTypeEntity::class,
            parentColumns = ["id"],
            childColumns = ["mealTypeId"]
        ),
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["uid"], unique = true),
        Index("day"),
        Index("mealTypeId"),
        Index("recipeId")
    ]
)
data class MealPlanEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val mealTypeId: Long,
    val recipeId: Long?,
    val servings: Int?,             // planned servings; null = the recipe's own yield
    val note: String?,              // a note instead of a recipe
    val sortOrder: Int,             // within its day and meal type
    val updatedAt: Long,
    val uid: String = newUid()
)

/**
 * A reusable weekly menu (#52): a named copy of a week's meals, to add to any later week. Its
 * entries live in [MenuEntryEntity]. [uid] and [updatedAt] are for export and a later sync (#53).
 */
@Entity(tableName = "menus", indices = [Index(value = ["uid"], unique = true)])
data class MenuEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val updatedAt: Long,
    val uid: String = newUid()
)

/**
 * One meal of a menu (#52), on [dayOffset] days after the week's first day (0 to 6), shaped like
 * [MealPlanEntryEntity]: a recipe at [servings] (null: its own yield), or a [note]. Entries go
 * with their menu and with their recipe (cascade). A meal type can't be deleted from under them
 * (no cascade): deleting one moves them to Dinner, as it does planned meals.
 */
@Entity(
    tableName = "menu_entries",
    foreignKeys = [
        ForeignKey(
            entity = MenuEntity::class,
            parentColumns = ["id"],
            childColumns = ["menuId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MealTypeEntity::class,
            parentColumns = ["id"],
            childColumns = ["mealTypeId"]
        ),
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["uid"], unique = true),
        Index("menuId"),
        Index("mealTypeId"),
        Index("recipeId")
    ]
)
data class MenuEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val menuId: Long,
    val dayOffset: Int,
    val mealTypeId: Long,
    val recipeId: Long?,
    val servings: Int?,
    val note: String?,
    val sortOrder: Int,             // within its day and meal type
    val updatedAt: Long,
    val uid: String = newUid()
)

/**
 * Chef mode's short version of one step (#100), written on the device. Derived data, never in
 * the export file: keyed by recipe, the step's text ([stepHash], its SHA-256) and the language
 * it was written in, so a changed step has no row and is written again; rows for steps the
 * recipe no longer has are pruned. [shortText] null: the model's version failed
 * `ShortStepCheck`, so the step shows as written and isn't asked for again. [uid] and
 * [updatedAt] follow the other tables (#53).
 */
@Entity(
    tableName = "short_steps",
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["uid"], unique = true),
        Index(value = ["recipeId", "stepHash", "language"], unique = true)
    ]
)
data class ShortStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: Long,
    val stepHash: String,
    val language: String,
    val shortText: String?,
    val updatedAt: Long,
    val uid: String = newUid()
)

/**
 * The on-device model's typed decisions (#104), one row per question: its [kind] key, its
 * [input] normalised and its [language], and the [answer] after the confidence rule (an option
 * or "unsure"), so each question is asked once. Derived data: never exported. [uid] and
 * [updatedAt] follow the other tables (#53).
 */
@Entity(
    tableName = "ai_decisions",
    indices = [
        Index(value = ["uid"], unique = true),
        Index(value = ["kind", "input", "language"], unique = true)
    ]
)
data class AiDecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val input: String,
    val language: String,
    val answer: String,
    val updatedAt: Long,
    val uid: String = newUid()
)
