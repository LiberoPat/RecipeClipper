package com.example.recipeclipper.fake

import com.example.recipeclipper.data.MealieFiles
import com.example.recipeclipper.data.MealieImportRepository
import com.example.recipeclipper.data.MealieResult
import com.example.recipeclipper.data.PhotoStore
import com.example.recipeclipper.data.backup.MealieSummary
import com.example.recipeclipper.data.remote.MealieArchive
import com.example.recipeclipper.data.remote.MealieError
import com.example.recipeclipper.data.remote.MealieLimits
import com.example.recipeclipper.data.remote.MealieRead
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/** Answers an import with whatever the test staged, recording what it was given. */
class FakeMealieImportRepository : MealieImportRepository {

    var result: MealieResult = MealieResult.Success(MealieSummary(0, 0, 0, 0, 0, 0, null))

    /** When set, [import] waits for it: lets a test look at the state mid-import. */
    var gate: CompletableDeferred<Unit>? = null

    val imported = mutableListOf<String>()

    /** The `filing` each import was asked for (#387). */
    val filings = mutableListOf<Boolean>()

    override suspend fun import(uri: String, filing: Boolean): MealieResult {
        imported += uri
        filings += filing
        gate?.await()
        return result
    }
}

/** A [MealieFiles] that reads fixture files by URI, through the real archive reader. */
class FakeMealieFiles(
    private val limits: MealieLimits = MealieLimits(),
    private val photos: PhotoStore? = null
) : MealieFiles {

    val files = mutableMapOf<String, File>()

    override suspend fun read(uri: String): MealieRead =
        files[uri]?.let { MealieArchive.read(it, limits, photos?.let { store -> store::importBytes }) } ?: MealieRead.Failure(MealieError.READ_FAILED)
}
