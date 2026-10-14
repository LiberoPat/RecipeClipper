package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Steps modelled on real recipe pages; each amount is shown in ⟦ ⟧. */
class StepAmountsTest {

    private fun annotate(step: String, lines: List<String>, lang: String = "en"): String =
        StepAmounts.marked(StepAmounts.annotate(listOf(step), lines, LanguageWords.forTag(lang)).single())

    @Test fun `the article gives way to the line's amount`() {
        assertEquals("Add ⟦2⟧ carrots and cook 5 minutes.", annotate("Add the carrots and cook 5 minutes.", listOf("2 carrots, peeled and diced")))
        assertEquals("Stir in ⟦250 g⟧ flour.", annotate("Stir in the flour.", listOf("250 g all-purpose flour")))
        assertEquals("Beat ⟦2 large⟧ eggs well.", annotate("Beat the eggs well.", listOf("2 large eggs")))
        assertEquals("Add ⟦115 g⟧ melted butter.", annotate("Add the melted butter.", listOf("115 g unsalted butter")))
    }

    @Test fun `a mention with no article takes the amount after a verb, a list comma or a joining word`() {
        assertEquals(
            "Whisk ⟦2 cups⟧ flour, ⟦1 tsp⟧ baking powder and ⟦1/2 tsp⟧ salt together.",
            annotate("Whisk flour, baking powder and salt together.", listOf("2 cups flour", "1 tsp baking powder", "1/2 tsp salt"))
        )
    }

    @Test fun `the amount follows the servings and units the reading view shows`() {
        val lines = IngredientRendering.render(listOf("1 cup all-purpose flour"), 2.0, UnitSystem.METRIC, false)
        assertEquals("Stir in ⟦240 g⟧ flour.", annotate("Stir in the flour.", lines))
    }

    @Test fun `an old-style unit lends its amount, and converts with the line`() {
        assertEquals("Melt ⟦2 T.⟧ butter.", annotate("Melt the butter.", listOf("2 T. butter")))
        val lines = IngredientRendering.render(listOf("1/2 c. heavy cream"), 2.0, UnitSystem.METRIC, false)
        assertEquals("Whisk in ⟦240 ml⟧ heavy cream.", annotate("Whisk in the heavy cream.", lines))
    }

    @Test fun `the same ingredient in two lines with different amounts is a link to both, never a number`() {
        val lines = listOf("1 cup sugar", "For the frosting:", "1/2 cup sugar")
        assertEquals("Add the ⟪sugar|0,2⟫.", annotate("Add the sugar.", lines))
        assertEquals("Season with ⟪salt|0,1⟫.", annotate("Season with salt.", listOf("1 tsp salt", "salt and pepper")))
        // "flour for dusting" has no amount: the step can't say which use it means (#254).
        assertEquals("Stir in the ⟪flour|0,1⟫.", annotate("Stir in the flour.", listOf("2 cups flour", "flour, for dusting")))
        val link = StepAmounts.annotate(listOf("Melt the butter."), listOf("200g butter", "100g butter"), LanguageWords.ENGLISH).single()
        assertEquals(listOf(StepAmounts.Part("Melt the "), StepAmounts.Part("butter", lines = listOf(0, 1)), StepAmounts.Part(".")), link)
    }

    @Test fun `two lines lending the same amount are one answer`() {
        assertEquals(
            "Add ⟦30g / 2 tbsp⟧ butter.",
            annotate("Add the butter.", listOf("30g / 2 tbsp unsalted butter", "30g / 2 tbsp unsalted butter, cold and cubed"))
        )
    }

    @Test fun `a step names a line by its last words when the rest only describe it`() {
        assertEquals("Heat ⟦1 tbsp.⟧ oil.", annotate("Heat the oil.", listOf("1 tbsp. extra-virgin olive oil")))
        assertEquals("Add ⟦2 cups⟧ stock.", annotate("Add the stock.", listOf("2 cups low-sodium beef stock")))
        assertEquals("Microwave ⟦8 tablespoons of⟧ butter.", annotate("Microwave the butter.", listOf("8 tablespoons of salted butter")))
        assertEquals("Cream together ⟦1/2 cup⟧ butter and ⟦1 cup⟧ sugar.", annotate("Cream together butter and sugar.", listOf("1/2 cup butter", "1 cup granulated sugar")))
        // Another line ending in the same words could be the one; a word that isn't a
        // describing word makes another ingredient.
        assertEquals("Add the ⟪oil|0,1⟫.", annotate("Add the oil.", listOf("1 tbsp olive oil", "1 tsp toasted sesame oil")))
        for (step in listOf("Spoon the sauce over.", "Add the leaves.", "Beat the butter."))
            assertEquals(step, annotate(step, listOf("2 tsp Worcestershire sauce", "2 bay leaves", "1/2 cup peanut butter")))
    }

