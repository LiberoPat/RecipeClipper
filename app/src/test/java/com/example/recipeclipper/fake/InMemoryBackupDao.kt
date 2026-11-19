package com.example.recipeclipper.fake

import com.example.recipeclipper.data.backup.ExistingList
import com.example.recipeclipper.data.backup.ExistingMealType
import com.example.recipeclipper.data.backup.ExistingPantryItem
import com.example.recipeclipper.data.backup.ExistingRecipe
import com.example.recipeclipper.data.local.dao.BackupDao
import com.example.recipeclipper.data.local.entity.GroceryItemEntity
import com.example.recipeclipper.data.local.entity.ListEntity
import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.local.entity.MealTypeEntity
import com.example.recipeclipper.data.local.entity.MenuEntity
import com.example.recipeclipper.data.local.entity.MenuEntryEntity
import com.example.recipeclipper.data.local.entity.PantryItemEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef

/** Rows in memory, with the same derived columns and IGNORE rule the SQL has. */
/**
 * [BackupDao] over lists in memory, with the derived columns and the IGNORE rule the SQL has (so an import's
 * plan can be written and read back without Room). Open so a test can make one write throw.
 */
open class InMemoryBackupDao : BackupDao() {
    val recipes = mutableListOf<RecipeEntity>()
    val lists = mutableListOf<ListEntity>()
    val refs = mutableListOf<RecipeListCrossRef>()
    var writes = 0
    private var nextId = 1000L

    override suspend fun allRecipes() = recipes.sortedWith(compareByDescending<RecipeEntity> { it.lastViewedAt }.thenByDescending { it.id })
    override suspend fun allLists() = lists.sortedWith(compareByDescending<ListEntity> { it.isBuiltIn }.thenBy { it.sortOrder }.thenBy { it.id })
    override suspend fun allCrossRefs() = refs.toList()
    override suspend fun existingRecipes() = recipes.map { r ->
        ExistingRecipe(
            r.id, r.uid, r.sourceUrl, !r.notes.isNullOrBlank(),
            refs.any { it.recipeId == r.id } || r.contentOrigin in listOf("MANUAL", "CHAT")
        )
    }
    override suspend fun existingLists() = allLists().map { ExistingList(it.id, it.uid, it.name, it.isFavorites) }
    override suspend fun maxSortOrder() = lists.maxOfOrNull { it.sortOrder } ?: -1
    override suspend fun insertRecipe(recipe: RecipeEntity): Long {
        writes++
        val id = nextId++
        recipes += recipe.copy(id = id)
        return id
    }
    override suspend fun insertList(list: ListEntity): Long {
        writes++
        val id = nextId++
        lists += list.copy(id = id)
        return id
    }
    override suspend fun addToList(crossRef: RecipeListCrossRef) {
        writes++
        if (refs.none { it.recipeId == crossRef.recipeId && it.listId == crossRef.listId }) refs += crossRef
    }
    override suspend fun fillNote(id: Long, notes: String) {
        writes++
        val i = recipes.indexOfFirst { it.id == id }
        if (i >= 0 && recipes[i].notes.isNullOrBlank()) recipes[i] = recipes[i].copy(notes = notes)
    }

    val pantry = mutableListOf<PantryItemEntity>()
    val groceries = mutableListOf<GroceryItemEntity>()
    override suspend fun allPantry() = pantry.toList()
    override suspend fun allGroceries() = groceries.sortedBy { it.sortOrder }
    override suspend fun existingPantry() = pantry.map { ExistingPantryItem(it.uid, it.name, it.language) }
    override suspend fun existingGroceryUids() = groceries.map { it.uid }
    override suspend fun maxGroceryOrder(listId: Long) = groceries.maxOfOrNull { it.sortOrder } ?: -1
    override suspend fun insertPantryItem(item: PantryItemEntity): Long {
        writes++
        val id = nextId++
        pantry += item.copy(id = id)
        return id
    }
    override suspend fun insertGroceryItem(item: GroceryItemEntity): Long {
        writes++
        val id = nextId++
        groceries += item.copy(id = id)
        return id
    }

    val mealTypes = mutableListOf<MealTypeEntity>()
    val plan = mutableListOf<MealPlanEntryEntity>()
    override suspend fun allMealTypes() = mealTypes.sortedWith(compareBy<MealTypeEntity> { it.sortOrder }.thenBy { it.id })
    override suspend fun allPlanEntries() = plan.toList()
    override suspend fun existingMealTypes() = allMealTypes().map { ExistingMealType(it.id, it.uid, it.name, it.builtInKey) }
    override suspend fun maxMealTypeOrder() = mealTypes.maxOfOrNull { it.sortOrder } ?: -1
    override suspend fun existingPlanUids() = plan.map { it.uid }
    override suspend fun nextPlanOrder(day: Long, mealTypeId: Long) =
        (plan.filter { it.day == day && it.mealTypeId == mealTypeId }.maxOfOrNull { it.sortOrder } ?: -1) + 1
    override suspend fun insertMealType(type: MealTypeEntity): Long {
        writes++
        val id = nextId++
        mealTypes += type.copy(id = id)
        return id
    }
    override suspend fun insertPlanEntry(entry: MealPlanEntryEntity): Long {
        writes++
        val id = nextId++
        plan += entry.copy(id = id)
        return id
    }

    val menus = mutableListOf<MenuEntity>()
    val menuEntries = mutableListOf<MenuEntryEntity>()
    override suspend fun allMenus() = menus.toList()
    override suspend fun allMenuEntries() = menuEntries.toList()
    override suspend fun existingMenuUids() = menus.map { it.uid }
    override suspend fun existingMenuEntryUids() = menuEntries.map { it.uid }
    override suspend fun insertMenu(menu: MenuEntity): Long {
        writes++
        val id = nextId++
        menus += menu.copy(id = id)
        return id
    }
    override suspend fun insertMenuEntry(entry: MenuEntryEntity): Long {
        writes++
        val id = nextId++
        menuEntries += entry.copy(id = id)
        return id
    }

    val cookedPhotos = mutableListOf<com.example.recipeclipper.data.local.entity.CookedPhotoEntity>()
    override suspend fun allCookedPhotos() = cookedPhotos.toList()
    override suspend fun existingCookedPhotoUids() = cookedPhotos.map { it.uid }
    override suspend fun insertCookedPhoto(photo: com.example.recipeclipper.data.local.entity.CookedPhotoEntity): Long {
        writes++
        val id = nextId++
        cookedPhotos += photo.copy(id = id)
        return id
    }

    // A shared file's reads and its "viewed now" (#149): not used by an export or import.
    override suspend fun recipesByIds(ids: List<Long>) = recipes.filter { it.id in ids }
    override suspend fun uncheckedGroceries() = groceries.filter { !it.checked }
    override suspend fun touchByUrl(urls: List<String>, now: Long) = Unit
}
