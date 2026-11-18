package com.example.recipeclipper

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import javax.inject.Inject

/**
 * Every field [RecipeApp] keeps is one Hilt sets. A merge once dropped the `@Inject` above
 * `timerCountdowns`, and the app crashed on every start; the tests run on Hilt's test application,
 * never on [RecipeApp], so nothing else would notice.
 */
class RecipeAppInjectionTest {

    @Test
    fun everyFieldIsInjected() {
        val fields = RecipeApp::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertTrue(fields.isNotEmpty())
        val missing = fields.filterNot { it.isAnnotationPresent(Inject::class.java) }.map { it.name }
        assertTrue("not @Inject: $missing", missing.isEmpty())
    }
}
