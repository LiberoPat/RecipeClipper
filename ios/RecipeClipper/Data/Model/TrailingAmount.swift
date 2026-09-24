import Foundation

/// Ingredient lines written name first and amount last, as Japanese sites write them (#16):
/// "鶏もも肉 2枚（約700g）", "だし汁 2と1/2カップ", "醤油 大さじ1", "☆砂糖 小さじ1/2". A language
/// whose amounts.json sets "amountAfterName" has its lines read here instead of by their leading
/// number. Pure: string in, data out. A port of the Kotlin `TrailingAmount`.
///
/// The amount is the text after the line's last space (half-width or full-width). It is read
/// only when it is, in this order: an optional word from amounts.json "beforeNumber" (各 "each",
/// 約 "about", 大/中/小), an optional unit, a number or a range, an optional unit, then text with
/// no digit in it (a counter such as 個 or 本, 弱 "scant"), which may hold one measure in
/// brackets ("缶（200g）"): that measure is the same amount weighed, so it scales with it.
/// Anything else stays as written: 少々 and 適量 have no number, "1半丁" has a half in words,
/// "10cm" is a size, and after a name ending in a digit ("大さじ2 1/2") part of the amount may be
/// in the name.
///
/// Full-width digits and letters ("１００ＣＣ") are read as half-width ones, and a mixed number's
/// joiner ("2と1/2", "2・1/2", amounts.json "mixedJoiners") as a space. Both keep the text's
/// length, so what is found in the read text is spliced back into the line as written.
enum TrailingAmount {

    /// `text` with full-width digits, Latin letters, slash and full stop half-width, a wave dash
    /// as "~" and an ideographic space as a space. Every change is one UTF-16 unit for one, so
    /// offsets into the result are offsets into `text`.
    static func halfWidth(_ text: String) -> String {
        var units = Array(text.utf16)
        for i in units.indices {
            let c = units[i]
            switch c {
            case 0xFF10...0xFF19, 0xFF21...0xFF3A, 0xFF41...0xFF5A, 0xFF0F, 0xFF0E: units[i] = c - 0xFEE0
            case 0xFF5E, 0x301C: units[i] = 0x7E
            case 0x3000: units[i] = 0x20
            default: break
            }
        }
        return String(decoding: units, as: UTF16.self)
    }

    /// One language's patterns: amounts.json, units.json and names.json.
    private final class Patterns {
        let scaler: IngredientScaler.Patterns

        /// How a mixed number is written back ("2と1/2"): the first joiner.
        let joiner: String

        // "2と1/2": the joiner between a whole number and a fraction, read as a space.
        let mixed: JRegex

