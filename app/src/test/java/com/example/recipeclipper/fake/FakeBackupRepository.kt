package com.example.recipeclipper.fake

import com.example.recipeclipper.data.BackupFiles
import com.example.recipeclipper.data.BackupRepository
import com.example.recipeclipper.data.backup.BackupError
import com.example.recipeclipper.data.backup.BackupPackage
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ExportedBackup
import com.example.recipeclipper.data.backup.ImportSummary
import kotlinx.coroutines.CompletableDeferred

/** Answers export and import with whatever the test staged, recording what it was given. */
class FakeBackupRepository : BackupRepository {

    var exportResult: BackupResult<ExportedBackup> =
        BackupResult.Success(ExportedBackup(json = "{}", exportedAt = 1_000L, recipeCount = 0))
    var importResult: BackupResult<ImportSummary> =
        BackupResult.Success(ImportSummary(0, 0, 0, 0))

    /** When set, [import] waits for it: lets a test look at the state mid-import. */
    var importGate: CompletableDeferred<Unit>? = null

    var exportCalls = 0
        private set
    val importedTexts = mutableListOf<String>()

    /** The pictures each import was given (#116), by path in the zip. */
    val importedPhotos = mutableListOf<Map<String, String>>()

    override suspend fun export(): BackupResult<ExportedBackup> {
        exportCalls++
        return exportResult
    }

    override suspend fun import(backup: BackupPackage): BackupResult<ImportSummary> {
        importedTexts += backup.json
        importedPhotos += backup.photos
        importGate?.await()
        return importResult
    }
}

/** An in-memory [BackupFiles]: [files] maps a URI to its text; writes are recorded. */
class FakeBackupFiles : BackupFiles {

    val files = mutableMapOf<String, String>()

    /** What [writeExport] returns; null stands for a failed write. */
    var writeUri: String? = "content://test/exports/recipe-clipper.json"

    /** json to exportedAt, for every [writeExport] call. */
    val written = mutableListOf<Pair<String, Long>>()

    /** The pictures each [writeExport] was given (#116). */
    val writtenPhotos = mutableListOf<Map<String, String>>()

    /** Pictures a picked zip holds, by URI (#116). */
    val photos = mutableMapOf<String, Map<String, String>>()

    override suspend fun writeExport(json: String, exportedAt: Long, photos: Map<String, String>): String? {
        written += json to exportedAt
        writtenPhotos += photos
        return writeUri
    }

    override suspend fun read(uri: String): BackupResult<BackupPackage> =
        files[uri]?.let { BackupResult.Success(BackupPackage(it, photos[uri].orEmpty())) }
            ?: BackupResult.Failure(BackupError.ReadFailed)
}
