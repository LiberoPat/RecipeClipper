package com.example.recipeclipper.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.recipeclipper.data.backup.Backup
import com.example.recipeclipper.data.backup.BackupMerger
import com.example.recipeclipper.data.backup.ExistingList
import com.example.recipeclipper.data.backup.ExistingRecipe
import com.example.recipeclipper.data.backup.ImportSummary
import com.example.recipeclipper.data.backup.Target
import com.example.recipeclipper.data.local.entity.ListEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef

/** Everything an export holds, read in one transaction so it is one consistent moment. */
data class BackupSnapshot(
    val recipes: List<RecipeEntity>,
    val lists: List<ListEntity>,
    val crossRefs: List<RecipeListCrossRef>
)

/**
 * Export and import (#26). Import works out a plan with the pure [BackupMerger] from what is
 * here, then writes it, all in one transaction: if any write fails, nothing was imported.
 * Import only ever inserts (recipes, lists, memberships) and fills empty notes; it never
 * deletes, and it runs no history cull (see [BackupMerger] for how the cap is respected).
 */
@Dao
abstract class BackupDao {

    @Query("SELECT * FROM recipes ORDER BY lastViewedAt DESC, id DESC")
    abstract suspend fun allRecipes(): List<RecipeEntity>

    @Query("SELECT * FROM lists ORDER BY isBuiltIn DESC, sortOrder ASC, id ASC")
    abstract suspend fun allLists(): List<ListEntity>

    @Query("SELECT * FROM recipe_list_cross_ref ORDER BY listId ASC, addedAt ASC, recipeId ASC")
    abstract suspend fun allCrossRefs(): List<RecipeListCrossRef>

    @Transaction
    open suspend fun snapshot(): BackupSnapshot = BackupSnapshot(allRecipes(), allLists(), allCrossRefs())

    @Query(
        """
        SELECT id, uid, sourceUrl,
               (notes IS NOT NULL AND trim(notes) != '') AS hasNotes,
               EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id) AS isListed
        FROM recipes
        """
    )
    abstract suspend fun existingRecipes(): List<ExistingRecipe>

    @Query("SELECT id, uid, name, isFavorites FROM lists ORDER BY isBuiltIn DESC, sortOrder ASC, id ASC")
    abstract suspend fun existingLists(): List<ExistingList>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM lists")
    abstract suspend fun maxSortOrder(): Int

    @Insert
    abstract suspend fun insertRecipe(recipe: RecipeEntity): Long

    @Insert
    abstract suspend fun insertList(list: ListEntity): Long

    /** IGNORE, never REPLACE: a membership already here keeps its `addedAt`. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addToList(crossRef: RecipeListCrossRef)

    /** Only ever fills an empty note; the plan never names a recipe that has one. */
    @Query("UPDATE recipes SET notes = :notes WHERE id = :id AND (notes IS NULL OR trim(notes) = '')")
    abstract suspend fun fillNote(id: Long, notes: String)

    @Transaction
    open suspend fun importBackup(backup: Backup, historyLimit: Int, newUid: () -> String): ImportSummary {
        val plan = BackupMerger.plan(
            backup = backup,
            existingRecipes = existingRecipes(),
            existingLists = existingLists(),
            maxSortOrder = maxSortOrder(),
            historyLimit = historyLimit,
            newUid = newUid
        )

        val newRecipeIds = HashMap<String, Long>()
        for (r in plan.newRecipes) {
            newRecipeIds[r.id] = insertRecipe(
                RecipeEntity(
                    uid = r.id,
                    sourceUrl = r.sourceUrl,
                    title = r.title,
                    imageUrl = r.imageUrl,
                    ingredients = r.ingredients,
                    instructions = r.instructions,
                    prepTime = r.prepTime,
                    cookTime = r.cookTime,
                    totalTime = r.totalTime,
                    servings = r.servings,
                    sourceType = r.sourceType,
                    lastViewedAt = r.lastViewedAt,
                    checkedIngredients = r.checkedIngredients,
                    notes = r.notes,
                    language = r.language
                )
            )
        }
        plan.noteUpdates.forEach { fillNote(it.recipeId, it.notes) }

        val newListIds = HashMap<String, Long>()
        for (l in plan.newLists) {
            newListIds[l.uid] = insertList(
                ListEntity(
                    uid = l.uid,
                    name = l.name,
                    isBuiltIn = l.isBuiltIn,
                    isFavorites = false,
                    sortOrder = l.sortOrder,
                    createdAt = l.createdAt
                )
            )
        }

        fun Target.rowId(new: Map<String, Long>): Long = when (this) {
            is Target.Existing -> id
            is Target.New -> new.getValue(uid)
        }
        for (m in plan.memberships) {
            addToList(RecipeListCrossRef(m.recipe.rowId(newRecipeIds), m.list.rowId(newListIds), m.addedAt))
        }
        return plan.summary
    }
}
