package com.example.recipeclipper.data

import com.example.recipeclipper.data.backup.MealieImport
import com.example.recipeclipper.data.backup.MealieSummary
import com.example.recipeclipper.data.local.dao.BackupDao
import com.example.recipeclipper.data.remote.MealieError
import com.example.recipeclipper.data.remote.MealieRead
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** An import from Mealie's outcome (#391): counts, or the cause. The screen picks the words. */
sealed class MealieResult {
    data class Success(val summary: MealieSummary) : MealieResult()
    data class Failure(val error: MealieError) : MealieResult()
}

/**
 * Reads a Mealie export (#391) into the library. An interface so the Settings ViewModel test can
 * hand it a fake.
 */
interface MealieImportRepository {

    /**
     * Merges the recipes of the picked Mealie file in (never replaces, never deletes; see
     * [MealieImport]). A file that can't be read writes nothing and says why. With [filing] (#387) each
     * recipe's categories and tags put it in lists of those names.
     */
    suspend fun import(uri: String, filing: Boolean = false): MealieResult
}

/**
 * The real [MealieImportRepository], [DefaultPaprikaImportRepository]'s twin: the file is read by
 * [MealieFiles], its recipes become a [com.example.recipeclipper.data.backup.Backup], and
 * [BackupDao.importBackup] merges it in one transaction under the library's limit, as every import
 * does (a free library that is full leaves the rest out and counts them; nothing old is removed). A
 * database failure is logged by [ErrorLog.guard] and becomes [MealieError.SAVE_FAILED]; cancellation
 * is rethrown.
 */
@Singleton
class DefaultMealieImportRepository @Inject constructor(
    private val files: MealieFiles,
    private val backupDao: BackupDao,
    private val clock: Clock,
    private val log: ErrorLog,
    private val library: LibraryPolicy,
    private val photos: PhotoStore = NoPhotoStore
) : MealieImportRepository {

    override suspend fun import(uri: String, filing: Boolean): MealieResult {
        val contents = when (val read = files.read(uri)) {
            is MealieRead.Failure -> return MealieResult.Failure(read.error)
            is MealieRead.Success -> read.contents
        }
        if (contents.recipes.isEmpty()) {
            return MealieResult.Success(
                MealieSummary(0, 0, 0, contents.notRecipes, contents.unreadable, contents.photosLeftOut, freeLimit = null)
            )
        }
        val now = clock.now()
        val mapped = MealieImport.toBackup(contents.recipes, now, filing) { UUID.randomUUID().toString() }
        val limit = library.limit
        // The pictures are already in the store (named for the merger by their own names). Those no
        // recipe ends up with (a recipe already here, one the limit left out, a failed write) go.
        val stored = contents.recipes.mapNotNullTo(LinkedHashSet()) { it.photoFile }
        val summary = log.guard("mealieImport", null) {
            backupDao.importBackup(
                mapped.backup, limit, today = null, newUid = { UUID.randomUUID().toString() },
                storedPhotos = stored.associateWith { it }, now = now, dropEmptyNewLists = true
            )
        }
        if (summary == null) {
            photos.delete(stored)
            return MealieResult.Failure(MealieError.SAVE_FAILED)
        }
        if (stored.isNotEmpty()) {
            val named = log.guard("mealieImport", null) { backupDao.recipePhotoFiles().toSet() }
            if (named != null) photos.delete(stored - named)
        }
        return MealieResult.Success(
            MealieSummary(
                imported = summary.recipesAdded,
                alreadyHere = summary.recipesAlreadyHere + mapped.repeated,
                skippedForLimit = summary.recipesSkipped,
                notRecipes = contents.notRecipes,
                unreadable = contents.unreadable,
                photosLeftOut = contents.photosLeftOut,
                freeLimit = summary.freeLimit,
                listsMade = summary.listsAdded
            )
        )
    }
}
