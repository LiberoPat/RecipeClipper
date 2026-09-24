import XCTest
@testable import RecipeClipper

/// Japanese lines (#16), read with `shared/tables/ja/`: name first, amount last, no spaces
/// between words, a 200 ml cup, full-width digits. Mirrors Android's JapaneseTest; every line is
/// from a real site unless it says otherwise, and the corpus pins many more.
final class JapaneseTests: XCTestCase {

    private let ja = LanguageWords.forTag("ja")!

    private func scale(_ line: String, _ factor: Double) -> String { IngredientScaler.scale(line, factor: factor, words: ja) }

    private func convert(_ line: String, _ system: UnitSystem, liquids: Bool = false) -> String {
        UnitConverter.convert(line, system: system, includeLiquids: liquids, words: ja)
    }

    func testTheAmountAfterTheNameScalesWithItsUnitBeforeOrAfterTheNumber() {
        XCTAssertEqual(scale("醤油 大さじ1", 2), "醤油 大さじ2")
        XCTAssertEqual(scale("パン粉 1/2カップ", 2), "パン粉 1カップ")
        XCTAssertEqual(scale("卵 2個", 2), "卵 4個")
        XCTAssertEqual(scale("合いびき肉 250g", 2), "合いびき肉 500g")
        XCTAssertEqual(scale("水 カップ1/2", 2), "水 カップ1") // constructed: the unit first
    }

    func testMixedNumbersScaleWhole() {
        XCTAssertEqual(scale("ごま油\u{3000} 小さじ1と1/2", 2), "ごま油\u{3000} 小さじ3")
        XCTAssertEqual(scale("A牛乳 2・1/2カップ", 2), "A牛乳 5カップ")
        XCTAssertEqual(scale("「蒲焼のたれ」 大さじ1と1/2", 1.5), "「蒲焼のたれ」 大さじ2と1/4")
    }

    func testAMeasureInBracketsScalesToo() {
        XCTAssertEqual(scale("ホールトマト缶 1/2缶（200g）", 2), "ホールトマト缶 1缶（400g）")
        XCTAssertEqual(scale("鶏もも肉 2枚（約700g）", 2), "鶏もも肉 4枚（約1400g）")
        XCTAssertEqual(scale("玉ねぎ(みじん切り) 1/4個分(50g)", 2), "玉ねぎ(みじん切り) 1/2個分(100g)")
    }

    func testFullWidthDigitsAreReadAndTheRestIsKept() {
        XCTAssertEqual(scale("シソの実 １００Ｇ", 2), "シソの実 200Ｇ")
        XCTAssertEqual(scale("醤油（お好みで加減して下さい） ５０ＣＣ", 2), "醤油（お好みで加減して下さい） 100ＣＣ")
    }

    func testVagueAmountsSizesHalvesAndHeadingsStayAsWritten() {
        for line in [
            "こしょう 少々", "大葉 適量", "レモン お好み", "木綿豆腐 一丁", "ねぎ 10cm", "豆腐 1半丁",
            "肉だね", "A（混ぜる）", "〈ドレッシング〉", "食紅 付属の小さじ１", "塩少々",
        ] {
            XCTAssertEqual(scale(line, 2), line)
            XCTAssertEqual(convert(line, .metric), line)
        }
        XCTAssertEqual(scale("砂糖 大さじ2 1/2", 2), "砂糖 大さじ2 1/2") // constructed
    }

    func testCookpadsShorthandScalesButNeverConverts() {
        XCTAssertEqual(scale("油 大2", 2), "油 大4")
        XCTAssertEqual(scale("玉ねぎ(細切り) 大1/6個くらい", 2), "玉ねぎ(細切り) 大1/3個くらい")
        XCTAssertEqual(convert("油 大2", .metric), "油 大2")
    }

    func testAJapaneseCupIs200MlAndARiceCup180() {
        XCTAssertEqual(convert("B水 2カップ", .metric), "B水 400 ml")
        XCTAssertEqual(convert("米 2合", .metric), "米 360 ml") // constructed
        XCTAssertEqual(convert("だし汁 2と1/2カップ", .metric), "だし汁 500 ml")
    }

