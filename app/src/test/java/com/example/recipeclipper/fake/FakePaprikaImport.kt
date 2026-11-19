package com.example.recipeclipper.fake

import com.example.recipeclipper.data.PaprikaFiles
import com.example.recipeclipper.data.PhotoStore
import com.example.recipeclipper.data.PaprikaImportRepository
import com.example.recipeclipper.data.PaprikaResult
import com.example.recipeclipper.data.backup.PaprikaSummary
import com.example.recipeclipper.data.remote.PaprikaArchive
import com.example.recipeclipper.data.remote.PaprikaError
import com.example.recipeclipper.data.remote.PaprikaLimits
import com.example.recipeclipper.data.remote.PaprikaRead
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/** Answers an import with whatever the test staged, recording what it was given. */
class FakePaprikaImportRepository : PaprikaImportRepository {

    var result: PaprikaResult = PaprikaResult.Success(PaprikaSummary(0, 0, 0, 0, 0, 0, null))

    /** When set, [import] waits for it: lets a test look at the state mid-import. */
    var gate: CompletableDeferred<Unit>? = null

    val imported = mutableListOf<String>()

    /** The `filing` each import was asked for (#387). */
    val filings = mutableListOf<Boolean>()

    override suspend fun import(uri: String, filing: Boolean): PaprikaResult {
        imported += uri
        filings += filing
        gate?.await()
        return result
    }
}

/** A [PaprikaFiles] that reads fixture files by URI, through the real archive reader. */
class FakePaprikaFiles(
    private val limits: PaprikaLimits = PaprikaLimits(),
    private val photos: PhotoStore? = null
) : PaprikaFiles {

    val files = mutableMapOf<String, File>()

    override suspend fun read(uri: String): PaprikaRead =
        files[uri]?.let { PaprikaArchive.read(it, limits, photos?.let { store -> store::importBytes }) } ?: PaprikaRead.Failure(PaprikaError.READ_FAILED)
}
