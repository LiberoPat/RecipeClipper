package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.dao.CookedPhotoDao
import com.example.recipeclipper.data.local.entity.CookedPhotoEntity
import com.example.recipeclipper.data.model.CookedPhoto
import com.example.recipeclipper.data.model.PlanDays
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "I made this" (#116): the user's own photos of a recipe, each with a day and a short note.
 * A photo's file outlives its row until the delete stands (so Undo can bring it back); [sweep]
 * removes files no row names.
 */
interface CookedPhotoRepository {

    /** [recipeId]'s photos, newest cook first. */
    fun observe(recipeId: Long): Flow<List<CookedPhoto>>

    /**
     * Stores the pictures at [sources] (Photo Picker or camera URIs) downscaled, one entry each,
     * cooked today with no note. The new entries, in order; one that couldn't be read is left out.
     */
    suspend fun add(recipeId: Long, sources: List<String>): List<CookedPhoto>

    suspend fun edit(id: Long, day: Long, note: String?)

    /** Deletes the entry; its file stays until [forget] or [sweep], so [restore] can undo it. */
    suspend fun delete(id: Long): CookedPhoto?

    suspend fun restore(photo: CookedPhoto)

    /** The delete stands: removes the files of [photos]. */
    suspend fun forget(photos: List<CookedPhoto>)

    /**
     * Removes stored files no entry names, except very recent ones (an add in flight). Never an
     * entry: one whose file is missing (restored from Android's backup) keeps its day and note.
     */
    suspend fun sweep()
}

@Singleton
class DefaultCookedPhotoRepository @Inject constructor(
    private val dao: CookedPhotoDao,
    private val store: PhotoStore,
    private val clock: Clock,
    private val log: ErrorLog
) : CookedPhotoRepository {

    override fun observe(recipeId: Long): Flow<List<CookedPhoto>> =
        dao.observeFor(recipeId).map { rows -> rows.map { it.toDomain() } }.orEmptyOnError(log, "observeCookedPhotos")

    override suspend fun add(recipeId: Long, sources: List<String>): List<CookedPhoto> = sources.mapNotNull { source ->
        val name = store.importPicture(source) ?: return@mapNotNull null
        val now = clock.now()
        val entity = CookedPhotoEntity(
            recipeId = recipeId, fileName = name, day = PlanDays.today(now), note = null, createdAt = now, updatedAt = now
        )
        val id = log.guard("addCookedPhoto", null) { dao.insert(entity) }
        if (id == null) {
            store.delete(listOf(name))
            null
        } else {
            entity.copy(id = id).toDomain()
        }
    }

    override suspend fun edit(id: Long, day: Long, note: String?) =
        log.guard("editCookedPhoto", Unit) { dao.edit(id, day, CookedPhoto.cleanNote(note), clock.now()) }

    override suspend fun delete(id: Long): CookedPhoto? = log.guard("deleteCookedPhoto", null) {
        val row = dao.get(id) ?: return@guard null
        dao.delete(id)
        row.toDomain()
    }

    override suspend fun restore(photo: CookedPhoto) =
        log.guard("restoreCookedPhoto", Unit) { dao.put(listOf(photo.toEntity())) }

    override suspend fun forget(photos: List<CookedPhoto>) {
        if (photos.isNotEmpty()) store.delete(photos.map { it.fileName })
    }

    override suspend fun sweep() {
        val named = log.guard("sweepCookedPhotos", null) { dao.fileNames().toHashSet() } ?: return
        val cutoff = clock.now() - PhotoStore.SWEEP_GRACE_MILLIS
        val orphans = store.files().filter { (name, written) -> name !in named && written < cutoff }.keys
        store.delete(orphans)
    }

    private fun CookedPhotoEntity.toDomain() =
        CookedPhoto(id, recipeId, fileName, store.path(fileName), day, note, createdAt, updatedAt, uid, store.exists(fileName))
}

internal fun CookedPhoto.toEntity() =
    CookedPhotoEntity(id, recipeId, fileName, day, note, createdAt, updatedAt, uid)
