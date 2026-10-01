package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Japanese lines (#16), read with `shared/tables/ja/`: name first, amount last, no spaces
 * between words, a 200 ml cup, full-width digits. Every line here is from a real site
 * (Cookpad, Delish Kitchen, Rakuten Recipe, Nadia, Orange Page, Ajinomoto Park) unless it says
 * otherwise; the corpus pins many more on both platforms.
 */
class JapaneseTest {

    private val ja = LanguageWords.forTag("ja")!!

    private fun scale(line: String, factor: Double) = IngredientScaler.scale(line, factor, ja)
    private fun convert(line: String, system: UnitSystem, liquids: Boolean = false) =
        UnitConverter.convert(line, system, liquids, words = ja)

    @Test fun `the amount after the name scales, with its unit before or after the number`() {
        assertEquals("醤油 大さじ2", scale("醤油 大さじ1", 2.0))
        assertEquals("パン粉 1カップ", scale("パン粉 1/2カップ", 2.0))
        assertEquals("卵 4個", scale("卵 2個", 2.0))
        assertEquals("合いびき肉 500g", scale("合いびき肉 250g", 2.0))
        assertEquals("水 カップ1", scale("水 カップ1/2", 2.0)) // constructed: the unit first
    }

    @Test fun `mixed numbers are written with to or a middle dot and scale whole`() {
        assertEquals("ごま油\u3000 小さじ3", scale("ごま油　 小さじ1と1/2", 2.0))
        assertEquals("A牛乳 5カップ", scale("A牛乳 2・1/2カップ", 2.0))
        assertEquals("「蒲焼のたれ」 大さじ2と1/4", scale("「蒲焼のたれ」 大さじ1と1/2", 1.5))
    }

    @Test fun `a measure in brackets is the same amount weighed, so it scales too`() {
        assertEquals("ホールトマト缶 1缶（400g）", scale("ホールトマト缶 1/2缶（200g）", 2.0))
        assertEquals("鶏もも肉 4枚（約1400g）", scale("鶏もも肉 2枚（約700g）", 2.0))
        assertEquals("玉ねぎ(みじん切り) 1/2個分(100g)", scale("玉ねぎ(みじん切り) 1/4個分(50g)", 2.0))
    }

    @Test fun `full-width digits are read and the rest of the line is kept as written`() {
        assertEquals("シソの実 200Ｇ", scale("シソの実 １００Ｇ", 2.0))
        assertEquals("醤油（お好みで加減して下さい） 100ＣＣ", scale("醤油（お好みで加減して下さい） ５０ＣＣ", 2.0))
    }

    @Test fun `vague amounts, sizes, halves in words and headings stay as written`() {
        for (line in listOf(
            "こしょう 少々", "大葉 適量", "レモン お好み", "木綿豆腐 一丁", "ねぎ 10cm", "豆腐 1半丁",
            "肉だね", "A（混ぜる）", "〈ドレッシング〉", "食紅 付属の小さじ１", "塩少々"
        )) {
            assertEquals(line, scale(line, 2.0))
            assertEquals(line, convert(line, UnitSystem.METRIC))
        }
    }

    @Test fun `a name ending in a digit may hold part of the amount, so the line stays`() {
        assertEquals("砂糖 大さじ2 1/2", scale("砂糖 大さじ2 1/2", 2.0)) // constructed
    }

    @Test fun `Cookpad's 大 and 小 scale but never convert`() {
        assertEquals("油 大4", scale("油 大2", 2.0))
        assertEquals("玉ねぎ(細切り) 大1/3個くらい", scale("玉ねぎ(細切り) 大1/6個くらい", 2.0))
        assertEquals("油 大2", convert("油 大2", UnitSystem.METRIC))
    }

    @Test fun `a Japanese cup is 200 ml and a rice cup 180 ml`() {
        assertEquals("B水 400 ml", convert("B水 2カップ", UnitSystem.METRIC))
        assertEquals("米 360 ml", convert("米 2合", UnitSystem.METRIC)) // constructed
        assertEquals("だし汁 500 ml", convert("だし汁 2と1/2カップ", UnitSystem.METRIC))
    }

    @Test fun `spoons become ml, and a known solid becomes grams`() {
        assertEquals("醤油 15 ml", convert("醤油 大さじ1", UnitSystem.METRIC))
        assertEquals("有塩バター 10g", convert("有塩バター 10g", UnitSystem.METRIC))
        assertEquals("薄力粉 7 g", convert("薄力粉 大さじ1", UnitSystem.METRIC)) // constructed
        assertEquals("砂糖 13 g", convert("砂糖 大さじ1", UnitSystem.METRIC))
        assertEquals("有塩バター 3/8 oz", convert("有塩バター 10g", UnitSystem.OUNCES))
    }

    @Test fun `liquids follow the liquids option in ounces`() {
        assertEquals("牛乳 180cc", convert("牛乳 180cc", UnitSystem.OUNCES))
        assertEquals("牛乳 6 1/2 oz", convert("牛乳 180cc", UnitSystem.OUNCES, liquids = true))
    }

    @Test fun `counters never convert`() {
        assertEquals("じゃがいも 2個（240g）", convert("じゃがいも 2個（240g）", UnitSystem.OUNCES))
    }

    @Test fun `names drop group markers, asides and quote marks`() {
        assertEquals("しょうゆ", IngredientName.of("Aしょうゆ 小さじ1", ja))
        assertEquals("醤油", IngredientName.of("☆醤油 100cc", ja))
        assertEquals("玉ねぎ", IngredientName.of("玉ねぎ(みじん切り) 1/4個分(50g)", ja))
        assertEquals("パセリ", IngredientName.of("パセリ[乾燥] 適量", ja))
        assertEquals("オイスターソース", IngredientName.of("A「Cook Do®」オイスターソース 小さじ1", ja)?.substringAfter("®"))
        assertEquals("砂糖", IngredientName.of("【A】砂糖 大さじ1", ja)) // constructed
        assertNull(IngredientName.of("砂糖・コンソメ 各小さじ1", ja))
        assertNull(IngredientName.of("薄力粉or強力粉(米粉は20ｇ) 15ｇ", ja))
        assertNull(IngredientName.of("肉だね", ja))
    }

    @Test fun `the density table matches the end of a name by character`() {
        assertEquals(IngredientDensities.find("バター", ja), IngredientDensities.find("有塩バター", ja))
        assertNull(IngredientDensities.find("醤油", ja))
        assertNull(IngredientDensities.find("ポン酢", ja)?.gramsPerCup)
    }

    @Test fun `timers read minutes and hours with no spaces`() {
        assertEquals(5 * 60, StepTimers.parse("水を加えてふたをし、5分蒸し焼きにする。", ja))
        assertEquals(8 * 60, StepTimers.parse("☆を加えて混ぜて煮立たせ、ふたをして弱火で8〜10分煮る。", ja))
        assertEquals(20 * 60, StepTimers.parse("２０分ほどで煮詰まってきました。", ja))
        assertEquals(90 * 60, StepTimers.parse("1時間30分煮る。", ja)) // constructed
        assertEquals(60, StepTimers.parse("電子レンジ（600W）で約１分加熱し", ja))
        assertEquals(5, StepTimers.parse("5～10秒後、菜箸で全体を軽くかき混ぜる。", ja))
    }

    @Test fun `a half, a fraction or a fill level is not a time`() {
        assertNull(StepTimers.parse("1時間半煮る。", ja)) // constructed
        assertNull(StepTimers.parse("2分の1量ずつ入れる。", ja)) // constructed
        assertNull(StepTimers.parse("型の8分目まで流し入れる。", ja)) // constructed
        assertNull(StepTimers.parse("水分が1/3程度になるまで煮る。", ja))
    }

    @Test fun `celsius is written with ℃`() {
        assertEquals(
            "オーブンを400°Fに予熱する。",
            TemperatureConverter.convert("オーブンを200℃に予熱する。", TemperatureUnit.FAHRENHEIT, ja)
        )
        assertEquals("200℃に予熱", TemperatureConverter.convert("200℃に予熱", TemperatureUnit.CELSIUS, ja))
        assertEquals("180度で焼く", TemperatureConverter.convert("180度で焼く", TemperatureUnit.FAHRENHEIT, ja))
    }

    @Test fun `yields count people, full-width digits included`() {
        assertEquals(2, Servings.parse("2人分", ja))
        assertEquals(2, Servings.parse("2〜3人分", ja))
        assertEquals(4, Servings.parse("４", ja))
        assertEquals(5, Servings.parse("５〜６", ja))
        assertEquals(1, Servings.parse("１人分(3枚", ja))
        assertNull(Servings.parse("その他", ja))
        assertEquals(YieldKind.SERVES, Servings.kind("2人分", ja))
        assertEquals(YieldKind.SERVES, Servings.kind("4(servings)", ja))
        assertEquals(YieldKind.MAKES, Servings.kind("20個", ja))
        assertEquals("2〜3人分", Servings.pickYield(listOf("2", "2〜3人分"), ja))
    }

    @Test fun `a Japanese recipe is detected from its lines`() {
        val text = LanguageWords.detectionText("和風ハンバーグ", listOf("玉ねぎ 1/4個", "塩こしょう 少々", "牛乳 大さじ2", "大葉 適量"))
        assertEquals("ja", LanguageWords.detect(text))
    }
}
