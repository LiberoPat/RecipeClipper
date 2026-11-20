package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerishablesTest {

    private val en = LanguageWords.ENGLISH

    private fun group(line: String, words: LanguageWords? = en) = Perishables.find(line, words)?.group?.key

    @Test fun `each group matches its ingredients, by the end of the name`() {
        assertEquals("fish", group("2 salmon fillets (about 6 oz each), skin on"))
        assertEquals("fish", group("1 lb large shrimp, peeled and deveined"))
        assertEquals("fish", group("4 cod fillets"))
        assertEquals("fish", group("12 mussels, scrubbed"))
        assertEquals("mince", group("1 lb ground beef"))
        assertEquals("mince", group("500 g pork mince"))
        assertEquals("mince", group("1 lb Italian sausage"))
        assertEquals("poultry", group("2 boneless skinless chicken breasts"))
        assertEquals("poultry", group("6 chicken thighs"))
        assertEquals("poultry", group("1 whole chicken (about 4 lb)"))
        assertEquals("meatCuts", group("2 ribeye steaks"))
        assertEquals("meatCuts", group("1 lb pork tenderloin"))
        assertEquals("mushrooms", group("8 oz button mushrooms, sliced"))
        assertEquals("mushrooms", group("200 g shiitake"))
        assertEquals("avocado", group("2 ripe avocados"))
        assertEquals("parsley", group("2 tbsp chopped fresh parsley"))
        // Foods that keep a week or more (FoodKeeper: basil 10 days, cilantro and mint 2-3 weeks, herbs 7-10 days) are off the table.
        assertNull(group("1/2 cup fresh basil leaves"))
        assertNull(group("1 bunch cilantro, chopped"))
        assertNull(group("2 tbsp chopped fresh mint"))
        assertNull(group("1 tbsp fresh herbs"))
        assertEquals("salad", group("5 oz baby spinach"))
        assertEquals("salad", group("1 head romaine lettuce"))
        assertEquals("salad", group("2 handfuls rocket"))
        assertEquals("berries", group("1 pint strawberries, hulled"))
        assertNull(group("1 cup blueberries")) // FoodKeeper: 1-2 weeks, off the table
        assertEquals("berries", group("1 cup raspberries"))
        assertEquals("berries", group("2 cups mixed berries"))
        assertEquals("blackberries", group("1 cup blackberries"))
    }

    @Test fun `a group's keeps hint is its range`() {
        assertEquals("1-2", Perishables.find("2 salmon fillets", en)!!.group.keeps)
        assertEquals("2-3", Perishables.find("1 bunch parsley", en)!!.group.keeps)
        assertEquals("3-5", Perishables.find("8 oz mushrooms", en)!!.group.keeps)
        assertEquals("3-4", Perishables.find("2 avocados", en)!!.group.keeps)
        assertEquals("3", PerishableGroup("x", 3, 3).keeps)
    }

    @Test fun `the name is the ingredient's own, as the pantry reads it`() {
        assertEquals("salmon fillets", Perishables.find("2 salmon fillets (about 6 oz each), skin on", en)!!.name)
        assertEquals("button mushrooms", Perishables.find("8 oz button mushrooms, sliced", en)!!.name)
    }

    @Test fun `a skip entry wins by being longer, and a name that only ends in an alias is not matched`() {
        // Not fresh: smoked, canned, dried, frozen, cooked.
        assertNull(group("4 oz smoked salmon"))
        assertNull(group("1 can (6 oz) canned salmon"))
        assertNull(group("2 tsp dried basil"))
        assertNull(group("1 tbsp dried parsley"))
        assertNull(group("1 cup frozen strawberries"))
        assertNull(group("2 cups frozen blueberries"))
        assertNull(group("1 cup frozen mixed berries"))
        assertNull(group("1 oz dried porcini mushrooms"))
        assertNull(group("1 cup dried shiitake"))
        assertNull(group("10 oz frozen chopped spinach"))
        assertNull(group("1 lb frozen shrimp"))
        assertNull(group("2 cups shredded chicken"))
        assertNull(group("1 rotisserie chicken"))
        assertNull(group("2 cups cooked chicken"))
        assertNull(group("1 cup cauliflower steak"))
        assertNull(group("1/4 cup goji berries"))
        assertNull(group("1/4 cup juniper berries"))
        // A different thing: the name ends in another word.
        assertNull(group("2 tbsp fish sauce"))
        assertNull(group("1 cup fish stock"))
        assertNull(group("1 cup mushroom stock"))
        assertNull(group("1 cup chicken stock"))
        assertNull(group("1/2 cup strawberry jam"))
        assertNull(group("2 tsp dried herbs"))
        assertNull(group("1 can cream of mushroom soup"))
        assertNull(group("1 tsp basil pesto"))
        assertNull(group("1/2 cup avocado oil"))
        assertNull(group("1 cup cod liver oil"))
        // Longest alias first: tuna steak is fish, but plain tuna isn't listed.
        assertEquals("fish", group("2 tuna steaks"))
        assertNull(group("1 can tuna"))
    }

    @Test fun `a line the app cannot name, or an unlisted ingredient, is not perishable`() {
        assertNull(group("Salmon and potatoes:"))
        assertNull(group("salt and pepper"))
        assertNull(group("juice of 1 lemon"))
        assertNull(group("2 cups flour"))
        assertNull(group("3 carrots"))
        assertNull(group(""))
        assertNull(Perishables.groupOf("", en))
    }

    @Test fun `a listed singular and plural are one word`() {
        assertEquals("berries", Perishables.groupOf("raspberries", en)?.key)
        assertEquals("berries", Perishables.groupOf("raspberry", en)?.key)
        assertEquals("fish", Perishables.groupOf("mussels", en)?.key)
        assertNull(Perishables.groupOf("frozen raspberries", en))
        assertNull(Perishables.groupOf("frozen raspberry", en))
    }

    @Test fun `the most delicate perishable of a recipe is the shortest-keeping`() {
        val lines = listOf("1 bunch parsley", "2 salmon fillets", "8 oz mushrooms", "2 cups flour")
        val worst = Perishables.mostDelicate(lines, en)!!
        assertEquals("fish", worst.group.key)
        assertEquals("salmon fillets", worst.name)
        assertEquals("parsley", Perishables.mostDelicate(listOf("2 cups flour", "1 bunch parsley", "1 cup blueberries"), en)!!.group.key)
        assertNull(Perishables.mostDelicate(listOf("2 cups flour", "1 tsp dried parsley"), en))
        assertNull(Perishables.mostDelicate(emptyList(), en))
    }

    @Test fun `the recipe's language picks the table, and a language with none matches nothing`() {
        val de = LanguageWords.forTag("de")
        assertEquals("fish", group("2 Lachsfilets", de))
        assertEquals("fish", group("200 g Lachs", de))
        assertNull(group("100 g geräucherter Lachs", de))
        assertEquals("mince", group("500 g Hackfleisch", de))
        assertEquals("parsley", group("1 Bund Petersilie", de))
        assertNull(group("1 Bund Basilikum", de))
        assertNull(group("200 g Heidelbeeren", de))
        assertNull(group("1 TL getrocknetes Basilikum", de))
        assertNull(group("200 g tiefgekühlte Erdbeeren", de))
        // English words read as German mean nothing, and German words read as English too.
        assertNull(group("2 salmon fillets", de))
        assertNull(group("200 g Lachs", en))

        val fr = LanguageWords.forTag("fr")
        assertEquals("fish", group("2 filets de saumon", fr))
        assertNull(group("2 tranches de saumon fumé", fr))
        assertNull(group("1 l de bouillon de poulet", fr))
        assertNull(group("1 pot de confiture de fraises", fr))
        assertEquals("berries", group("250 g de fraises", fr))

        val es = LanguageWords.forTag("es")
        assertEquals("poultry", group("2 pechugas de pollo", es))
        assertNull(group("1 l de caldo de pollo", es))
        assertEquals("mushrooms", group("200 g de champiñones", es))
        assertNull(group("1 lata de crema de champiñones", es))

        val ital = LanguageWords.forTag("it")
        assertEquals("parsley", group("1 mazzetto di prezzemolo", ital))
        assertNull(group("1 mazzetto di basilico", ital))
        assertNull(group("2 cucchiai di pesto di basilico", ital))

        val pt = LanguageWords.forTag("pt")
        assertEquals("fish", group("2 filés de salmão", pt))
        assertNull(group("1 xícara de caldo de frango", pt))

        val ja = LanguageWords.forTag("ja")
        assertEquals("fish", group("鮭 2切れ", ja))
        assertNull(group("塩鮭 2切れ", ja))
        assertEquals("mince", group("豚ひき肉 200g", ja))

        assertNull(Perishables.find("2 salmon fillets", null))
        assertNull(Perishables.groupOf("salmon", null))
    }

    @Test fun `every shipped language has the table, with the same groups and days as English`() {
        val english = Perishables.groups(en).associateBy { it.key }
        for (language in LanguageWords.SHIPPED) {
            val words = LanguageWords.forTag(language)!!
            val groups = Perishables.groups(words)
            assertTrue("$language has groups", groups.isNotEmpty())
            for (group in groups) {
                val base = english[group.key]
                assertNotNull("$language's ${group.key} is a group of the English table", base)
                assertEquals("$language ${group.key} days", base, group)
            }
            // The names of the groups every language must say.
            for (key in listOf("fish", "mince", "poultry", "mushrooms", "avocado", "parsley", "salad", "berries", "blackberries")) {
                assertTrue("$language lists $key", groups.any { it.key == key })
            }
        }
    }

    @Test fun `later is at or past the low end of every group's range`() {
        for (language in LanguageWords.SHIPPED) {
            for (group in Perishables.groups(LanguageWords.forTag(language)!!)) {
                assertTrue("${group.key}: minDays ${group.minDays}", group.minDays <= Perishables.LATER_DAYS)
                assertTrue("${group.key}: a range", group.minDays in 1..group.maxDays)
            }
        }
    }

    @Test fun `no alias is a skip entry as well, and none is empty`() {
        for (language in LanguageWords.SHIPPED) {
            val table = SharedTables.load("perishables", language)
            val skips = SharedTables.strings(table.getJSONArray("skip"))
            val aliases = SharedTables.objects(table.getJSONArray("groups")).flatMap { SharedTables.strings(it.getJSONArray("aliases")) }
            assertTrue("$language: no blank entry", (skips + aliases).none { it.isBlank() || it != it.trim() || it != it.lowercase() })
            assertEquals("$language: an alias that is also a skip", emptyList<String>(), aliases.filter { it in skips })
        }
    }
}
