import Foundation

/// How ingredient amounts are shown. `asWritten` leaves the recipe's own units untouched.
/// `ounces` expresses everything as weight. `metric` is EU-style: weights in g/kg, volumes
/// (spoons, cups, liquids) in ml/L, and dry goods with a known density in g.
enum UnitSystem: String, CaseIterable, Equatable {
    case asWritten = "AS_WRITTEN", metric = "METRIC", ounces = "OUNCES"

    /// The system a stored enum name stands for. GRAMS was a fourth option until #17; the
    /// people who chose it wanted weights, so it reads as `metric` rather than falling back
    /// to `asWritten`. Anything unknown (or nothing stored) is `asWritten`.
    init(storedName: String?) {
        if storedName == "GRAMS" { self = .metric; return }
        self = storedName.flatMap(UnitSystem.init(rawValue:)) ?? .asWritten
    }
}

/// How oven temperatures in instruction text are shown. Independent of `UnitSystem`: a user
/// can be a Metric person and still want an oven temperature left exactly as the recipe wrote
/// it (or vice versa), so this is its own setting rather than implied by the unit choice.
/// `asWritten` (the default) leaves the text alone.
enum TemperatureUnit: String, CaseIterable, Equatable { case asWritten = "AS_WRITTEN", celsius = "CELSIUS", fahrenheit = "FAHRENHEIT" }

/// `none`: a unit whose size varies from cook to cook (`MeasureUnit.varies`).
enum MeasureKind { case volume, weight, none }

/// `base` is millilitres for volume units and grams for weight units. `varies` is a unit word
/// whose size differs between cooks and countries (a French "tasse", a German "Tasse", an Italian
/// "tazza", a Portuguese "colher (café)"): its amount scales, so "250 ml (1 tasse)" doubles as a
/// whole, but it is never converted.
enum MeasureUnit: CaseIterable {
    case tsp, tbsp, cup, flOz, stick, ml, l, cl, dl, g, kg, oz, lb, varies

    var kind: MeasureKind {
        switch self {
        case .tsp, .tbsp, .cup, .flOz, .stick, .ml, .l, .cl, .dl: return .volume
        case .g, .kg, .oz, .lb: return .weight
        case .varies: return .none
        }
    }

    var base: Double {
        switch self {
        case .tsp: return 4.92892
        case .tbsp: return 14.7868
        case .cup: return 236.588
        case .flOz: return 29.5735
        case .stick: return 118.294 // US butter stick = 8 tbsp
        case .ml: return 1.0
        case .l: return 1000.0
        case .cl: return 10.0
        case .dl: return 100.0
        case .g: return 1.0
        case .kg: return 1000.0
        case .oz: return 28.3495
        case .lb: return 453.592
        case .varies: return 0.0
        }
    }

    var metric: Bool {
        switch self {
        case .ml, .l, .cl, .dl, .g, .kg: return true
        default: return false
        }
    }

    private static let whitespace = JRegex(#"\s+"#)

    /// The name Kotlin gives the unit, which the shared tables use.
    static let byTableName: [String: MeasureUnit] = [
        "TSP": .tsp, "TBSP": .tbsp, "CUP": .cup, "FL_OZ": .flOz, "STICK": .stick, "ML": .ml,
        "L": .l, "CL": .cl, "DL": .dl, "G": .g, "KG": .kg, "OZ": .oz, "LB": .lb, "VARIES": .varies,
    ]

    // shared/tables/<language>/units.json "names": the first rule the text satisfies wins.
    private final class Names {
        let names: [(unit: MeasureUnit, exact: [String], prefixes: [String])]
        init(_ words: LanguageWords) {
            names = SharedTables.objects(words.table("units"), "names").map {
                (byTableName[$0["unit"] as? String ?? ""]!, SharedTables.strings($0, "exact"), SharedTables.strings($0, "prefixes"))
            }
        }
    }

    static func fromText(_ text: String, words: LanguageWords = .english) -> MeasureUnit? {
        let s = whitespace.replace(text.lowercased().replacingOccurrences(of: ".", with: ""), with: " ")
        return words.compiled(Names.self, Names.init).names.first { name in
            name.exact.contains(s) || name.prefixes.contains { s.hasPrefix($0) }
        }?.unit
    }
}

/// Regex fragments matching a unit word. The trailing lookahead makes them match whole
/// words only, so "g" doesn't match the start of "garlic" or "l" the start of "large". It
/// looks for any letter, not just A-Z, so "g" isn't read in "gélatine" either (#15).
final class UnitPatterns {
    /// One capturing group holding the unit text.
    let captured: String

