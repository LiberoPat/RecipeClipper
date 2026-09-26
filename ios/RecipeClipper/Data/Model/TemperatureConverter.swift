import Foundation

/// Converts temperatures in instruction text between °F and °C ("Preheat to 350°F",
/// "bake at 180 degrees C", "165F"). Pure: string in, string out.
///
/// `.celsius` and `.fahrenheit` convert to that scale, `.asWritten` leaves the text alone.
/// Independent of `UnitSystem` — see `TemperatureUnit`'s doc. Ovens are rounded to the
/// numbers recipes actually use (350°F is 180°C, not 177°C).
///
/// A number is only a temperature if it is 2-3 digits and followed by F or C. Without a
/// degree sign, "degrees" or a full word ("350F", "350 F") it must also be a plausible
/// cooking temperature, so "2 C flour" (cups) and "20 C of sugar" are left alone.
/// A pair like "350°F (180°C)" collapses to whichever half already matches the target.
enum TemperatureConverter {

    private enum Scale: Character { case f = "F", c = "C" }

    private struct Temp {
        let low: Int
        let separator: String
        let high: Int?
        let scale: Scale
    }

    // The scale, degree and range words are shared with Android:
    // shared/tables/<language>/temperature.json and ranges.json.
    private final class Patterns {
        let fahrenheitWord: JRegex
        let tempAnywhere: JRegex
        let tempAtStart: JRegex

        init(_ words: LanguageWords) {
            let fahrenheitWords = words.strings("temperature", "fahrenheit")
            let scaleWords = fahrenheitWords + words.strings("temperature", "celsius")
            fahrenheitWord = JRegex(SharedTables.alternation(fahrenheitWords), ignoreCase: true)
            let degrees = SharedTables.alternation(words.strings("temperature", "degrees"))

            // groups: 1 low, 2 range separator, 3 high, 4 connector, 5 unit
            let temp =
                #"(\d{2,3})(?:(\s*(?:[-–—]|"# + words.rangeWords + #")\s*)(\d{2,3}))?(\s*[°º˚]\s*|\s+"# +
                degrees + #"\s+|\s?)"# +
                #"((?i:"# + (scaleWords.isEmpty ? ["(?!)"] : scaleWords).joined(separator: "|") + #")|[FC])(?![A-Za-z])"#

            tempAnywhere = JRegex(#"(?<![\d.,/])"# + temp)
            tempAtStart = JRegex("^" + temp)
        }
    }

    /// A bare "180 C" or "350 F" is a temperature only in these ranges; the scaler reads them too (#135).
    static let plausibleCelsius = 40...320
    private static let plausibleFahrenheit = 100...600

