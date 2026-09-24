package com.example.recipeclipper.data

import android.content.Context
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import com.example.recipeclipper.data.backup.BackupError
import com.example.recipeclipper.data.backup.BackupResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Where an export file is written and a picked one is read: the only part of export and import
 * that needs a `Context`, behind an interface so the Settings ViewModel never touches one and
 * its test can use a fake. URIs travel as strings for the same reason.
 */
interface BackupFiles {

    /** Writes [json] as `recipe-clipper-YYYY-MM-DD.json` and returns a URI the share sheet can
     *  read, or null if it couldn't be written. */
    suspend fun writeExport(json: String, exportedAt: Long): String?

    /** The text of the picked file, [BackupError.ReadFailed] if it couldn't be read, or
     *  [BackupError.NotABackup] if it's far bigger than any export. */
    suspend fun readText(uri: String): BackupResult<String>

    companion object {
        /** Far beyond any real export (a few hundred recipes is well under 2 MB). */
        const val MAX_BYTES = 20 * 1024 * 1024

        fun fileName(exportedAt: Long): String =
            "recipe-clipper-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(exportedAt)) + ".json"
    }
}

/** Writes into `cacheDir/exports/` (shared through the FileProvider in the manifest) and reads
 *  through the ContentResolver, on the IO dispatcher. */
class AndroidBackupFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: ErrorLog
) : BackupFiles {

    override suspend fun writeExport(json: String, exportedAt: Long): String? = withContext(Dispatchers.IO) {
        try {
            // The cache, so the system may clear it; one file a day, overwritten by a re-export.
            val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            val file = File(dir, BackupFiles.fileName(exportedAt))
            file.writeText(json, Charsets.UTF_8)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("writeExport failed", e)
            null
        }
    }

    override suspend fun readText(uri: String): BackupResult<String> = withContext(Dispatchers.IO) {
        try {
            val input = context.contentResolver.openInputStream(uri.toUri())
                ?: return@withContext BackupResult.Failure(BackupError.ReadFailed)
            input.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > BackupFiles.MAX_BYTES) {
                        return@withContext BackupResult.Failure(BackupError.NotABackup)
                    }
                }
                BackupResult.Success(out.toString(Charsets.UTF_8.name()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("readText failed", e)
            BackupResult.Failure(BackupError.ReadFailed)
        }
    }

    private companion object {
        const val EXPORT_DIR = "exports"
    }
}
