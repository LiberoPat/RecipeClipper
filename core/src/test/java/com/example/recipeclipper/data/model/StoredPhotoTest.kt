package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A recipe's own stored picture (#386), as the screens name it to the image loader; iOS's StoredPhotoTests agree. */
class StoredPhotoTest {

    @Test fun `a file name is a photo model and back`() {
        assertEquals("photo://3f2a.jpg", StoredPhoto.model("3f2a.jpg"))
        assertEquals("3f2a.jpg", StoredPhoto.fileName("photo://3f2a.jpg"))
    }

    @Test fun `a model can carry the link to show when the file is missing`() {
        assertEquals("photo://a.jpg#https://img.example/a.jpg", StoredPhoto.model("a.jpg", "https://img.example/a.jpg"))
        assertEquals("a.jpg", StoredPhoto.fileName("photo://a.jpg#https://img.example/a.jpg"))
        assertEquals("https://img.example/a.jpg", StoredPhoto.fallback("photo://a.jpg#https://img.example/a.jpg"))
        // A link's own fragment is cut, a blank or missing link is none.
        assertEquals("photo://a.jpg#https://img.example/a.jpg", StoredPhoto.model("a.jpg", "https://img.example/a.jpg#top"))
        assertEquals("photo://a.jpg", StoredPhoto.model("a.jpg", ""))
        assertEquals("photo://a.jpg", StoredPhoto.model("a.jpg", null))
        assertNull(StoredPhoto.fallback("photo://a.jpg"))
        // Only a photo model has one.
        assertNull(StoredPhoto.fallback("https://img.example/a.jpg#x"))
        assertNull(StoredPhoto.fallback("photo://../x#https://img.example/a.jpg"))
    }

    @Test fun `a web link, or anything that isn't a plain file name, names no file`() {
        for (model in listOf(
            "https://img.example/a.jpg", "file:///etc/passwd", "photo://", "photo://../secret", "photo://a/b.jpg",
            "photo://..", "photo://.", "photo://a\\b.jpg", "PHOTO://a.jpg", "", " photo://a.jpg"
        )) {
            assertNull(model, StoredPhoto.fileName(model))
        }
    }

    @Test fun `a recipe shows its stored photo in place of its link`() {
        val recipe = Recipe(
            name = "A", image = "https://img.example/a.jpg", ingredients = listOf("1 egg"), instructions = emptyList(),
            prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = "https://x/a"
        )
        assertEquals("https://img.example/a.jpg", recipe.displayImage)
        assertEquals("photo://mine.jpg#https://img.example/a.jpg", recipe.copy(photoFile = "mine.jpg").displayImage)
        assertEquals("photo://mine.jpg", recipe.copy(image = null, photoFile = "mine.jpg").displayImage)
        assertNull(recipe.copy(image = null).displayImage)
        // The editor's link is untouched by it.
        assertEquals("https://img.example/a.jpg", recipe.copy(photoFile = "mine.jpg").image)
    }
}
