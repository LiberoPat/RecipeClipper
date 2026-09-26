package com.example.recipeclipper.fake

import com.example.recipeclipper.data.CookedPhotoRepository
import com.example.recipeclipper.data.model.CookedPhoto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * "I made this" (#116) in memory. A source starting "bad" can't be read, as a broken picture;
 * every other one becomes a photo cooked on [today]. Records edits and forgotten files.
 */
class FakeCookedPhotoRepository(var today: Long = 20_000L) : CookedPhotoRepository {

    val photos = MutableStateFlow<List<CookedPhoto>>(emptyList())
    val forgotten = mutableListOf<String>()
    val edits = mutableListOf<Triple<Long, Long, String?>>()
    var sweeps = 0
        private set
    private var nextId = 1L

    fun photo(recipeId: Long, day: Long = today, note: String? = null): CookedPhoto {
        val id = nextId++
        return CookedPhoto(id, recipeId, "p$id.jpg", "/photos/p$id.jpg", day, note, id, id, "uid-$id")
            .also { photos.value = photos.value + it }
    }

    override fun observe(recipeId: Long): Flow<List<CookedPhoto>> =
        photos.map { list -> list.filter { it.recipeId == recipeId }.sortedWith(compareByDescending<CookedPhoto> { it.day }.thenByDescending { it.id }) }

    override suspend fun add(recipeId: Long, sources: List<String>): List<CookedPhoto> =
        sources.filterNot { it.startsWith("bad") }.map { photo(recipeId) }

    override suspend fun edit(id: Long, day: Long, note: String?) {
        edits += Triple(id, day, note)
        photos.value = photos.value.map { if (it.id == id) it.copy(day = day, note = CookedPhoto.cleanNote(note)) else it }
    }

    override suspend fun delete(id: Long): CookedPhoto? {
        val found = photos.value.firstOrNull { it.id == id } ?: return null
        photos.value = photos.value - found
        return found
    }

    override suspend fun restore(photo: CookedPhoto) {
        photos.value = photos.value + photo
    }

    override suspend fun forget(photos: List<CookedPhoto>) {
        forgotten += photos.map { it.fileName }
    }

    override suspend fun sweep() {
        sweeps++
    }
}
