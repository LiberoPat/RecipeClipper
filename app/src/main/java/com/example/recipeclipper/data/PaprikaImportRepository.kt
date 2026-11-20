package com.example.recipeclipper.data

import com.example.recipeclipper.data.backup.PaprikaImport
import com.example.recipeclipper.data.backup.PaprikaSummary
import com.example.recipeclipper.data.local.dao.BackupDao
import com.example.recipeclipper.data.remote.ImportSource
import com.example.recipeclipper.data.remote.PaprikaError
import com.example.recipeclipper.data.remote.PaprikaRead
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** An import from Paprika's outcome (#382): counts, or the cause. The screen picks the words. */
sealed class PaprikaResult {
    data class Success(val summary: PaprikaSummary) : PaprikaResult()
    data class Failure(val error: PaprikaError) : PaprikaResult()
}

/**
 * Reads a Paprika export (#382) into the library. An interface so the Settings ViewModel test
 * can hand it a fake.
 */
interface PaprikaImportRepository {

    /**
     * Merges the recipes of the picked `.paprikarecipes` file in (never replaces, never deletes;
     * see [PaprikaImport]). A file that can't be read writes nothing and says why. With [filing]
     * (#387) each recipe's Paprika categories put it in lists of those names. [source] (#390) says
     * whose file it is: the Recipe Keeper import is the same route with its own reader.
     */
    suspend fun import(uri: String, filing: Boolean = false, source: ImportSource = ImportSource.PAPRIKA): PaprikaResult
}

/**
 * The real [PaprikaImportRepository]: the file is read by [PaprikaFiles], its recipes become a
 * [com.example.recipeclipper.data.backup.Backup] with no lists, and [BackupDao.importBackup]
 * merges it in one transaction under the library's limit, as every import does (a free library
 * that is full leaves the rest out and counts them; nothing old is removed). A database failure
 * is logged by [ErrorLog.guard] and becomes [PaprikaError.SAVE_FAILED]; cancellation is rethrown.
 */
@Singleton
class DefaultPaprikaImportRepository @Inject constructor(
    private val files: PaprikaFiles,
    private val backupDao: BackupDao,
    private val clock: Clock,
    private val log: ErrorLog,
    private val library: LibraryPolicy,
    private val photos: PhotoStore = NoPhotoStore
) : PaprikaImportRepository {

    override suspend fun import(uri: String, filing: Boolean, source: ImportSource): PaprikaResult {
        val contents = when (val read = files.read(uri, source)) {
            is PaprikaRead.Failure -> return PaprikaResult.Failure(read.error)
            is PaprikaRead.Success -> read.contents
        }
        if (contents.recipes.isEmpty()) {
            return PaprikaResult.Success(
                PaprikaSummary(0, 0, 0, contents.notRecipes, contents.unreadable, contents.photosLeftOut, freeLimit = null)
            )
        }
        val now = clock.now()
        val tag = if (source == ImportSource.PAPRIKA) "paprikaImport" else "recipeKeeperImport"
        val mapped = PaprikaImport.toBackup(contents.recipes, now, filing, source) { UUID.randomUUID().toString() }
        val limit = library.limit
        // The pictures are already in the store (named for the merger by their own names). Those no
        // recipe ends up with (a recipe already here, one the limit left out, a failed write) go.
        val stored = contents.recipes.mapNotNullTo(LinkedHashSet()) { it.photoFile }
        val summary = log.guard(tag, null) {
            backupDao.importBackup(
                mapped.backup, limit, today = null, newUid = { UUID.randomUUID().toString() },
                storedPhotos = stored.associateWith { it }, now = now, dropEmptyNewLists = true
            )
        }
        if (summary == null) {
            photos.delete(stored)
            return PaprikaResult.Failure(PaprikaError.SAVE_FAILED)
        }
        if (stored.isNotEmpty()) {
            val named = log.guard(tag, null) { backupDao.recipePhotoFiles().toSet() }
            if (named != null) photos.delete(stored - named)
        }
        return PaprikaResult.Success(
            PaprikaSummary(
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