    func testSpoonsLiquidsAndCounters() {
        XCTAssertEqual(convert("醤油 大さじ1", .metric), "醤油 15 ml")
        XCTAssertEqual(convert("有塩バター 10g", .ounces), "有塩バター 3/8 oz")
        XCTAssertEqual(convert("牛乳 180cc", .ounces), "牛乳 180cc")
        XCTAssertEqual(convert("牛乳 180cc", .ounces, liquids: true), "牛乳 6 1/2 oz")
        XCTAssertEqual(convert("じゃがいも 2個（240g）", .ounces), "じゃがいも 2個（240g）")
    }

    func testNamesDropGroupMarkersAsidesAndQuoteMarks() {
        XCTAssertEqual(IngredientName.of("Aしょうゆ 小さじ1", words: ja), "しょうゆ")
        XCTAssertEqual(IngredientName.of("☆醤油 100cc", words: ja), "醤油")
        XCTAssertEqual(IngredientName.of("玉ねぎ(みじん切り) 1/4個分(50g)", words: ja), "玉ねぎ")
        XCTAssertEqual(IngredientName.of("パセリ[乾燥] 適量", words: ja), "パセリ")
        XCTAssertEqual(IngredientName.of("【A】砂糖 大さじ1", words: ja), "砂糖") // constructed
        XCTAssertNil(IngredientName.of("砂糖・コンソメ 各小さじ1", words: ja))
        XCTAssertNil(IngredientName.of("薄力粉or強力粉(米粉は20ｇ) 15ｇ", words: ja))
        XCTAssertNil(IngredientName.of("肉だね", words: ja))
        XCTAssertTrue(IngredientName.matches("有塩バター", "バター", words: ja))
    }

    func testTimersReadMinutesAndHoursWithNoSpaces() {
        XCTAssertEqual(StepTimers.parse("水を加えてふたをし、5分蒸し焼きにする。", words: ja), 5 * 60)
        XCTAssertEqual(StepTimers.parse("２０分ほどで煮詰まってきました。", words: ja), 20 * 60)
        XCTAssertEqual(StepTimers.parse("1時間30分煮る。", words: ja), 90 * 60) // constructed
        XCTAssertNil(StepTimers.parse("1時間半煮る。", words: ja)) // constructed
        XCTAssertNil(StepTimers.parse("2分の1量ずつ入れる。", words: ja)) // constructed
        XCTAssertNil(StepTimers.parse("型の8分目まで流し入れる。", words: ja)) // constructed
    }

    func testCelsiusIsWrittenWithTheOneCharacterSign() {
        XCTAssertEqual(TemperatureConverter.convert("オーブンを200℃に予熱する。", unit: .fahrenheit, words: ja), "オーブンを400°Fに予熱する。")
        XCTAssertEqual(TemperatureConverter.convert("180度で焼く", unit: .fahrenheit, words: ja), "180度で焼く")
    }

    func testYieldsCountPeopleFullWidthDigitsIncluded() {
        XCTAssertEqual(Servings.parse("2〜3人分", words: ja), 2)
        XCTAssertEqual(Servings.parse("４", words: ja), 4)
        XCTAssertEqual(Servings.parse("５〜６", words: ja), 5)
        XCTAssertNil(Servings.parse("その他", words: ja))
        XCTAssertEqual(Servings.kind("4(servings)", words: ja), .serves)
        XCTAssertEqual(Servings.kind("20個", words: ja), .makes)
        XCTAssertEqual(Servings.pickYield(["2", "2〜3人分"], words: ja), "2〜3人分")
    }

    func testAJapaneseRecipeIsDetectedFromItsLines() {
        let text = LanguageWords.detectionText(name: "和風ハンバーグ", ingredients: ["玉ねぎ 1/4個", "塩こしょう 少々", "牛乳 大さじ2", "大葉 適量"])
        XCTAssertEqual(LanguageWords.detect(text), "ja")
    }
}
