package com.example.recipeclipper.data.backup

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * An export with photos (#116): a zip holding [JSON_ENTRY] (the same JSON as a plain export) and
 * each picture at its `photos/…` path. Every entry is STORED, not deflated: JPEGs don't shrink,
 * and iOS, which has no zip library, reads and writes only that. A file that doesn't start with
 * a zip's signature is read as a plain JSON export, so every older backup still imports.
 */
object BackupArchive {
    const val JSON_ENTRY = "backup.json"

    /** Far beyond any real photo, which the store keeps to 2048 px JPEGs of a few hundred KB. */
    const val MAX_PHOTO_BYTES = 30L * 1024 * 1024

    /** True if [head] (a file's first bytes) is a zip's local file header signature. */
    fun isZip(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            head[2] == 3.toByte() && head[3] == 4.toByte()

    /** Writes [json] and the [photos] (path in the zip to a local file; missing files are skipped). */
    fun write(out: OutputStream, json: String, photos: Map<String, File>) {
        ZipOutputStream(out).use { zip ->
            zip.setMethod(ZipOutputStream.STORED)
            zip.putStored(JSON_ENTRY, json.toByteArray(Charsets.UTF_8))
            photos.forEach { (path, file) ->
                if (BackupJson.PHOTO_FILE.matches(path) && file.isFile) zip.putStored(path, file.readBytes())
            }
        }
    }

    private fun ZipOutputStream.putStored(name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        putNextEntry(
            ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                this.crc = crc.value
            }
        )
        write(bytes)
        closeEntry()
    }

    /**
     * Reads a zip export: its JSON (at most [maxJsonBytes]) and its pictures, each copied into
     * [photoDir] (which is emptied first). Entries other than those two kinds are ignored.
     */
    fun read(input: InputStream, photoDir: File, maxJsonBytes: Int): BackupResult<BackupPackage> {
        photoDir.deleteRecursively()
        photoDir.mkdirs()
        var json: String? = null
        val photos = LinkedHashMap<String, String>()
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.isDirectory -> Unit
                        entry.name == JSON_ENTRY -> {
                            val bytes = zip.readAtMost(maxJsonBytes.toLong())
                                ?: return BackupResult.Failure(BackupError.NotABackup)
                            json = bytes.toString(Charsets.UTF_8.name())
                        }
                        BackupJson.PHOTO_FILE.matches(entry.name) && entry.name !in photos -> {
                            // Too big for a stored photo: left out, like a missing picture.
                            val bytes = zip.readAtMost(MAX_PHOTO_BYTES)
                            if (bytes != null) {
                                val file = File(photoDir, "${photos.size}.jpg")
                                file.outputStream().buffered().use { bytes.writeTo(it) }
                                photos[entry.name] = file.absolutePath
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // A damaged or truncated zip (ZipException, EOFException): not an export we can read.
            return BackupResult.Failure(BackupError.NotABackup)
        }
        val text = json ?: return BackupResult.Failure(BackupError.NotABackup)
        return BackupResult.Success(BackupPackage(text, photos))
    }

    /** The current entry's bytes, or null if it's longer than [max]. */
    private fun InputStream.readAtMost(max: Long): ByteArrayOutputStream? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) return out
            out.write(buffer, 0, n)
            if (out.size() > max) return null
        }
    }
}
