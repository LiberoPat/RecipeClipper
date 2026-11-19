package com.example.recipeclipper.ui.common

import coil.map.Mapper
import coil.request.Options
import com.example.recipeclipper.data.model.StoredPhoto
import java.io.File

/**
 * Turns a recipe's `photo://<name>` picture (#386, [StoredPhoto]) into the file in the photo store
 * ([dir]), so `AsyncImage(model = recipe.imageUrl)` shows a stored photo and a web link alike. Any
 * other string is left for Coil's own mappers. [StoredPhoto.fileName] allows only a plain name, so
 * a model can't point outside [dir].
 */
class StoredPhotoMapper(private val dir: File) : Mapper<String, File> {
    override fun map(data: String, options: Options): File? = StoredPhoto.fileName(data)?.let { File(dir, it) }
}
