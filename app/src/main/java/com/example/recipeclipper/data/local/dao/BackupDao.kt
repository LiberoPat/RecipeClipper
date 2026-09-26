package com.example.recipeclipper.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.recipeclipper.data.backup.Backup
import com.example.recipeclipper.data.backup.BackupMerger
import com.example.recipeclipper.data.backup.ExistingList
import com.example.recipeclipper.data.backup.ExistingMealType
import com.example.recipeclipper.data.backup.ExistingPantryItem
import com.example.recipeclipper.data.backup.ExistingRecipe
import com.example.recipeclipper.data.backup.ImportSummary
import com.example.recipeclipper.data.backup.Target
import com.example.recipeclipper.data.local.entity.CookedPhotoEntity
import com.example.recipeclipper.data.local.entity.GroceryItemEntity
import com.example.recipeclipper.data.local.entity.ListEntity
import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.local.entity.MealTypeEntity
import com.example.recipeclipper.data.local.entity.MenuEntity
import com.example.recipeclipper.data.local.entity.MenuEntryEntity
import com.example.recipeclipper.data.local.entity.PantryItemEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import com.example.recipeclipper.data.model.LibraryLimit

/** Everything an export holds, read in one transaction so it is one consistent moment. */
data class BackupSnapshot(
    val recipes: List<RecipeEntity>,
    val lists: List<ListEntity>,
    val crossRefs: List<RecipeListCrossRef>,
    val pantry: List<PantryItemEntity>,
    val groceries: List<GroceryItemEntity>,
    val mealTypes: List<MealTypeEntity>,
    val mealPlan: List<MealPlanEntryEntity>,
    val menus: List<MenuEntity> = emptyList(),
    val menuEntries: List<MenuEntryEntity> = emptyList(),
    val cookedPhotos: List<CookedPhotoEntity> = emptyList()
)

