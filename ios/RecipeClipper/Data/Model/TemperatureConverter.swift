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
    // shared/tables/en/temperature.json and ranges.json.
    private static let table = SharedTables.load("temperature")
    private static let fahrenheitWords = SharedTables.strings(table, "fahrenheit")
    private static let scaleWords = fahrenheitWords + SharedTables.strings(table, "celsius")
    private static let fahrenheitWord = JRegex(SharedTables.alternation(fahrenheitWords), ignoreCase: true)
    private static let degrees = SharedTables.alternation(SharedTables.strings(table, "degrees"))

    // groups: 1 low, 2 range separator, 3 high, 4 connector, 5 unit
    private static let temp =
        #"(\d{2,3})(?:(\s*(?:[-–—]|"# + SharedTables.rangeWords + #")\s*)(\d{2,3}))?(\s*[°º˚]\s*|\s+"# +
        degrees + #"\s+|\s?)"# +
        #"((?i:"# + scaleWords.joined(separator: "|") + #")|[FC])(?![A-Za-z])"#

    private static let tempAnywhere = JRegex(#"(?<![\d.,/])"# + temp)
    private static let tempAtStart = JRegex("^" + temp)
    private static let pairJoiner = JRegex(#"^\s*([(/])\s*"#)
    private static let closingParen = JRegex(#"^\s*\)"#)

    static func convert(_ text: String, unit: TemperatureUnit) -> String {
        let target: Scale
        switch unit {
        case .asWritten: return text
        case .celsius: target = .c
        case .fahrenheit: target = .f
        }

        var out = ""
        var cursor = 0
        while let match = tempAnywhere.find(text, from: cursor) {
            let end = match.end
            guard let temp = parse(match) else {
                out += text.u16Substring(cursor, end)
                cursor = end
                continue
            }

            // "350°F (180°C)" or "180°C/350°F": keep the half that already matches.
            if let pair = findPair(text, firstEnd: end, first: temp) {
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

    private struct Pair {
        let value: String
        let end: Int
    }

    /// The other-scale temperature written straight after `first`, if there is one.
    private static func findPair(_ text: String, firstEnd: Int, first: Temp) -> Pair? {
        let rest = text.u16Substring(from: firstEnd)
        guard let joiner = pairJoiner.find(rest) else { return nil }
        let afterJoiner = rest.u16Substring(from: joiner.value.u16Count)
        guard let match = tempAtStart.find(afterJoiner) else { return nil }
        guard let second = parse(match) else { return nil }
        if second.scale == first.scale { return nil }

        var end = firstEnd + joiner.value.u16Count + match.value.u16Count
        if joiner[1] == "(" {
            guard let close = closingParen.find(text.u16Substring(from: end)) else { return nil }
            end += close.value.u16Count
        }
        return Pair(value: match.value, end: end)
    }

    private static func parse(_ match: JMatch) -> Temp? {
        guard let low = Int(match[1]) else { return nil }
        let high = match[3].isEmpty ? nil : Int(match[3])
        let unit = match[5]
        let scale: Scale = unit == "F" || fahrenheitWord.matchEntire(unit) != nil ? .f : .c

        let connector = match[4]
        let explicit = unit.count > 1 || connector.contains { "°º˚".contains($0) } || !connector.kIsBlank
        if !explicit {
            let range = scale == .f ? 100...600 : 40...320
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