    @Test fun `a step's title before a dash is not its first mention`() {
        assertEquals(
            "Onions - Add ⟦2⟧ onions and stir.",
            annotate("Onions - Add the onions and stir.", listOf("2 onions, sliced"))
        )
    }

    @Test fun `a step that already says how much stays as written`() {
        val lines = listOf("2 cups flour", "1 cup sugar", "115 g butter")
        for (step in listOf(
            "Add 1 cup of the flour.", "Melt half the butter.", "Add the remaining sugar.",
            "Add the rest of the flour.", "Add 2 tablespoons butter.", "Add a little sugar."
        )) assertEquals(step, annotate(step, lines))
    }

    @Test fun `a longer name, compound or prose around the word stays as written`() {
        val lines = listOf("2 cups flour", "1 lemon")
        for (step in listOf("Dust with rice flour.", "Fold in the flour mixture.", "Add the lemon juice.", "Flour the counter."))
            assertEquals(step, annotate(step, lines))
        assertEquals(
            "Add ⟦1 cup⟧ brown sugar and ⟦1 cup⟧ sugar.",
            annotate("Add the brown sugar and the sugar.", listOf("1 cup brown sugar", "1 cup sugar"))
        )
    }

    @Test fun `a line with no amount it can scale, or used in parts, lends none`() {
        assertEquals("Chop the onions.", annotate("Chop the onions.", listOf("2 onions (about 300 g)")))
        assertEquals("Season with salt.", annotate("Season with salt.", listOf("salt, to taste")))
        assertEquals("Add the flour.", annotate("Add the flour.", listOf("2 cups flour, divided")))
        assertEquals("Add the salt.", annotate("Add the salt.", listOf("1 tsp salt, plus more to taste")))
    }

    @Test fun `only the first mention of a line in a step`() {
        assertEquals(
            "Melt ⟦4 tbsp⟧ butter, then brush the pan with butter.",
            annotate("Melt the butter, then brush the pan with butter.", listOf("4 tbsp butter"))
        )
    }

    @Test fun `other languages use their own articles and words`() {
        assertEquals("Ajoutez ⟦200 g de⟧ farine et ⟦3⟧ œufs.", annotate("Ajoutez la farine et les œufs.", listOf("200 g de farine", "3 œufs"), "fr"))
        assertEquals("⟦200 g⟧ Butter schmelzen.", annotate("Die Butter schmelzen.", listOf("200 g Butter"), "de"))
        assertEquals("Añade ⟦250 g de⟧ harina poco a poco.", annotate("Añade la harina poco a poco.", listOf("250 g de harina"), "es"))
        assertEquals("Aggiungete ⟦2⟧ carote.", annotate("Aggiungete le carote.", listOf("2 carote"), "it"))
        assertEquals("Junte ⟦2⟧ ovos e misture.", annotate("Junte os ovos e misture.", listOf("2 ovos"), "pt"))
        assertEquals("Ajoutez le reste de la farine.", annotate("Ajoutez le reste de la farine.", listOf("200 g de farine"), "fr"))
        assertEquals("醤油を加える。", annotate("醤油を加える。", listOf("醤油 大さじ1"), "ja"))
    }

    @Test fun `Spanish names its lines past the words after the noun`() {
        assertEquals("Añade ⟦100 ml de⟧ aceite de oliva y la sal.", annotate("Añade el aceite de oliva y la sal.", listOf("100 ml de aceite de oliva virgen extra", "sal"), "es"))
        assertEquals("Incorpora ⟦1⟧ cebolla picada.", annotate("Incorpora la cebolla picada.", listOf("1 cebolla mediana"), "es"))
        assertEquals("Añade ⟦100 ml de⟧ agua caliente.", annotate("Añade el agua caliente.", listOf("100 ml de agua"), "es"))
        assertEquals("Pica ⟦1⟧ diente de ajo.", annotate("Pica el diente de ajo.", listOf("1 diente de ajo"), "es"))
        // Another kind (rice flour, sesame oil) is another ingredient; two of a kind are a link.
        assertEquals("Añade la harina.", annotate("Añade la harina.", listOf("200 g de harina de arroz"), "es"))
        assertEquals("Añade el ⟪aceite|0,1⟫.", annotate("Añade el aceite.", listOf("2 cucharadas de aceite de oliva", "1 cucharada de aceite de sésamo"), "es"))
        assertEquals("Añade los ⟪pimientos|0,1⟫.", annotate("Añade los pimientos.", listOf("1 pimiento verde", "1 pimiento rojo"), "es"))
        // A dish named after its ingredients, and greasing, stay as written.
        assertEquals("Refrigera el arroz con leche.", annotate("Refrigera el arroz con leche.", listOf("1 taza de arroz blanco"), "es"))
        assertEquals("Engrasa el molde con la mantequilla.", annotate("Engrasa el molde con la mantequilla.", listOf("100 g de mantequilla"), "es"))
    }