/**
 * Export and import (#26). Import works out a plan with the pure [BackupMerger] from what is
 * here, then writes it, all in one transaction: if any write fails, nothing was imported.
 * Import only ever inserts (recipes, lists, memberships, pantry and grocery items, meal types and
 * planned meals, menus) and fills empty notes; it never
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

    @Query("SELECT * FROM pantry_items ORDER BY id ASC")
    abstract suspend fun allPantry(): List<PantryItemEntity>

    @Query("SELECT * FROM grocery_items ORDER BY listId ASC, sortOrder ASC, id ASC")
    abstract suspend fun allGroceries(): List<GroceryItemEntity>

    @Query("SELECT * FROM meal_types ORDER BY sortOrder ASC, id ASC")
    abstract suspend fun allMealTypes(): List<MealTypeEntity>

    @Query("SELECT * FROM meal_plan_entries ORDER BY day ASC, mealTypeId ASC, sortOrder ASC, id ASC")
    abstract suspend fun allPlanEntries(): List<MealPlanEntryEntity>

    @Query("SELECT * FROM menus ORDER BY id ASC")
    abstract suspend fun allMenus(): List<MenuEntity>

    @Query("SELECT * FROM menu_entries ORDER BY menuId ASC, dayOffset ASC, mealTypeId ASC, sortOrder ASC, id ASC")
    abstract suspend fun allMenuEntries(): List<MenuEntryEntity>

    @Query("SELECT * FROM cooked_photos ORDER BY recipeId ASC, day DESC, createdAt DESC, id DESC")
    abstract suspend fun allCookedPhotos(): List<CookedPhotoEntity>

    @Transaction
    open suspend fun snapshot(): BackupSnapshot =
        BackupSnapshot(
            allRecipes(), allLists(), allCrossRefs(), allPantry(), allGroceries(), allMealTypes(), allPlanEntries(),
            allMenus(), allMenuEntries(), allCookedPhotos()
        )

    @Query("SELECT uid FROM cooked_photos")
    abstract suspend fun existingCookedPhotoUids(): List<String>

    @Insert
    abstract suspend fun insertCookedPhoto(photo: CookedPhotoEntity): Long

    @Query("SELECT uid FROM menus")
    abstract suspend fun existingMenuUids(): List<String>

    @Query("SELECT uid FROM menu_entries")
    abstract suspend fun existingMenuEntryUids(): List<String>

    @Insert
    abstract suspend fun insertMenu(menu: MenuEntity): Long

    @Insert
    abstract suspend fun insertMenuEntry(entry: MenuEntryEntity): Long

    @Query("SELECT id, uid, name, builtInKey FROM meal_types ORDER BY sortOrder ASC, id ASC")
    abstract suspend fun existingMealTypes(): List<ExistingMealType>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM meal_types")
    abstract suspend fun maxMealTypeOrder(): Int

    @Query("SELECT uid FROM meal_plan_entries")
    abstract suspend fun existingPlanUids(): List<String>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM meal_plan_entries WHERE day = :day AND mealTypeId = :mealTypeId")
    abstract suspend fun nextPlanOrder(day: Long, mealTypeId: Long): Int

    @Insert
    abstract suspend fun insertMealType(type: MealTypeEntity): Long

    @Insert
    abstract suspend fun insertPlanEntry(entry: MealPlanEntryEntity): Long

    @Query("SELECT uid, name, language FROM pantry_items")
    abstract suspend fun existingPantry(): List<ExistingPantryItem>

    @Query("SELECT uid FROM grocery_items")
    abstract suspend fun existingGroceryUids(): List<String>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM grocery_items WHERE listId = :listId")
    abstract suspend fun maxGroceryOrder(listId: Long = GroceryItemEntity.DEFAULT_LIST): Int

    @Insert
    abstract suspend fun insertPantryItem(item: PantryItemEntity): Long

    @Insert
    abstract suspend fun insertGroceryItem(item: GroceryItemEntity): Long

    @Query(
        """
        SELECT id, uid, sourceUrl,
               (notes IS NOT NULL AND trim(notes) != '') AS hasNotes,
               (EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id)
                    OR contentOrigin = 'MANUAL'
                    OR EXISTS(SELECT 1 FROM cooked_photos p WHERE p.recipeId = recipes.id)) AS isListed
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
    open suspend fun importBackup(
        backup: Backup,
        limit: LibraryLimit,
        today: Long?,
        newUid: () -> String,
        /** The package's pictures (#116), by path in the zip, already copied in: their stored names. */
        storedPhotos: Map<String, String> = emptyMap(),
        now: Long = 0L
    ): ImportSummary {
        // Unlimited (#107) leaves nothing out: every recipe comes in.
        val historyLimit = when (limit) {
            is LibraryLimit.History -> limit.keep
            is LibraryLimit.Free -> limit.max
            LibraryLimit.Unlimited -> Int.MAX_VALUE
        }
        val plan = BackupMerger.plan(
            backup = backup,
            existingRecipes = existingRecipes(),
            existingLists = existingLists(),
            maxSortOrder = maxSortOrder(),
            historyLimit = historyLimit,
            newUid = newUid,
            countsEveryRecipe = limit is LibraryLimit.Free,
            existingPantry = existingPantry(),
            existingGroceryUids = existingGroceryUids().toSet(),
            existingMealTypes = existingMealTypes(),
            maxMealTypeSortOrder = maxMealTypeOrder(),
            existingPlanUids = existingPlanUids().toSet(),
            today = today,
            existingMenuUids = existingMenuUids().toSet(),
            existingMenuEntryUids = existingMenuEntryUids().toSet(),
            existingCookedPhotoUids = existingCookedPhotoUids().toSet(),
            availablePhotoFiles = storedPhotos.keys
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
                    language = r.language,
                    contentOrigin = r.contentOrigin,
                    editedAt = r.editedAt
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

        for (p in plan.newPantry) {
            insertPantryItem(
                PantryItemEntity(
                    uid = p.id,
                    name = p.name,
                    quantity = p.quantity,
                    language = p.language,
                    aisle = p.aisle,
                    inStock = p.inStock,
                    alwaysHave = p.alwaysHave,
                    purchasedDay = p.purchasedDay,
                    expiresDay = p.expiresDay,
                    updatedAt = p.updatedAt
                )
            )
        }

        var order = maxGroceryOrder() + 1
        for (g in plan.newGroceries) {
            insertGroceryItem(
                GroceryItemEntity(
                    uid = g.item.id,
                    text = g.item.text,
                    language = g.item.language,
                    aisle = g.item.aisle,
                    checked = g.item.checked,
                    sortOrder = order++,
                    recipeId = g.recipe?.rowId(newRecipeIds),
                    plannedDay = g.item.plannedDay,
                    updatedAt = g.item.updatedAt
                )
            )
        }

        val newTypeIds = HashMap<String, Long>()
        for (t in plan.newMealTypes) {
            newTypeIds[t.uid] = insertMealType(
                MealTypeEntity(uid = t.uid, name = t.name, builtInKey = null, sortOrder = t.sortOrder, updatedAt = t.updatedAt)
            )
        }
        for (p in plan.newPlanEntries) {
            val mealTypeId = p.mealType.rowId(newTypeIds)
            insertPlanEntry(
                MealPlanEntryEntity(
                    uid = p.entry.id,
                    day = p.entry.day,
                    mealTypeId = mealTypeId,
                    recipeId = p.recipe?.rowId(newRecipeIds),
                    servings = p.entry.servings,
                    note = p.entry.note,
                    sortOrder = nextPlanOrder(p.entry.day, mealTypeId),
                    updatedAt = p.entry.updatedAt
                )
            )
        }
        for (m in plan.newMenus) {
            val menuId = insertMenu(MenuEntity(uid = m.menu.id, name = m.menu.name, updatedAt = m.menu.updatedAt))
            for (e in m.entries) {
                insertMenuEntry(
                    MenuEntryEntity(
                        uid = e.entry.id,
                        menuId = menuId,
                        dayOffset = e.entry.dayOffset,
                        mealTypeId = e.mealType.rowId(newTypeIds),
                        recipeId = e.recipe?.rowId(newRecipeIds),
                        servings = e.entry.servings,
                        note = e.entry.note,
                        sortOrder = e.entry.sortOrder,
                        updatedAt = e.entry.updatedAt
                    )
                )
            }
        }
        for (p in plan.newCookedPhotos) {
            insertCookedPhoto(
                CookedPhotoEntity(
                    uid = p.photo.id,
                    recipeId = p.recipe.rowId(newRecipeIds),
                    fileName = storedPhotos.getValue(p.photo.file),
                    day = p.photo.day,
                    note = p.photo.note,
                    createdAt = p.photo.createdAt.takeIf { it > 0 } ?: now,
                    updatedAt = p.photo.updatedAt.takeIf { it > 0 } ?: now
                )
            )
        }
        return plan.summary
    }
}