        // The name, the last run of spaces, the amount. groups: 1 name, 2 amount
        let line = JRegex(#"^(.*\S)\s+(\S+)\s*$"#)

        // groups: 1 before-word, 2 unit before, 3 quantity, 4 range separator, 5 upper bound,
        // 6 unit after, 7 the rest
        let amount: JRegex

        // The rest: no digits, or one measure in brackets. groups: 1 text before the bracket,
        // 2 the bracket and any before-word, 3 quantity, 4 unit, 5 the closing bracket
        let rest: JRegex

        // "1半丁": a half in words straight after the number.
        let half: JRegex

        // names.json "groupMarkers": "☆", "A", "【A】" before a name, repeatedly.
        let markers: JRegex

        // "(みじん切り)", "[乾燥]": an aside in brackets, innermost first.
        let aside = JRegex(#"[(（][^()（）]*[)）]|[\[［][^\[\]［］]*[\]］]"#)

        // Brackets left unmatched, and the quote marks around a brand name, kept inside.
        let stray = JRegex(#"[()（）\[\]［］「」『』〈〉]"#)

        let conjunctions: [String]

        init(_ words: LanguageWords) {
            scaler = IngredientScaler.patterns(words)
            let units = SharedTables.alternation(words.strings("units", "patterns"))
            let before = SharedTables.alternation(words.strings("amounts", "beforeNumber"))
            let qty = #"\d+ +\d+/\d+|\d+/\d+|\d+(?:\.\d+)?"#
            let joiners = words.strings("amounts", "mixedJoiners")
            joiner = joiners.first ?? " "
            mixed = JRegex(#"(?<=\d)"# + SharedTables.alternation(joiners) + #"(?=\d+/\d)"#)
            amount = JRegex(
                "^(" + before + ")?(?:(" + units + #")\s*)?("# + qty + #")(?:(\s*(?:[-–—]|"# + words.rangeWords +
                    #")\s*)("# + qty + #"))?"# + #"\s*(?:("# + units + ")(?![A-Za-z]))?(.*)$",
                ignoreCase: true
            )
            rest = JRegex(
                #"^([^\d]*?)([(（]\s*(?:"# + before + #")?\s*)("# + qty + #")\s*("# + units +
                    #")(?![A-Za-z])(\s*[)）])[^\d]*$|^[^\d]*$"#,
                ignoreCase: true
            )
            half = JRegex(#"^\s*"# + SharedTables.alternation(words.strings("amounts", "spelledHalves")))
            markers = JRegex(#"^\s*"# + SharedTables.alternation(words.strings("names", "groupMarkers")) + "+")
            conjunctions = words.strings("names", "conjunctions")
        }
    }

    private static func patterns(_ words: LanguageWords) -> Patterns { words.compiled(Patterns.self, Patterns.init) }

    /// A number in the line: where it is (UTF-16), and what it reads.
    struct Number {
        let range: NSRange
        let value: Double
    }

    /// The measure in brackets after the amount ("（約700g）"). `end` is just past its bracket.
    struct Measure {
        let number: Number
        let unit: MeasureUnit
        let text: String
        let atStart: Bool
        let end: Int
    }

    /// An amount found at the end of a line. Offsets are into the line as written. `unit` is
    /// nil for a counter (個, 本) or a bare number: it scales, but never converts.
    struct Found {
        let name: String
        let start: Int
        let beforeWord: String
        let low: Number
        let separator: String
        let high: Number?
        let unit: MeasureUnit?
        let restStart: Int
        let measure: Measure?
    }

    /// The amount at the end of `line`, or nil when there is none this can read without guessing.
    static func find(_ line: String, words: LanguageWords) -> Found? {
        let p = patterns(words)
        if p.scaler.unreadable(line) { return nil }
        guard let split = p.line.find(halfWidth(line)) else { return nil }
        if let last = split[1].utf16.last, (0x30...0x39).contains(last) { return nil }
        let start = split.ranges[2].location
        let text = p.mixed.replace(split[2]) { String(repeating: " ", count: $0.value.u16Count) }
        guard let m = p.amount.find(text) else { return nil }
        let restAt = m.ranges[7].location
        let rest = m[7]
        if p.half.containsMatch(in: rest) || p.scaler.notAnAmount.containsMatch(in: rest) { return nil }
        guard let r = p.rest.find(rest) else { return nil }

        let unitBefore = m[2]
        let unitAfter = m[6]
        if !unitBefore.isEmpty && !unitAfter.isEmpty { return nil }
        let unitText = unitBefore.isEmpty ? unitAfter : unitBefore
        var unit: MeasureUnit? = nil
        if !unitText.isEmpty {
            guard let u = MeasureUnit.fromText(unitText, words: words) else { return nil }
            unit = u
        }

        func number(_ match: JMatch, _ index: Int, _ offset: Int) -> Number? {
            let g = match.ranges[index]
            guard g.location != NSNotFound, let value = p.scaler.parse(match[index]) else { return nil }
            return Number(range: NSRange(location: start + offset + g.location, length: g.length), value: value)
        }
        guard let low = number(m, 3, 0) else { return nil }
        var high: Number? = nil
        if m.ranges[5].location != NSNotFound {
            guard let h = number(m, 5, 0) else { return nil }
            high = h
        }

        var measure: Measure? = nil
        if r.ranges[3].location != NSNotFound {
            guard let measureUnit = MeasureUnit.fromText(r[4], words: words), let n = number(r, 3, restAt) else { return nil }
            let textEnd = start + restAt + NSMaxRange(r.ranges[4])
            measure = Measure(
                number: n, unit: measureUnit, text: line.u16Substring(n.range.location, textEnd),
                atStart: r[1].kIsBlank, end: start + restAt + NSMaxRange(r.ranges[5])
            )
        }
        func original(_ g: NSRange) -> String {
            g.location == NSNotFound ? "" : line.u16Substring(start + g.location, start + NSMaxRange(g))
        }
        return Found(
            name: line.u16Substring(0, start),
            start: start,
            beforeWord: original(m.ranges[1]),
            low: low,
            separator: original(m.ranges[4]),
            high: high,
            unit: unit,
            restStart: start + restAt,
            measure: measure
        )
    }

    /// `line` with its amount, and the measure in brackets after it, scaled by `factor`.
    static func scale(_ line: String, factor: Double, words: LanguageWords) -> String {
        guard let found = find(line, words: words) else { return line }
        let p = patterns(words)
        var edits = [(found.low.range, format(p, found.low.value * factor, found.unit))]
        if let high = found.high { edits.append((high.range, format(p, high.value * factor, found.unit))) }
        if let m = found.measure { edits.append((m.number.range, format(p, m.number.value * factor, m.unit))) }
        var out = ""
        var cursor = 0
        for (range, text) in edits.sorted(by: { $0.0.location < $1.0.location }) {
            out += line.u16Substring(cursor, range.location) + text
            cursor = NSMaxRange(range)
        }
        return out + line.u16Substring(from: cursor)
    }

    // Grams and millilitres as "75" or "7.5"; anything else as a fraction, "2と1/2".
    private static func format(_ p: Patterns, _ value: Double, _ unit: MeasureUnit?) -> String {
        unit?.metric == true
            ? IngredientScaler.formatMetric(value)
            : IngredientScaler.format(value).replacingOccurrences(of: " ", with: p.joiner)
    }

    /// The ingredient's name in `line`, lowercase, without its group marker, asides in brackets
    /// or quote marks; nil for a line with no amount after a space, for a name holding a digit,
    /// and for two ingredients ("砂糖・コンソメ").
    static func nameOfLine(_ line: String, words: LanguageWords) -> String? {
        guard let split = patterns(words).line.find(halfWidth(line)) else { return nil }
        return nameOf(split[1], words: words)
    }

    /// `nameOfLine` for the text before the amount, as `Found.name` holds it.
    static func nameOf(_ text: String, words: LanguageWords) -> String? {
        let p = patterns(words)
        var t = p.markers.replace(halfWidth(text).kTrimmed, with: "")
        while true {
            let next = p.aside.replace(t, with: "")
            if next == t { break }
            t = next
        }
        t = p.markers.replace(p.stray.replace(t, with: ""), with: "").kTrimmed.lowercased()
        if t.isEmpty || t.utf16.contains(where: { (0x30...0x39).contains($0) }) ||
            p.conjunctions.contains(where: { t.contains($0) }) { return nil }
        return t
    }
}
