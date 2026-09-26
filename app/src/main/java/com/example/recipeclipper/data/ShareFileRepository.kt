package com.example.recipeclipper.data

import androidx.room.withTransaction
import com.example.recipeclipper.data.backup.Backup
import com.example.recipeclipper.data.backup.BackupError
import com.example.recipeclipper.data.backup.BackupJson
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ImportSummary
import com.example.recipeclipper.data.backup.ShareChoice
import com.example.recipeclipper.data.backup.ShareFile
import com.example.recipeclipper.data.local.RecipeDatabase
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.PlanDays
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The file that carries picked recipes and items to someone else's Recipe Clipper (#149, phase
 * 2; the rules are [ShareFile]). An interface so the send and receive ViewModels take a fake.
 * Nothing is sent anywhere from here: the screen hands the file to the user's own share sheet.
 */
interface ShareFileRepository {

    /** The file for recipe [recipeId], complete; null if it's gone or can't be read. */
    suspend fun recipeFile(recipeId: Long): String?

    /** The file for every grocery item not ticked off, with the recipes they came from; null
     *  when none is left to buy. */
    suspend fun groceriesFile(): String?

    /** Merges what the receiver chose from [file]; a failure writes nothing. */
    suspend fun receive(file: Backup, choice: ShareChoice): BackupResult<ImportSummary>
}

/**
 * The Room-backed [ShareFileRepository]. Receiving runs the merge and the history cap in one
 * transaction, as a shared link's save does; a database failure is logged and changes nothing.
 */
@Singleton
class DefaultShareFileRepository @Inject constructor(
    private val db: RecipeDatabase,
    private val clock: Clock,
    private val log: ErrorLog,
    private val library: LibraryPolicy = LibraryPolicy.HistoryOnly
) : ShareFileRepository {

    override suspend fun recipeFile(recipeId: Long): String? = log.guard("recipeFile", null) {
        val recipe = db.backupDao().recipesByIds(listOf(recipeId)).singleOrNull() ?: return@guard null
        BackupJson.encode(ShareFile.make(clock.now(), listOf(recipe.toBackup())))
    }

    override suspend fun groceriesFile(): String? = log.guard("groceriesFile", null) {
        val items = db.backupDao().uncheckedGroceries().ifEmpty { return@guard null }
        val recipes = db.backupDao().recipesByIds(items.mapNotNull { it.recipeId }.distinct())
        val uids = recipes.associate { it.id to it.uid }
        BackupJson.encode(ShareFile.make(clock.now(), recipes.map { it.toBackup() }, items.map { it.toBackup(uids) }))
    }

    override suspend fun receive(file: Backup, choice: ShareChoice): BackupResult<ImportSummary> {
        val now = clock.now()
        val limit = library.limit
        val summary = log.guard("receiveShare", null) {
            db.withTransaction {
                val summary = db.backupDao().importShare(
                    ShareFile.chosen(file, choice, now), limit, { UUID.randomUUID().toString() }, now
                )
                if (limit is LibraryLimit.History) db.recipeDao().cullHistory(limit.keep, PlanDays.today(now))
                summary
            }
        }
        return summary?.let { BackupResult.Success(it) } ?: BackupResult.Failure(BackupError.SaveFailed)
    }
}