    /// Same match, no capturing group.
    let plain: String

    private init(_ words: LanguageWords) {
        // The unit words are shared with Android: shared/tables/<language>/units.json "patterns",
        // in order. (No units at all never matches, rather than matching an empty unit.)
        let patterns = words.strings("units", "patterns")
        let alternatives = (patterns.isEmpty ? ["(?!)"] : patterns).joined(separator: "|")

        // The alternation is wrapped in its own group so the optional trailing period applies to
        // every unit ("tsp.", "Tbsp.", "oz.", "lb."), not just the last alternative.
        captured = "((?:\(alternatives))\\.?)(?!\\p{L})"
        plain = "(?:(?:\(alternatives))\\.?)(?!\\p{L})"
    }

    static func of(_ words: LanguageWords = .english) -> UnitPatterns {
        words.compiled(UnitPatterns.self, UnitPatterns.init)
    }
}

// MARK: - Java-compatible regex

/// One regex match, with Kotlin-`groupValues`-style access: an unmatched group reads as "".
/// Offsets are UTF-16, the same unit Kotlin's `String` indexes by, so ranges port verbatim.
struct JMatch {
    let ranges: [NSRange]
    private let source: NSString

    init(_ result: NSTextCheckingResult, in source: NSString) {
        self.source = source
        ranges = (0..<result.numberOfRanges).map { result.range(at: $0) }
    }

    var range: NSRange { ranges[0] }
    var start: Int { ranges[0].location }
    /// One past the last matched unit (Kotlin's `range.last + 1`).
    var end: Int { NSMaxRange(ranges[0]) }
    var value: String { self[0] }

    subscript(group: Int) -> String {
        let r = ranges[group]
        return r.location == NSNotFound ? "" : source.substring(with: r)
    }
}

/// A thin wrapper over NSRegularExpression that behaves like the `kotlin.text.Regex` the
/// Android code was written against. The patterns were ported character for character, but
/// ICU and java.util.regex disagree on a few shorthand classes: Java's `\s`, `\S` and `\d`
/// are ASCII-only, ICU's are Unicode (ICU's `\s` would match a no-break space, `\d` an Arabic
/// digit that `toDouble` then can't read). The pattern is rewritten to the ASCII sets before
/// compiling so both platforms accept exactly the same text.
final class JRegex {
    let regex: NSRegularExpression
    private let entire: NSRegularExpression

    init(_ pattern: String, ignoreCase: Bool = false, dotAll: Bool = false) {
        let p = JRegex.javaCompatible(pattern)
        var options: NSRegularExpression.Options = []
        if ignoreCase { options.insert(.caseInsensitive) }
        if dotAll { options.insert(.dotMatchesLineSeparators) }
        // Patterns are compile-time constants; a failure here is a programming error.
        regex = try! NSRegularExpression(pattern: p, options: options)
        entire = try! NSRegularExpression(pattern: "\\A(?:\(p))\\z", options: options)
    }

    /// Rewrites `\s`, `\S`, `\d` and `\b` to Java's ASCII meaning. Works inside character classes
    /// too, since ICU allows nested sets (`[\d.]` becomes `[[0-9].]`, a union).
    static func javaCompatible(_ pattern: String) -> String {
        var out = ""
        var chars = pattern.makeIterator()
        while let c = chars.next() {
            guard c == "\\", let next = chars.next() else { out.append(c); continue }
            switch next {
            // Java's \b (JDK 19+) is a boundary between ASCII word characters and the rest;
            // ICU's counts any Unicode letter, so "10 minutesé" would not end at "minutes".
            case "b": out += #"(?:(?<=[A-Za-z0-9_])(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])(?=[A-Za-z0-9_]))"#
            case "s": out += #"[ \t\n\x0B\f\r]"#
            case "S": out += #"[^ \t\n\x0B\f\r]"#
            case "d": out += "[0-9]"
            default: out.append(c); out.append(next)
            }
        }
        return out
    }

    /// Like Kotlin's `find(input, startIndex)`: lookbehind can see text before `from`,
    /// and `^` still means the start of the whole input, not of the search.
    func find(_ s: String, from: Int = 0) -> JMatch? {
        let ns = s as NSString
        guard from <= ns.length else { return nil }
        let range = NSRange(location: from, length: ns.length - from)
        guard let r = regex.firstMatch(
            in: s, options: [.withTransparentBounds, .withoutAnchoringBounds], range: range
        ) else { return nil }
        return JMatch(r, in: ns)
    }

    func findAll(_ s: String) -> [JMatch] {
        let ns = s as NSString
        return regex.matches(in: s, range: NSRange(location: 0, length: ns.length)).map { JMatch($0, in: ns) }
    }

