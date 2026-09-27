package com.example.recipeclipper.data

import com.example.recipeclipper.data.backup.Backup
import com.example.recipeclipper.data.backup.BackupCookedPhoto
import com.example.recipeclipper.data.backup.BackupError
import com.example.recipeclipper.data.backup.BackupPackage
import com.example.recipeclipper.data.backup.BackupGroceryItem
import com.example.recipeclipper.data.backup.BackupJson
import com.example.recipeclipper.data.backup.BackupList
import com.example.recipeclipper.data.backup.BackupMealType
import com.example.recipeclipper.data.backup.BackupMenu
import com.example.recipeclipper.data.backup.BackupMenuEntry
import com.example.recipeclipper.data.backup.BackupMembership
import com.example.recipeclipper.data.backup.BackupPantryItem
import com.example.recipeclipper.data.backup.BackupPlanEntry
import com.example.recipeclipper.data.backup.BackupRecipe
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ExportedBackup
import com.example.recipeclipper.data.backup.ImportSummary
import com.example.recipeclipper.data.local.dao.BackupDao
import com.example.recipeclipper.data.local.entity.GroceryItemEntity
import com.example.recipeclipper.data.local.entity.PantryItemEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.PlanDays
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Export and import of every recipe and list as one file (#26). An interface so the Settings
 * ViewModel test can hand it a fake.
 */
interface BackupRepository {

    /** Everything, as the text of one export file. */
    suspend fun export(): BackupResult<ExportedBackup>

    /**
     * Merges an export file into what's here (never replaces, never deletes; see
     * [com.example.recipeclipper.data.backup.BackupMerger]). A file that can't be read writes
     * nothing and says why.
     */
    suspend fun import(text: String): BackupResult<ImportSummary> = import(BackupPackage(text))

    /** [import] of a package: the JSON, and the pictures of the user's photos beside it (#116). */
    suspend fun import(backup: BackupPackage): BackupResult<ImportSummary>
}

/** Reads a picked file and merges it in: Settings' Import and Home's Restore (#150) alike. */
suspend fun BackupRepository.importFile(files: BackupFiles, uri: String): BackupResult<ImportSummary> =
    when (val picked = files.read(uri)) {
        is BackupResult.Failure -> picked
        is BackupResult.Success -> import(picked.value)
    }

/**
 * The real [BackupRepository]. Database failures are logged and become [BackupError.ExportFailed]
 * or [BackupError.SaveFailed] (the import is one transaction, so a failed one wrote nothing);
 * cancellation is always rethrown, by [ErrorLog.guard].
 */
@Singleton
class DefaultBackupRepository @Inject constructor(
    private val backupDao: BackupDao,
    private val clock: Clock,
    private val log: ErrorLog,
    private val library: LibraryPolicy = LibraryPolicy.HistoryOnly,
    private val photos: PhotoStore = NoPhotoStore
) : BackupRepository {

    override suspend fun export(): BackupResult<ExportedBackup> {
        val snapshot = log.guard("export", null) { backupDao.snapshot() }
            ?: return BackupResult.Failure(BackupError.ExportFailed)
        val recipeUids = snapshot.recipes.associate { it.id to it.uid }
        val listUids = snapshot.lists.associate { it.id to it.uid }
        val mealTypeUids = snapshot.mealTypes.associate { it.id to it.uid }
        val menuUids = snapshot.menus.associate { it.id to it.uid }
        val now = clock.now()
        val backup = Backup(
            exportedAt = now,
            recipes = snapshot.recipes.map { it.toBackup() },
            lists = snapshot.lists.map {
                BackupList(it.uid, it.name, it.isFavorites, it.isBuiltIn, it.sortOrder, it.createdAt)
            },
            memberships = snapshot.crossRefs.mapNotNull { ref ->
                val recipe = recipeUids[ref.recipeId] ?: return@mapNotNull null
                val list = listUids[ref.listId] ?: return@mapNotNull null
                BackupMembership(recipe, list, ref.addedAt)
            },
            pantry = snapshot.pantry.map { it.toBackup() },
            groceries = snapshot.groceries.map { it.toBackup(recipeUids) },
            mealTypes = snapshot.mealTypes.map { BackupMealType(it.uid, it.name, it.builtInKey, it.sortOrder, it.updatedAt) },
            mealPlan = snapshot.mealPlan.map {
                BackupPlanEntry(
                    it.uid, it.day, mealTypeUids[it.mealTypeId], it.recipeId?.let(recipeUids::get),
                    it.servings, it.note, it.sortOrder, it.updatedAt
                )
            },
            menus = snapshot.menus.map { BackupMenu(it.uid, it.name, it.updatedAt) },
            menuEntries = snapshot.menuEntries.mapNotNull {
                BackupMenuEntry(
                    it.uid, menuUids[it.menuId] ?: return@mapNotNull null, it.dayOffset, mealTypeUids[it.mealTypeId],
                    it.recipeId?.let(recipeUids::get), it.servings, it.note, it.sortOrder, it.updatedAt
                )
            }
        )
        // The pictures travel beside the JSON (#116), each named after its row.
        val pictures = LinkedHashMap<String, String>()
        val withPhotos = backup.copy(
            cookedPhotos = snapshot.cookedPhotos.mapNotNull { photo ->
                val recipe = recipeUids[photo.recipeId] ?: return@mapNotNull null
                val file = "photos/photo-${photo.id}.jpg"
                pictures[file] = photos.path(photo.fileName)
                BackupCookedPhoto(photo.uid, recipe, photo.day, photo.note, photo.createdAt, photo.updatedAt, file)
            }
        )
        return BackupResult.Success(ExportedBackup(BackupJson.encode(withPhotos), now, backup.recipes.size, pictures))
    }

    override suspend fun import(backup: BackupPackage): BackupResult<ImportSummary> {
        val decoded = when (val result = BackupJson.decode(backup.json)) {
            is BackupResult.Success -> result.value
            is BackupResult.Failure -> return result
        }
        // Copy in the pictures the file's photos name first; a photo whose picture didn't come
        // stays out. Copies the import didn't use are swept later (PhotoStore's grace period).
        val stored = HashMap<String, String>()
        for (file in decoded.cookedPhotos.mapTo(LinkedHashSet()) { it.file }) {
            val local = backup.photos[file] ?: continue
            photos.adopt(local)?.let { stored[file] = it }
        }
        val now = clock.now()
        val summary = log.guard("import", null) {
            backupDao.importBackup(decoded, library.limit, PlanDays.today(now), { UUID.randomUUID().toString() }, stored, now)
        }
        if (summary == null) {
            photos.delete(stored.values)
            return BackupResult.Failure(BackupError.SaveFailed)
        }
        return BackupResult.Success(summary)
    }
}

/** A recipe as the export file holds it (#26), and as a shared file sends it (#149). */
internal fun RecipeEntity.toBackup() = BackupRecipe(
    id = uid,
    sourceUrl = sourceUrl,
    sourceType = sourceType,
    title = title,
    imageUrl = imageUrl,
    ingredients = ingredients,
    instructions = instructions,
    prepTime = prepTime,
    cookTime = cookTime,
    totalTime = totalTime,
    servings = servings,
    lastViewedAt = lastViewedAt,
    checkedIngredients = checkedIngredients,
    notes = notes,
    language = language,
    contentOrigin = contentOrigin,
    editedAt = editedAt
)

/** A pantry item as the export file holds it, and as the Pantry's shared file sends it (#149). */
internal fun PantryItemEntity.toBackup() = BackupPantryItem(
    uid, name, quantity, language, aisle, inStock, alwaysHave, purchasedDay, expiresDay, updatedAt
)

/** A grocery item as the export file holds it; [recipeUids] maps recipe row ids to uids. */
internal fun GroceryItemEntity.toBackup(recipeUids: Map<Long, String>) = BackupGroceryItem(
    uid, text, language, aisle, checked, recipeId?.let(recipeUids::get), plannedDay, updatedAt
)
