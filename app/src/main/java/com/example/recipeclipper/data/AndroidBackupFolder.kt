package com.example.recipeclipper.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.example.recipeclipper.data.backup.BackupArchive
import com.example.recipeclipper.data.backup.BackupDestination
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * [BackupFolder] over the Storage Access Framework: a document tree (Google Drive, a local
 * folder, any provider) picked with `ACTION_OPEN_DOCUMENT_TREE`, kept through a persisted URI
 * permission. Every call runs on the IO dispatcher and degrades to null or a no-op on failure.
 */
class AndroidBackupFolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: ErrorLog
) : BackupFolder {

    private val resolver get() = context.contentResolver

    override fun destination(folderUri: String?): BackupDestination {
        if (folderUri == null) return BackupDestination.NOT_CHOSEN
        val uri = folderUri.toUri()
        val held = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        return if (held) BackupDestination.READY else BackupDestination.LOST
    }

    override fun adopt(folderUri: String): String? = try {
        val uri = folderUri.toUri()
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        displayName(DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)))
            ?: folderUri.toUri().lastPathSegment.orEmpty()
    } catch (e: Exception) {
        log.error("adopt backup folder failed", e)
        null
    }

    override fun release(folderUri: String) {
        try {
            resolver.releasePersistableUriPermission(
                folderUri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            log.error("release backup folder failed", e)
        }
    }

    override suspend fun list(folderUri: String): List<String>? = withContext(Dispatchers.IO) {
        guard("list backup folder") { children(folderUri.toUri()).keys.toList() }
    }

    override suspend fun write(folderUri: String, name: String, json: String, photos: Map<String, String>): String? =
        withContext(Dispatchers.IO) {
            val tree = folderUri.toUri()
            var created: Uri? = null
            try {
                val existing = children(tree)[name]
                val document = existing?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
                    ?: DocumentsContract.createDocument(resolver, root(tree), ZIP, name)?.also { created = it }
                    ?: return@withContext null
                val out = resolver.openOutputStream(document, if (existing != null) "wt" else "w")
                    ?: throw IllegalStateException("no output stream")
                out.buffered().use { BackupArchive.write(it, json, photos.mapValues { File(it.value) }) }
                displayName(document) ?: name
            } catch (e: Exception) {
                // A half-written copy must not stand in for a good one; the older copies stay.
                created?.let { partial -> withContext(NonCancellable) { guard("delete partial backup") { DocumentsContract.deleteDocument(resolver, partial) } } }
                if (e is CancellationException) throw e
                log.error("write backup copy failed", e)
                null
            }
        }

    override suspend fun delete(folderUri: String, name: String) {
        withContext(Dispatchers.IO) {
            guard("delete old backup copy") {
                val tree = folderUri.toUri()
                children(tree)[name]?.let { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it)) }
            }
        }
    }

    private fun root(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** Display name to document id, for the folder's direct children. */
    private fun children(tree: Uri): Map<String, String> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val found = LinkedHashMap<String, String>()
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                found[name] = id
            }
        } ?: throw IllegalStateException("folder can't be listed")
        return found
    }

    private fun displayName(document: Uri): String? = try {
        resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private inline fun <T> guard(what: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("$what failed", e)
        null
    }

    private companion object {
        const val ZIP = "application/zip"
    }
}