    @Test fun `French reads elided names, describing words after the noun and states`() {
        assertEquals("Faites chauffer ⟦3 cuillères à soupe d'⟧huile d'olive.", annotate("Faites chauffer l'huile d'olive.", listOf("3 cuillères à soupe d'huile d'olive"), "fr"))
        assertEquals("Ajoutez ⟦90 g de⟧ beurre doux coupé en dés.", annotate("Ajoutez le beurre doux coupé en dés.", listOf("90 g de beurre doux"), "fr"))
        assertEquals("Versez ⟦120 g de⟧ farine puis ⟦50 g de⟧ beurre fondu.", annotate("Versez la farine puis le beurre fondu.", listOf("120 g de farine de blé", "50 g de beurre"), "fr"))
        assertEquals("Ajoutez le ⟪beurre|0,1⟫.", annotate("Ajoutez le beurre.", listOf("100 g de beurre doux", "30 g de beurre demi-sel"), "fr"))
        // "au" also makes dish names ("fenouil au citron"): it is no article.
        assertEquals("Ajoutez ⟦1⟧ fenouil au citron.", annotate("Ajoutez le fenouil au citron.", listOf("1 fenouil", "1/2 citron"), "fr"))
        assertEquals("Farinez le plan de travail avec la farine.", annotate("Farinez le plan de travail avec la farine.", listOf("250 g de farine"), "fr"))
    }

    @Test fun `German compounds answer their last part only when listed`() {
        assertEquals("⟦250 g⟧ Mehl mit ⟦100 g⟧ Zucker mischen.", annotate("Das Mehl mit dem Zucker mischen.", listOf("250 g Weizenmehl", "100 g Zucker"), "de"))
        assertEquals("⟦200 ml⟧ Sahne erhitzen und zu ⟦100 g⟧ Mehl geben.", annotate("Die Sahne erhitzen und zum Mehl geben.", listOf("200 ml Schlagsahne", "100 g Mehl"), "de"))
        assertEquals("⟦1 Stg⟧ Lauch waschen.", annotate("Den Lauch waschen.", listOf("1 Stg Lauch"), "de"))
        assertEquals("Die Butter schmelzen.", annotate("Die Butter schmelzen.", listOf("100 g Erdnussbutter"), "de"))
        assertEquals("Die Kerne entfernen.", annotate("Die Kerne entfernen.", listOf("50 g Sonnenblumenkerne"), "de"))
        assertEquals("Das ⟪Mehl|0,1⟫ sieben.", annotate("Das Mehl sieben.", listOf("200 g Weizenmehl", "50 g Mandelmehl"), "de"))
        assertEquals("Die Form mit der Butter einfetten.", annotate("Die Form mit der Butter einfetten.", listOf("200 g Butter"), "de"))
    }

    @Test fun `Italian and Portuguese read contractions that say where, and their verbs`() {
        assertEquals("Versate ⟦100 ml di⟧ olio e il sale.", annotate("Versate l'olio e il sale.", listOf("100 ml di olio extravergine di oliva", "sale"), "it"))
        assertEquals("Unite ⟦200 g di⟧ farina, ⟦40 g di⟧ cacao e ⟦1 bustina di⟧ lievito.", annotate("Unite farina, cacao e lievito.", listOf("200 g di farina", "40 g di cacao amaro", "1 bustina di lievito vanigliato"), "it"))
        assertEquals("Versate ⟦250 ml di⟧ latte in ⟦300 g di⟧ farina.", annotate("Versate il latte nella farina.", listOf("250 ml di latte", "300 g di farina"), "it"))
        assertEquals("Imburrate la teglia e infarinatela con la farina.", annotate("Imburrate la teglia e infarinatela con la farina.", listOf("300 g di farina"), "it"))
        assertEquals("Adicione ⟦2 xícaras (chá) de⟧ farinha e misture.", annotate("Adicione a farinha e misture.", listOf("2 xícaras (chá) de farinha de trigo"), "pt"))
        assertEquals("Dissolva a gelatina em ⟦3 colheres (sopa) de⟧ água fria.", annotate("Dissolva a gelatina na água fria.", listOf("1 colher (chá) de gelatina em pó sem sabor", "3 colheres (sopa) de água fria"), "pt"))
        assertEquals("Adicione ⟦2 unidades de⟧ ovos.", annotate("Adicione os ovos.", listOf("2 unidades de ovos (tamanho médio)"), "pt"))
        assertEquals("Unte a forma com manteiga e polvilhe farinha.", annotate("Unte a forma com manteiga e polvilhe farinha.", listOf("100 g de manteiga", "2 xícaras de farinha de trigo"), "pt"))
    }

    @Test fun `no words leave every step as written`() {
        assertEquals(listOf(listOf(StepAmounts.Part("Add the carrots."))), StepAmounts.annotate(listOf("Add the carrots."), listOf("2 carrots"), null))
    }
}
