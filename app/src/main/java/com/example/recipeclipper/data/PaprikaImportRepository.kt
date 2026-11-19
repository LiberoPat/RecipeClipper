package com.example.recipeclipper.data

import com.example.recipeclipper.data.backup.PaprikaImport
import com.example.recipeclipper.data.backup.PaprikaSummary
import com.example.recipeclipper.data.local.dao.BackupDao
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
     * see [PaprikaImport]). A file that can't be read writes nothing and says why.
     */
    suspend fun import(uri: String): PaprikaResult
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
    private val library: LibraryPolicy
) : PaprikaImportRepository {

    override suspend fun import(uri: String): PaprikaResult {
        val contents = when (val read = files.read(uri)) {
            is PaprikaRead.Failure -> return PaprikaResult.Failure(read.error)
            is PaprikaRead.Success -> read.contents
        }
        val photos = contents.recipes.any { it.hasPhoto }
        if (contents.recipes.isEmpty()) {
            return PaprikaResult.Success(
                PaprikaSummary(0, 0, 0, contents.notRecipes, contents.unreadable, photos, freeLimit = null)
            )
        }
        val now = clock.now()
        val mapped = PaprikaImport.toBackup(contents.recipes, now) { UUID.randomUUID().toString() }
        val limit = library.limit
        val summary = log.guard("paprikaImport", null) {
            backupDao.importBackup(mapped.backup, limit, today = null, newUid = { UUID.randomUUID().toString() }, now = now)
        } ?: return PaprikaResult.Failure(PaprikaError.SAVE_FAILED)
        return PaprikaResult.Success(
            PaprikaSummary(
                imported = summary.recipesAdded,
                alreadyHere = summary.recipesAlreadyHere + mapped.repeated,
                skippedForLimit = summary.recipesSkipped,
                notRecipes = contents.notRecipes,
                unreadable = contents.unreadable,
                photosNotImported = photos,
                freeLimit = summary.freeLimit
            )
        )
    }
}