    func containsMatch(in s: String) -> Bool { find(s) != nil }

    /// Like Kotlin's `matchEntire`: the whole input must match (with backtracking into longer
    /// alternatives), not just a prefix of it.
    func matchEntire(_ s: String) -> JMatch? {
        let ns = s as NSString
        guard let r = entire.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)) else { return nil }
        return JMatch(r, in: ns)
    }

    func replace(_ s: String, _ transform: (JMatch) -> String) -> String {
        let ns = s as NSString
        var out = ""
        var cursor = 0
        for m in findAll(s) {
            out += ns.substring(with: NSRange(location: cursor, length: m.start - cursor))
            out += transform(m)
            cursor = m.end
        }
        out += ns.substring(from: cursor)
        return out
    }

    /// Literal replacement (no `$1` templates).
    func replace(_ s: String, with literal: String) -> String { replace(s) { _ in literal } }

    /// Like Kotlin's `split(regex)`: keeps leading and trailing empty strings.
    func split(_ s: String) -> [String] {
        let ns = s as NSString
        var parts: [String] = []
        var cursor = 0
        for m in findAll(s) where m.range.length > 0 {
            parts.append(ns.substring(with: NSRange(location: cursor, length: m.start - cursor)))
            cursor = m.end
        }
        parts.append(ns.substring(from: cursor))
        return parts
    }
}

extension String {
    /// Length in UTF-16 units, the unit Kotlin's `length` and every JMatch offset use.
    var u16Count: Int { (self as NSString).length }

    /// `substring(from)` with a UTF-16 offset, as in Kotlin.
    func u16Substring(from: Int) -> String { (self as NSString).substring(from: from) }

    /// `substring(from, to)` with UTF-16 offsets, as in Kotlin.
    func u16Substring(_ from: Int, _ to: Int) -> String {
        (self as NSString).substring(with: NSRange(location: from, length: to - from))
    }

    /// Kotlin's `trim()`: strips Unicode whitespace from both ends.
    var kTrimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Kotlin's `isBlank()`.
    var kIsBlank: Bool { allSatisfy { $0.isWhitespace } }
}

/// Kotlin's `Double.toInt()`: truncates toward zero into a 32-bit Int, NaN is 0 and anything
/// out of range saturates. Swift's `Int(_:)` would trap on a huge value (a line reading
/// "99999999999999999999 cups" is enough) and, being 64-bit, disagree well before that.
func kotlinToInt(_ value: Double) -> Int {
    if value.isNaN { return 0 }
    if value >= Double(Int32.max) { return Int(Int32.max) }
    if value <= Double(Int32.min) { return Int(Int32.min) }
    return Int(value)
}

/// Kotlin/Java `BigDecimal(value).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros()
/// .toPlainString()`. `BigDecimal(Double)` is the *exact* binary value, so 2.675 (really
/// 2.67499999...) rounds to 2.67, not 2.68. `Decimal(Double)` is not exact, so the digits
/// come from `%.100f`, which Apple's libc prints exactly for any double large enough to
/// be near a rounding tie at the scales used here.
func plainDecimal(_ value: Double, scale: Int) -> String {
    guard value.isFinite else { return "\(value)" }
    let text = String(format: "%.100f", abs(value))
    let parts = text.split(separator: ".", omittingEmptySubsequences: false)
    let intPart = Array(parts[0])
    let fracPart = parts.count > 1 ? Array(parts[1]) : []

    var digits: [Int] = intPart.map { Int(String($0))! }
    for i in 0..<scale { digits.append(i < fracPart.count ? Int(String(fracPart[i]))! : 0) }
    let next = scale < fracPart.count ? Int(String(fracPart[scale]))! : 0
    if next >= 5 {
        var i = digits.count - 1
        while i >= 0 {
            if digits[i] == 9 { digits[i] = 0; i -= 1 } else { digits[i] += 1; break }
        }
        if i < 0 { digits.insert(1, at: 0) }
    }
    let intCount = digits.count - scale
    var whole = digits[0..<intCount].map(String.init).joined()
    var frac = digits[intCount...].map(String.init).joined()
    while frac.hasSuffix("0") { frac.removeLast() }
    while whole.count > 1 && whole.hasPrefix("0") { whole.removeFirst() }
    if whole.isEmpty { whole = "0" }
    let body = frac.isEmpty ? whole : "\(whole).\(frac)"
    let isZero = whole == "0" && frac.isEmpty
    return value < 0 && !isZero ? "-" + body : body
}
