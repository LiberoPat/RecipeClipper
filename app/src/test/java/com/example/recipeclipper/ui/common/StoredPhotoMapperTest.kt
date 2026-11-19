package com.example.recipeclipper.ui.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.request.Options
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A recipe's own picture (#386) reaches Coil as a file in the photo store, and nothing else does. */
@RunWith(AndroidJUnit4::class)
class StoredPhotoMapperTest {

    private val dir = File("/data/photos")
    private val options = Options(ApplicationProvider.getApplicationContext<Context>())

    @Test fun `a photo model is the file in the store`() {
        assertEquals(File(dir, "abc.jpg"), StoredPhotoMapper(dir).map("photo://abc.jpg", options))
    }

    @Test fun `a web link and a name with a path are left alone`() {
        val mapper = StoredPhotoMapper(dir)
        assertNull(mapper.map("https://img.example/a.jpg", options))
        assertNull(mapper.map("photo://../databases/recipe_clipper.db", options))
        assertNull(mapper.map("photo://a/b.jpg", options))
    }
}
