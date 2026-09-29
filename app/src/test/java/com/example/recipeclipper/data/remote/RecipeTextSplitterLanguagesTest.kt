package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.remote.RecipeTextSplitter.Section
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reddit posts and photo text in every language the app reads (#208): each language's own
 * headers and unit words (`shared/tables/<language>/splitter.json`), chosen by the text's words,
 * never merged. The fixtures (`shared/fixtures/languages`) are read by the iOS suite too
 * (`RecipeTextSplitterLanguagesTests.swift`), with the same expectations.
 */
class RecipeTextSplitterLanguagesTest {

    private val languages = listOf("en", "de", "es", "fr", "it", "pt", "ja")

    private fun fixture(language: String): JSONObject = JSONObject(
        javaClass.getResourceAsStream("/languages/$language.json")!!.bufferedReader().use { it.readText() }
    )

    private fun strings(o: JSONObject, key: String): List<String> {
        val a = o.getJSONArray(key)
        return List(a.length()) { a.getString(it) }
    }

    private fun optional(o: JSONObject, key: String): String? = if (o.has(key)) o.getString(key) else null

    @Test fun `a post splits in its own language`() {
        for (language in languages) {
            val post = fixture(language).getJSONObject("post")
            val split = RecipeTextSplitter.detectAndSplit(post.getString("body"), context = post.getString("title"))
                ?: throw AssertionError("$language: no split")
            assertEquals(language, language, split.language)
            assertEquals(language, strings(post, "ingredients"), split.ingredients)
            assertEquals(language, strings(post, "instructions"), split.instructions)
            assertEquals(language, optional(post, "yield"), split.yield)
            assertEquals(language, optional(post, "prepTime"), split.prepTime)
            assertEquals(language, optional(post, "cookTime"), split.cookTime)
            assertEquals(language, optional(post, "totalTime"), split.totalTime)
        }
    }

    @Test fun `a photo's lines sort in their own language, with glued units to check`() {
        for (language in languages) {
            val photo = fixture(language).getJSONObject("photo")
            val reading = PhotoTextSorter.sort(strings(photo, "lines").map { PhotoLine(it) })
            assertTrue(language, reading.sorted)
            assertEquals(language, language, reading.language)
            assertEquals(language, strings(photo, "ingredients"), reading.ingredients)
            assertEquals(language, strings(photo, "instructions"), reading.instructions)
            assertEquals(language, strings(photo, "uncertain"), reading.uncertain)
        }
    }

    @Test fun `prose and requests never split, in any language`() {
        for (language in languages) {
            for (text in strings(fixture(language), "negatives")) {
                assertNull("$language: $text", RecipeTextSplitter.detectAndSplit(text))
                val lines = text.replace(Regex("([.!?。！？])\\s*"), "$1\n").split("\n").filter { it.isNotBlank() }.map { PhotoLine(it) }
                assertFalse("$language: $text", PhotoTextSorter.sort(lines).sorted)
            }
        }
    }

    @Test fun `languages are never merged`() {
        // Another language's words know none of the headers: what's left is only the structure
        // (a numbered list under amounts), so the headers are read as lines, if it splits at all.
        for (language in languages - "en") {
            val post = fixture(language).getJSONObject("post")
            val english = RecipeTextSplitter.split(post.getString("body"), LanguageWords.ENGLISH)
            assertTrue(language, english == null || english.ingredients != strings(post, "ingredients"))
        }
        val post = fixture("en").getJSONObject("post")
        for (language in languages - "en") {
            val other = RecipeTextSplitter.split(post.getString("body"), LanguageWords.forTag(language)!!)
            assertTrue(language, other == null || other.ingredients != strings(post, "ingredients"))
        }
    }

    @Test fun `text whose words say nothing clear is read in English`() {
        assertNull(RecipeTextSplitter.languageOf("Zutaten\n2 Eier"))
        assertEquals(LanguageWords.ENGLISH, RecipeTextSplitter.wordsFor(null))
    }

    private fun section(raw: String, language: String): Section? =
        LanguageWords.forTag(language)!!.let { RecipeTextSplitter.header(RecipeTextSplitter.line(raw, it), it)?.section }

    private fun label(raw: String, language: String): String? =
        LanguageWords.forTag(language)!!.let { RecipeTextSplitter.header(RecipeTextSplitter.line(raw, it), it)?.label }

    @Test fun `each language's headers, with the fuzzy rules`() {
        // Whole lines, with what may surround them.
        assertEquals(Section.INGREDIENTS, section("Zutaten:", "de"))
        assertEquals(Section.INSTRUCTIONS, section("## Anleitung", "de"))
        assertEquals(Section.INGREDIENTS, section("Ingredientes (para 4)", "es"))
        assertEquals(Section.INSTRUCTIONS, section("Elaboración:", "es"))
        assertEquals(Section.INSTRUCTIONS, section("Étapes :", "fr"))
        assertEquals(Section.INSTRUCTIONS, section("Instructions", "fr"))
        assertEquals(Section.INSTRUCTIONS, section("PROCEDIMENTO", "it"))
        assertEquals(Section.INSTRUCTIONS, section("Modo de preparo:", "pt"))
        assertEquals(Section.INSTRUCTIONS, section("Preparo", "pt"))
        assertEquals(Section.INGREDIENTS, section("■材料", "ja"))
        assertEquals(Section.INSTRUCTIONS, section("手順", "ja"))
        assertEquals(Section.END, section("Tipps:", "de"))
        assertEquals(Section.END, section("Astuces", "fr"))
        assertEquals(Section.END, section("Edit: Tippfehler", "de"))
        // A servings phrase needs no colon; any other "for" header does.
        assertEquals(Section.INGREDIENTS, section("Ingredienti per 4 persone", "it"))
        assertEquals(Section.INGREDIENTS, section("Zutaten für 4 Personen", "de"))
        assertEquals(Section.INGREDIENTS, section("Ingrédients pour la pâte :", "fr"))
        assertNull(section("Ingredientes para esta receta son baratos", "es"))
        // Words beside the keyword: before it in German, after it in the Romance languages.
        assertEquals("Trockene Zutaten:", label("**Trockene Zutaten**", "de"))
        assertEquals("Ingredientes secos:", label("**Ingredientes secos**", "es"))
        assertEquals("Ingrédients secs:", label("**Ingrédients secs**", "fr"))
        assertEquals("Ingredienti per la crema:", label("**Ingredienti per la crema**", "it"))
        assertEquals("Ingredientes da cobertura:", label("**Ingredientes da cobertura**", "pt"))
        assertEquals(Section.INGREDIENTS, section("**Die Zutaten**", "de"))
        assertNull(label("**Die Zutaten**", "de"))
        assertNull(label("**Lista de ingredientes**", "es"))
        // A step is never a header.
        assertNull(section("**Mélanger les ingrédients**", "fr"))
        assertNull(section("**Mezclar los ingredientes**", "es"))
        assertNull(section("**Alle Zutaten verrühren**", "de"))
        // Typos two letters off, in a header set apart as one.
        assertEquals(Section.INSTRUCTIONS, section("ZUBEREITNUG", "de"))
        assertEquals(Section.INGREDIENTS, section("**Ingedientes**", "es"))
        assertEquals(Section.INGREDIENTS, section("**Ingrédiens**", "fr"))
        assertEquals(Section.INSTRUCTIONS, section("**Procedimeto**", "it"))
        assertEquals(Section.INGREDIENTS, section("**Ingredintes**", "pt"))
        assertNull(section("Ingedientes", "es"))
        // Numbered steps by the language's own label.
        val schritt = RecipeTextSplitter.line("Schritt 2: Rühren.", LanguageWords.forTag("de")!!)
        assertEquals(2, schritt.number)
        assertEquals("Rühren.", schritt.text)
        assertEquals(1, RecipeTextSplitter.line("Paso 1. Mezclar.", LanguageWords.forTag("es")!!).number)
        assertEquals(3, RecipeTextSplitter.line("Passo 3 - Assar.", LanguageWords.forTag("pt")!!).number)
    }

    @Test fun `glued units in each language, and only there`() {
        fun suspect(line: String, language: String) = PhotoTextSorter.suspect(line, LanguageWords.forTag(language)!!)
        assertTrue(suspect("2 ELZucker", "de"))
        assertTrue(suspect("1 TLSalz", "de"))
        assertTrue(suspect("2 Esslöffelzucker", "de"))
        assertTrue(suspect("1 tazaharina", "es"))
        assertTrue(suspect("2 cucharadasazúcar", "es"))
        assertTrue(suspect("1 c. à soupesucre", "fr"))
        assertTrue(suspect("2 cuillères à soupesucre", "fr"))
        assertTrue(suspect("2 cucchiaizucchero", "it"))
        assertTrue(suspect("1 xícaraleite", "pt"))
        assertTrue(suspect("2 colheresaçúcar", "pt"))
        // Units the scaler reads, ordinary words, and short units left out.
        assertFalse(suspect("2 EL Zucker", "de"))
        assertFalse(suspect("2 Eier", "de"))
        assertFalse(suspect("2 Elche", "de"))
        assertFalse(suspect("1 taza de harina", "es"))
        assertFalse(suspect("2 cucharaditas de sal", "es"))
        assertFalse(suspect("2 cuillères de sucre", "fr"))
        assertFalse(suspect("100 g cassonade", "fr"))
        assertFalse(suspect("2 cassonade", "fr"))
        assertFalse(suspect("1 cucchiaino di sale", "it"))
        assertFalse(suspect("1 litro di latte", "it"))
        assertFalse(suspect("2 colheres de sopa de açúcar", "pt"))
        assertFalse(suspect("2 gramas de sal", "pt"))
        // Japanese has no spaces, so no glued-unit check; the other shapes still apply.
        assertFalse(suspect("砂糖 大さじ2", "ja"))
        assertTrue(suspect("11/2 カップ", "ja"))
        // Languages never merged: English units aren't read in German, nor German ones in English.
        assertFalse(suspect("1 cupraisins", "de"))
        assertFalse(suspect("2 ELZucker", "en"))
    }

    @Test fun `a German post keeps German as the recipe's language`() {
        val post = fixture("de").getJSONObject("post")
        val listing = JSONArray()
            .put(listing("t3", JSONObject().put("title", post.getString("title")).put("selftext", post.getString("body"))))
            .put(listing("t1", null))
        val result = RedditRecipeParser.parse(listing.toString(), "https://www.reddit.com/r/Kochen/comments/abc/x/")
        val recipe = (result as ParseResult.Success).recipe
        assertEquals("de", recipe.language)
        assertEquals(strings(post, "ingredients"), recipe.ingredients)
    }

    @Test fun `a French recipe in the poster's comment is read in French`() {
        val post = fixture("fr").getJSONObject("post")
        val comment = JSONObject().put("body", post.getString("body")).put("is_submitter", true).put("author", "op")
        val listing = JSONArray()
            .put(listing("t3", JSONObject().put("title", post.getString("title")).put("selftext", "")))
            .put(listing("t1", comment))
        val result = RedditRecipeParser.parse(listing.toString(), "https://www.reddit.com/r/cuisine/comments/abc/x/")
        val recipe = (result as ParseResult.Success).recipe
        assertEquals("fr", recipe.language)
        assertEquals(strings(post, "instructions"), recipe.instructions)
    }

    /** A Reddit listing holding one child of [kind], or none. */
    private fun listing(kind: String, data: JSONObject?): JSONObject {
        val children = JSONArray()
        if (data != null) children.put(JSONObject().put("kind", kind).put("data", data))
        return JSONObject().put("kind", "Listing").put("data", JSONObject().put("children", children))
    }
}
