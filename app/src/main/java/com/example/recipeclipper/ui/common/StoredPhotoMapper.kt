package com.example.recipeclipper.ui.common

import coil.map.Mapper
import coil.request.Options
import com.example.recipeclipper.data.model.StoredPhoto
import java.io.File

/**
 * Turns a recipe's `photo://<name>` picture (#386, [StoredPhoto]) into the file in the photo store
 * ([dir]), so `AsyncImage(model = recipe.imageUrl)` shows a stored photo and a web link alike. A file
 * that isn't there (a phone restored from Android's backup, a cleared store) gives the recipe's web
 * link when the model carries one, as before the photo existed, and nothing (Coil's usual placeholder)
 * when it doesn't; the row keeps its `photoFile`, since an import may bring the file back. The check
 * is a stat, in Coil's own pipeline, never a decode on the main thread. Any other string is left for
 * Coil's own mappers, and a link given back goes through them too. [StoredPhoto.fileName] allows only
 * a plain name, so a model can't point outside [dir].
 */
class StoredPhotoMapper(private val dir: File) : Mapper<String, Any> {
    override fun map(data: String, options: Options): Any? {
        val name = StoredPhoto.fileName(data) ?: return null
        val file = File(dir, name)
        return if (file.isFile) file else StoredPhoto.fallback(data)
    }
}
