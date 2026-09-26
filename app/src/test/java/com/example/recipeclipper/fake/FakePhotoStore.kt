package com.example.recipeclipper.fake

import com.example.recipeclipper.data.PhotoStore
import java.io.File
import java.nio.file.Files

/**
 * A [PhotoStore] over a temporary folder: a picture's "source" is its text (no decoding or
 * downscaling, which is Android's part), one starting "bad" can't be read. Files are real, so
 * a backup round trip carries real bytes. [modified] backdates a file for the sweep's grace.
 */
class FakePhotoStore(val dir: File = Files.createTempDirectory("photos").toFile()) : PhotoStore {
    private var next = 1

    override suspend fun importPicture(source: String): String? =
        if (source.startsWith("bad")) null else write(source.toByteArray())

    override suspend fun adopt(path: String): String? = File(path).takeIf { it.isFile }?.let { write(it.readBytes()) }

    override fun path(name: String): String = File(dir, name).absolutePath

    override suspend fun files(): Map<String, Long> =
        dir.listFiles().orEmpty().associate { it.name to it.lastModified() }

    override suspend fun delete(names: Collection<String>) {
        names.forEach { File(dir, it).delete() }
    }

    fun bytes(name: String): String = File(dir, name).readText()

    fun modified(name: String, at: Long) {
        File(dir, name).setLastModified(at)
    }

    private fun write(bytes: ByteArray): String {
        val name = "stored-${next++}.jpg"
        File(dir, name).writeBytes(bytes)
        return name
    }
}
