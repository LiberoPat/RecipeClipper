package com.example.recipeclipper.data

import com.example.recipeclipper.data.backup.Backup
import com.example.recipeclipper.data.backup.BackupError
import com.example.recipeclipper.data.backup.BackupJson
import com.example.recipeclipper.data.backup.BackupList
import com.example.recipeclipper.data.backup.BackupMembership
import com.example.recipeclipper.data.backup.BackupRecipe
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ExportedBackup
import com.example.recipeclipper.data.backup.ImportSummary
import com.example.recipeclipper.data.local.dao.BackupDao
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
    suspend fun import(text: String): BackupResult<ImportSummary>
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
    private val log: ErrorLog
) : BackupRepository {

    override suspend fun export(): BackupResult<ExportedBackup> {
        val snapshot = log.guard("export", null) { backupDao.snapshot() }
            ?: return BackupResult.Failure(BackupError.ExportFailed)
        val recipeUids = snapshot.recipes.associate { it.id to it.uid }
        val listUids = snapshot.lists.associate { it.id to it.uid }
        val now = clock.now()
        val backup = Backup(
            exportedAt = now,
            recipes = snapshot.recipes.map {
                BackupRecipe(
                    id = it.uid,
                    sourceUrl = it.sourceUrl,
                    sourceType = it.sourceType,
                    title = it.title,
                    imageUrl = it.imageUrl,
                    ingredients = it.ingredients,
                    instructions = it.instructions,
                    prepTime = it.prepTime,
                    cookTime = it.cookTime,
                    totalTime = it.totalTime,
                    servings = it.servings,
                    lastViewedAt = it.lastViewedAt,
                    checkedIngredients = it.checkedIngredients,
                    notes = it.notes,
                    language = it.language
                )
            },
            lists = snapshot.lists.map {
                BackupList(it.uid, it.name, it.isFavorites, it.isBuiltIn, it.sortOrder, it.createdAt)
            },
            memberships = snapshot.crossRefs.mapNotNull { ref ->
                val recipe = recipeUids[ref.recipeId] ?: return@mapNotNull null
                val list = listUids[ref.listId] ?: return@mapNotNull null
                BackupMembership(recipe, list, ref.addedAt)
            }
        )
        return BackupResult.Success(ExportedBackup(BackupJson.encode(backup), now, backup.recipes.size))
    }

    override suspend fun import(text: String): BackupResult<ImportSummary> {
        val backup = when (val decoded = BackupJson.decode(text)) {
            is BackupResult.Success -> decoded.value
            is BackupResult.Failure -> return decoded
        }
        val summary = log.guard("import", null) {
            backupDao.importBackup(backup, HISTORY_LIMIT) { UUID.randomUUID().toString() }
        } ?: return BackupResult.Failure(BackupError.SaveFailed)
        return BackupResult.Success(summary)
    }
}
