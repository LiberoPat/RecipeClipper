package com.example.recipeclipper.ui.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.request.Options
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * A recipe's own picture (#386) reaches Coil as the file in the photo store when it is there, as the
 * recipe's web link when the file is missing, and as nothing (Coil's placeholder) when it is missing
 * and there is no link; no other string is touched.
 */
@RunWith(AndroidJUnit4::class)
class StoredPhotoMapperTest {

    @get:Rule val folder = TemporaryFolder()

    private val options = Options(ApplicationProvider.getApplicationContext<Context>())
    private fun mapper() = StoredPhotoMapper(folder.root)

    @Test fun `a photo that is there is the file in the store, whatever link is behind it`() {
        val file = folder.newFile("abc.jpg")
        assertEquals(file, mapper().map("photo://abc.jpg", options))
        assertEquals(file, mapper().map("photo://abc.jpg#https://img.example/a.jpg", options))
    }

    @Test fun `a photo whose file is missing shows the recipe's link as before`() {
        assertEquals("https://img.example/a.jpg", mapper().map("photo://gone.jpg#https://img.example/a.jpg", options))
    }

    @Test fun `a photo whose file is missing, with no link, is nothing for Coil's placeholder`() {
        assertNull(mapper().map("photo://gone.jpg", options))
    }

    @Test fun `the row is not touched by a miss, and the file coming back shows again`() {
        val model = "photo://later.jpg#https://img.example/a.jpg"
        assertEquals("https://img.example/a.jpg", mapper().map(model, options))
        val file = folder.newFile("later.jpg")
        assertEquals(file, mapper().map(model, options))
    }

    @Test fun `a web link and a name with a path are left alone`() {
        assertNull(mapper().map("https://img.example/a.jpg", options))
        assertNull(mapper().map("photo://../databases/recipe_clipper.db", options))
        assertNull(mapper().map("photo://a/b.jpg#https://img.example/a.jpg", options))
    }
}
