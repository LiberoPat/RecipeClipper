package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.StepPhotos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step photos read from JSON-LD's `HowToStep.image` (#433). */
class StepPhotosParserTest {

    private val sourceUrl = "https://example.com/recipe"

    private fun parse(steps: String): Recipe =
        JsonLdRecipeParser.parse(listOf("""{"@type":"Recipe","name":"Pie","recipeIngredient":["1 egg"],"recipeInstructions":$steps}"""), sourceUrl)!!

    private fun fixture(name: String): Recipe {
        val block = javaClass.getResourceAsStream("/jsonld/$name.json")!!.bufferedReader().use { it.readText() }
        return JsonLdRecipeParser.parse(listOf(block), "https://www.cookist.it/fagottini-di-mortadella-ricetta/")!!
    }

    @Test
    fun `Cookist gives each step its photo, and the markers number them`() {
        val recipe = fixture("cookist-fagottini-di-mortadella")
        assertEquals(8, recipe.instructions.size)
        assertEquals(8, recipe.stepPhotos.size)
        assertTrue(recipe.stepPhotos.all { it.size == 1 })
        assertEquals(
            "https://staticcookist.akamaized.net/wp-content/uploads/sites/21/2024/01/Fagottini-di-mortadella_farcire_mortadella_1-1200x675.jpg",
            recipe.stepPhotos[0][0]
        )
        // The markers stay in the text as written.
        assertEquals("Stendi le fette di mortadella su un tagliere (1).", recipe.instructions[0])
        val numbered = StepPhotos.numbered(recipe.instructions, recipe.stepPhotos)
        assertEquals((1..8).toList(), numbered.flatten().map { it.number })
        assertEquals((0..7).toList(), numbered.flatten().map { it.position })
    }

    @Test
    fun `a string, an ImageObject and an array of either are all read`() {
        val recipe = parse(
            """[
              {"@type":"HowToStep","text":"One (1).","image":"https://img.example/1.jpg"},
              {"@type":"HowToStep","text":"Two (2) (3).","image":[{"@type":"ImageObject","url":"https://img.example/2.jpg"},"https://img.example/3.jpg"]},
              {"@type":"HowToStep","text":"Three.","image":{"@type":"ImageObject","contentUrl":"http://img.example/4.jpg"}},
              {"@type":"HowToStep","text":"Four."}
            ]"""
        )
        assertEquals(
            listOf(
                listOf("https://img.example/1.jpg"),
                listOf("https://img.example/2.jpg", "https://img.example/3.jpg"),
                listOf("https://img.example/4.jpg"),
                emptyList()
            ),
            recipe.stepPhotos
        )
    }

    @Test
    fun `no step photo is no list at all`() {
        assertEquals(emptyList<List<String>>(), parse("""[{"@type":"HowToStep","text":"Mix."},"Bake."]""").stepPhotos)
        assertEquals(emptyList<List<String>>(), parse("\"Mix.\\nBake.\"").stepPhotos)
    }

    @Test
    fun `only web images, each once, at most eight a step`() {
        val many = (1..12).joinToString(",") { "\"https://img.example/$it.jpg\"" }
        val recipe = parse(
            """[
              {"@type":"HowToStep","text":"One.","image":["file:///data/x.jpg","data:image/gif;base64,AA","/rel.jpg","https://img.example/a.jpg","https://img.example/a.jpg"]},
              {"@type":"HowToStep","text":"Two.","image":[$many]}
            ]"""
        )
        assertEquals(listOf("https://img.example/a.jpg"), recipe.stepPhotos[0])
        assertEquals(StepPhotos.MAX_PER_STEP, recipe.stepPhotos[1].size)
    }

    @Test
    fun `sections are flattened with their steps' photos, and string steps between them have none`() {
        val recipe = parse(
            """[
              {"@type":"HowToSection","name":"Dough","itemListElement":[
                {"@type":"HowToStep","text":"Knead.","image":"https://img.example/k.jpg"},
                "Rest."
              ]},
              {"@type":"HowToSection","name":"Filling","itemListElement":[
                {"@type":"HowToStep","text":"Fill.","image":"https://img.example/f.jpg"}
              ]}
            ]"""
        )
        assertEquals(listOf("Knead.", "Rest.", "Fill."), recipe.instructions)
        assertEquals(
            listOf(listOf("https://img.example/k.jpg"), emptyList(), listOf("https://img.example/f.jpg")),
            recipe.stepPhotos
        )
    }

    @Test
    fun `a blank step is dropped with its photo`() {
        val recipe = parse(
            """[
              {"@type":"HowToStep","text":" ","image":"https://img.example/blank.jpg"},
              {"@type":"HowToStep","text":"Bake.","image":"https://img.example/bake.jpg"}
            ]"""
        )
        assertEquals(listOf("Bake."), recipe.instructions)
        assertEquals(listOf(listOf("https://img.example/bake.jpg")), recipe.stepPhotos)
    }

    @Test
    fun `a site rule that drops the last step drops its photos too`() {
        val html = """
            <html><head><script type="application/ld+json">
            {"@type":"Recipe","name":"Pie","recipeIngredient":["1 egg"],"recipeInstructions":[
              {"@type":"HowToStep","text":"Bake.","image":"https://img.example/1.jpg"},
              {"@type":"HowToStep","text":"Rest.","image":"https://img.example/2.jpg"},
              {"@type":"HowToStep","text":"Editor’s note: first published in 2019.","image":"https://img.example/3.jpg"}
            ]}
            </script></head><body></body></html>
        """.trimIndent()
        val parsed = BlogPageParser.parse(html, "https://www.bonappetit.com/recipe/pie")
        val recipe = (parsed as com.example.recipeclipper.data.model.ParseResult.Success).recipe
        assertEquals(listOf("Bake.", "Rest."), recipe.instructions)
        assertEquals(listOf(listOf("https://img.example/1.jpg"), listOf("https://img.example/2.jpg")), recipe.stepPhotos)
    }
}
