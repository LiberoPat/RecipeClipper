package com.example.recipeclipper.data

import android.content.Context
import androidx.core.net.toUri
import com.example.recipeclipper.data.remote.PaprikaArchive
import com.example.recipeclipper.data.remote.PaprikaError
import com.example.recipeclipper.data.remote.PaprikaLimits
import com.example.recipeclipper.data.remote.PaprikaRead
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * Where a picked Paprika file is read (#382): the only part of the import that needs a `Context`,
 * behind an interface so the repository never touches one and its test can use a fake. URIs
 * travel as strings, as in [BackupFiles].
 */
interface PaprikaFiles {

    /**
     * The picked file's recipes, or why not ([PaprikaError.READ_FAILED] if it couldn't be opened).
     * Each recipe's picture is stored as it is read (#386): the recipe names the stored file.
     */
    suspend fun read(uri: String): PaprikaRead
}

/**
 * Copies the picked file into the app's cache (a zip is read from its directory at the end, so it
 * needs a file, not a stream; the copy stops at [PaprikaLimits.maxFileBytes]), reads it with
 * [PaprikaArchive] on the IO dispatcher, storing each recipe's picture in the [PhotoStore] as it goes,
 * and deletes the copy. The copy's name is the app's own,
 * never anything the file says.
 */
class AndroidPaprikaFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: ErrorLog,
    private val photos: PhotoStore
) : PaprikaFiles {

    override suspend fun read(uri: String): PaprikaRead = withContext(Dispatchers.IO) {
        val limits = PaprikaLimits()
        val dir = File(context.cacheDir, DIR).apply { deleteRecursively(); mkdirs() }
        val copy = File(dir, UUID.randomUUID().toString() + ".paprikarecipes")
        try {
            val input = context.contentResolver.openInputStream(uri.toUri())
                ?: return@withContext PaprikaRead.Failure(PaprikaError.READ_FAILED)
            val copied = input.use { source ->
                copy.outputStream().buffered().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var within = true
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > limits.maxFileBytes) {
                            within = false
                            break
                        }
                        out.write(buffer, 0, n)
                    }
                    within
                }
            }
            if (!copied) PaprikaRead.Failure(PaprikaError.TOO_LARGE) else PaprikaArchive.read(copy, limits, photos::importBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("read paprika file failed", e)
            PaprikaRead.Failure(PaprikaError.READ_FAILED)
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val DIR = "import-paprika"
    }
}