    private static let pairJoiner = JRegex(#"^\s*([(/])\s*"#)
    private static let closingParen = JRegex(#"^\s*\)"#)

    /// `words` nil: a language the app has no words for, so the text stays as written.
    static func convert(_ text: String, unit: TemperatureUnit, words: LanguageWords? = .english) -> String {
        guard let words else { return text }
        let p = words.compiled(Patterns.self, Patterns.init)
        let target: Scale
        switch unit {
        case .asWritten: return text
        case .celsius: target = .c
        case .fahrenheit: target = .f
        }

        var out = ""
        var cursor = 0
        while let match = p.tempAnywhere.find(text, from: cursor) {
            let end = match.end
            guard let temp = parse(p, match) else {
                out += text.u16Substring(cursor, end)
                cursor = end
                continue
            }

            // "350°F (180°C)" or "180°C/350°F": keep the half that already matches.
            if let pair = findPair(p, text, firstEnd: end, first: temp) {
                out += text.u16Substring(cursor, match.start)
                out += temp.scale == target ? match.value : pair.value
                cursor = pair.end
                continue
            }

            out += text.u16Substring(cursor, match.start)
            out += temp.scale == target ? match.value : render(temp, target)
            cursor = end
        }
        out += text.u16Substring(from: cursor)
        return out
    }

    /// Every temperature `text` states, each as its keys ("350F", "180-200C"); "350°F (180°C)"
    /// is one temperature with two keys. What `ShortStepCheck` compares, so a short step can't
    /// change a scale or drop an oven setting. Empty for nil `words`.
    static func temperatures(_ text: String, words: LanguageWords?) -> [[String]] {
        guard let words else { return [] }
        let p = words.compiled(Patterns.self, Patterns.init)
        var found: [[String]] = []
        var cursor = 0
        while let match = p.tempAnywhere.find(text, from: cursor) {
            cursor = match.end
            guard let temp = parse(p, match) else { continue }
            let pair = findPair(p, text, firstEnd: cursor, first: temp)
            found.append([key(temp)] + (pair.map { [key($0.temp)] } ?? []))
            if let pair { cursor = pair.end }
        }
        return found
    }

    private static func key(_ t: Temp) -> String {
        "\(t.low)\(t.high.map { "-\($0)" } ?? "")\(t.scale.rawValue)"
    }

    private struct Pair {
        let value: String
        let end: Int
        let temp: Temp
    }

    /// The other-scale temperature written straight after `first`, if there is one.
    private static func findPair(_ p: Patterns, _ text: String, firstEnd: Int, first: Temp) -> Pair? {
        let rest = text.u16Substring(from: firstEnd)
        guard let joiner = pairJoiner.find(rest) else { return nil }
        let afterJoiner = rest.u16Substring(from: joiner.value.u16Count)
        guard let match = p.tempAtStart.find(afterJoiner) else { return nil }
        guard let second = parse(p, match) else { return nil }
        if second.scale == first.scale { return nil }

        var end = firstEnd + joiner.value.u16Count + match.value.u16Count
        if joiner[1] == "(" {
            guard let close = closingParen.find(text.u16Substring(from: end)) else { return nil }
            end += close.value.u16Count
        }
        return Pair(value: match.value, end: end, temp: second)
    }

    private static func parse(_ p: Patterns, _ match: JMatch) -> Temp? {
        guard let low = Int(match[1]) else { return nil }
        let high = match[3].isEmpty ? nil : Int(match[3])
        let unit = match[5]
        let scale: Scale = unit == "F" || p.fahrenheitWord.matchEntire(unit) != nil ? .f : .c

        let connector = match[4]
        let explicit = unit.count > 1 || connector.contains { "°º˚".contains($0) } || !connector.kIsBlank
        if !explicit {
            let range = scale == .f ? plausibleFahrenheit : plausibleCelsius
            if !range.contains(low) || (high.map { !range.contains($0) } ?? false) { return nil }
        }
        return Temp(low: low, separator: match[2], high: high, scale: scale)
    }

    private static func render(_ temp: Temp, _ target: Scale) -> String {
        func convert(_ v: Int) -> Int { target == .c ? toCelsius(v) : toFahrenheit(v) }
        let high = temp.high.map { temp.separator + String(convert($0)) } ?? ""
        return "\(convert(temp.low))\(high)°\(target.rawValue)"
    }

    // Kotlin's `kotlin.math.round` is half-to-even, hence `.toNearestOrEven`.
    // Oven range to the nearest 10, food-safety and dough temperatures to the nearest degree.
    private static func toCelsius(_ f: Int) -> Int {
        let c = Double((f - 32) * 5) / 9.0
        return c >= 120 ? Int((c / 10).rounded(.toNearestOrEven) * 10) : Int(c.rounded(.toNearestOrEven))
    }

    // Oven range to the nearest 25 (350, 375, 400, 425...), lower temperatures to the nearest 5.
    private static func toFahrenheit(_ c: Int) -> Int {
        let f = Double(c * 9) / 5.0 + 32
        return f >= 200 ? Int((f / 25).rounded(.toNearestOrEven) * 25) : Int((f / 5).rounded(.toNearestOrEven) * 5)
    }
}
