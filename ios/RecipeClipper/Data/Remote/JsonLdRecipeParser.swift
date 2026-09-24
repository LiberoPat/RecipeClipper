import Foundation

/// Almost every recipe site embeds a schema.org "Recipe" object as JSON-LD inside a
/// `<script type="application/ld+json">` tag - that's the data Google uses to build the
/// recipe rich-snippet cards in search results. It already contains just the title,
/// ingredients, steps, times and servings, with none of the surrounding story/ads/keyword
/// filler. This parser reads that data directly instead of scraping the visible article text.
///
/// Pure: takes the raw text of each JSON-LD script block (or a whole page, for
/// `extractJsonLdBlocks`), does no network I/O.
///
/// Ported from Android's `JsonLdRecipeParser`, which leans on Jsoup and org.json. Neither
/// exists here, so `extractJsonLdBlocks` and `stripHtml` stand in for the Jsoup calls and
/// `JSONSerialization` stands in for org.json; the notes on each say where they differ.
enum JsonLdRecipeParser {

    /// How deep `findRecipeNode` will recurse through nested JSON-LD before giving up. Far
    /// deeper than any real `@graph` needs.
    private static let maxDepth = 50

    /// Blocks nested deeper than this are skipped before `JSONSerialization` sees them.
    /// Android had to catch a `StackOverflowError` out of org.json's recursive parser for a
    /// 20,000-deep block; `JSONSerialization` refuses past 512 levels with an error rather
    /// than crashing, but the cheap pre-scan makes that independent of the Foundation
    /// version, and keeps the uncapped HowToSection recursion below on bounded input.
    private static let maxJsonNesting = 512

    /// Pure: raw text of each <script type="application/ld+json"> block in, Recipe out.
    static func parse(_ jsonLdBlocks: [String], sourceUrl: String) -> Recipe? {
        for block in jsonLdBlocks {
            // A block that doesn't parse (bad JSON, absurd nesting) is skipped, not fatal:
            // the next <script> tag may hold the recipe.
            guard let value = parseJson(block),
                  let recipeJson = findRecipeNode(value, depth: 0) else { continue }
            if let recipe = parseRecipeJson(recipeJson, sourceUrl: sourceUrl) { return recipe }
        }
        return nil
    }

    // MARK: - Finding the blocks in a page (Jsoup's `select("script[type=application/ld+json]")`)

    private static let scriptOpen = JRegex(#"<script\b((?:[^>"']|"[^"]*"|'[^']*')*)>"#, ignoreCase: true)
    private static let scriptClose = JRegex(#"</script\s*>"#, ignoreCase: true)
    private static let attribute = JRegex(
        #"([^\s"'>/=]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?"#
    )

    /// Pure: every ld+json script body in an HTML page, in document order, raw (entities
    /// are not decoded inside a script, just as Jsoup's `data()` leaves them). The type
    /// attribute is matched case-insensitively and trimmed, like Jsoup's `[type=...]`
    /// selector, with either quote style (or none) and attributes in any order. Scripts
    /// inside an HTML comment are not scripts, so comments are skipped.
    static func extractJsonLdBlocks(fromHtml html: String) -> [String] {
        let ns = html as NSString
        var blocks: [String] = []
        var cursor = 0
        while cursor < ns.length {
            let rest = NSRange(location: cursor, length: ns.length - cursor)
            let comment = ns.range(of: "<!--", range: rest)
            let script = scriptOpen.find(html, from: cursor)

            if comment.location != NSNotFound, script.map({ comment.location < $0.start }) ?? true {
                let after = comment.location + comment.length
                let close = ns.range(of: "-->", range: NSRange(location: after, length: ns.length - after))
                if close.location == NSNotFound { break }
                cursor = close.location + close.length
                continue
            }
            guard let open = script else { break }

            guard let close = scriptClose.find(html, from: open.end) else {
                // Unclosed: the rest of the page is the script's body, as an HTML parser has it.
                if isJsonLd(attributes: open[1]) { blocks.append(ns.substring(from: open.end)) }
                break
            }
            if isJsonLd(attributes: open[1]) {
                blocks.append(ns.substring(with: NSRange(location: open.end, length: close.start - open.end)))
            }
            cursor = close.end
        }
        return blocks
    }

    private static func isJsonLd(attributes: String) -> Bool {
        for m in attribute.findAll(attributes) where m[1].lowercased() == "type" {
            // An HTML parser keeps the first of duplicate attributes.
            let value = m[2].isEmpty ? (m[3].isEmpty ? m[4] : m[3]) : m[2]
            return value.kTrimmed.lowercased() == "application/ld+json"
        }
        return false
    }

    // MARK: - JSON

    /// Parses one block, or nil. Mirrors what Android's org.json `JSONTokener.nextValue()`
    /// accepts where `JSONSerialization` would be stricter, because real pages rely on it:
    /// leading `//`, `/* */` and `#` comments (the `//<![CDATA[` wrapper some CMSs add), raw
    /// newlines and tabs inside strings, and anything after the first complete value.
    static func parseJson(_ block: String) -> Any? {
        guard let prepared = prepareJson(block) else { return nil }
        do {
            return try JSONSerialization.jsonObject(with: prepared, options: [])
        } catch {
            return nil
        }
    }

    private static func prepareJson(_ block: String) -> Data? {
        let bytes = Array(block.utf8)
        var i = 0

        // Skip leading whitespace and comments.
        while i < bytes.count {
            let c = bytes[i]
            if c == 0x20 || c == 0x09 || c == 0x0A || c == 0x0D { i += 1; continue }
            if c == UInt8(ascii: "#") || (c == UInt8(ascii: "/") && i + 1 < bytes.count && bytes[i + 1] == UInt8(ascii: "/")) {
                while i < bytes.count && bytes[i] != 0x0A && bytes[i] != 0x0D { i += 1 }
                continue
            }
            if c == UInt8(ascii: "/") && i + 1 < bytes.count && bytes[i + 1] == UInt8(ascii: "*") {
                i += 2
                while i + 1 < bytes.count && !(bytes[i] == UInt8(ascii: "*") && bytes[i + 1] == UInt8(ascii: "/")) { i += 1 }
                i += 2
                continue
            }
            if c == 0xEF && i + 2 < bytes.count && bytes[i + 1] == 0xBB && bytes[i + 2] == 0xBF { i += 3; continue } // BOM
            break
        }
        // Only an object or array can hold a Recipe; anything else is no recipe.
        guard i < bytes.count, bytes[i] == UInt8(ascii: "{") || bytes[i] == UInt8(ascii: "[") else { return nil }

        var out: [UInt8] = []
        out.reserveCapacity(bytes.count - i)
        var depth = 0
        var inString = false
        var escaped = false
        while i < bytes.count {
            let c = bytes[i]
            i += 1
            if inString {
                if escaped {
                    escaped = false
                    out.append(c)
                } else if c == UInt8(ascii: "\\") {
                    escaped = true
                    out.append(c)
                } else if c == UInt8(ascii: "\"") {
                    inString = false
                    out.append(c)
                } else if c < 0x20 {
                    // A raw control character inside a string: escape it rather than reject.
                    switch c {
                    case 0x0A: out += Array("\\n".utf8)
                    case 0x0D: out += Array("\\r".utf8)
                    case 0x09: out += Array("\\t".utf8)
                    default: out += Array(String(format: "\\u%04x", c).utf8)
                    }
                } else {
                    out.append(c)
                }
                continue
            }
            out.append(c)
            switch c {
            case UInt8(ascii: "\""):
                inString = true
            case UInt8(ascii: "{"), UInt8(ascii: "["):
                depth += 1
                if depth > maxJsonNesting { return nil }
            case UInt8(ascii: "}"), UInt8(ascii: "]"):
                depth -= 1
                if depth == 0 { return Data(out) } // first complete value; ignore the rest
            default:
                break
            }
        }
        return nil // unterminated
    }

    // MARK: - Locating the Recipe object inside arbitrarily nested JSON-LD

    private static func findRecipeNode(_ node: Any, depth: Int) -> [String: Any]? {
        if depth > maxDepth { return nil }
        if let object = node as? [String: Any] {
            if isRecipeType(object["@type"]) { return object }
            if let graph = object["@graph"], let found = findRecipeNode(graph, depth: depth + 1) {
                return found
            }
            // Kotlin walks org.json's keys in document order; a Swift dictionary has none, so
            // walk them sorted for a stable answer. It only matters when one block holds two
            // separate Recipe objects outside @graph, which real pages don't do.
            for key in object.keys.sorted() where key != "@graph" {
                let child = object[key]!
                if child is [String: Any] || child is [Any], let found = findRecipeNode(child, depth: depth + 1) {
                    return found
                }
            }
        } else if let array = node as? [Any] {
            for element in array {
                if let found = findRecipeNode(element, depth: depth + 1) { return found }
            }
        }
        return nil
    }

    private static func isRecipeType(_ type: Any?) -> Bool {
        if let s = type as? String { return s.caseInsensitiveCompare("Recipe") == .orderedSame }
        if let array = type as? [Any] {
            return array.contains { ($0 as? String)?.caseInsensitiveCompare("Recipe") == .orderedSame }
        }
        return false
    }

    // MARK: - Turning the matched Recipe JSON object into our model

    private static func parseRecipeJson(_ json: [String: Any], sourceUrl: String) -> Recipe? {
        let name = stripHtml(optString(json, "name"))
        if name.kIsBlank { return nil }
        let ingredients = extractStringList(opt(json, "recipeIngredient") ?? opt(json, "ingredients"))
        let instructions = extractInstructions(opt(json, "recipeInstructions"))
        if ingredients.isEmpty && instructions.isEmpty { return nil }

        return Recipe(
            name: name,
            image: extractImage(opt(json, "image")),
            ingredients: ingredients,
            instructions: instructions,
            prepTime: formatDuration(optString(json, "prepTime")),
            cookTime: formatDuration(optString(json, "cookTime")),
            totalTime: formatDuration(optString(json, "totalTime")),
            yield: extractYield(opt(json, "recipeYield")),
            sourceUrl: sourceUrl
        )
    }

    /// org.json's `opt`, except JSON `null` reads as absent. (On Android `opt` returns
    /// `JSONObject.NULL`, so `"recipeYield": null` became the literal yield "null" there.
    /// That is a bug, not a behaviour to keep.)
    private static func opt(_ json: [String: Any], _ key: String) -> Any? {
        guard let value = json[key], !(value is NSNull) else { return nil }
        return value
    }

    /// org.json's `optString`: a string as is, anything else as its JSON text, absent or null
    /// as "". (The JVM org.json that Android's tests run against also reads null as "".)
    private static func optString(_ json: [String: Any], _ key: String) -> String {
        guard let value = opt(json, key) else { return "" }
        return javaString(value)
    }

    /// What Java's `toString()` gives for a parsed JSON value.
    static func javaString(_ value: Any) -> String {
        if let s = value as? String { return s }
        if let n = value as? NSNumber {
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue ? "true" : "false" }
            // An integer literal ("recipeYield": 6) prints as "6"; org.json keeps "6.0" a
            // Double, and Java prints that "6.0", so a float literal keeps its ".0".
            if !CFNumberIsFloatType(n) { return n.stringValue }
            let d = n.doubleValue
            if d == d.rounded(), abs(d) < 1e7 { return String(format: "%.1f", d) }
            return "\(d)"
        }
        if value is [String: Any] || value is [Any],
           let data = try? JSONSerialization.data(withJSONObject: value, options: [.withoutEscapingSlashes]),
           let text = String(data: data, encoding: .utf8) {
            return text
        }
        return "\(value)"
    }

    private static func extractImage(_ node: Any?) -> String? {
        if let s = node as? String { return s }
        if let object = node as? [String: Any] {
            let url = optString(object, "url")
            return url.kIsBlank ? nil : url
        }
        if let array = node as? [Any] { return array.first.flatMap { extractImage($0) } }
        return nil
    }

    private static func extractStringList(_ node: Any?) -> [String] {
        var result: [String] = []
        if let array = node as? [Any] {
            for element in array {
                guard let s = element as? String else { continue }
                let text = stripHtml(s).kTrimmed
                if !text.isEmpty { result.append(text) }
            }
        } else if let s = node as? String {
            let text = stripHtml(s).kTrimmed
            if !text.isEmpty { result.append(text) }
        }
        return result
    }

    /// Names of a HowToSection that restates the whole recipe in condensed form ahead of the
    /// real steps. RecipeTin Eats (WP Recipe Maker) opens with an "Abbreviated Recipe" section
    /// holding a one-paragraph summary; kept, it became step 1 in cook mode (with a timer read
    /// out of the summary) followed by the same steps again in full. Compared after
    /// `stripHtml`, trimmed and lowercased; exact matches only, never a substring, so a real
    /// section that merely mentions "summary" is untouched. Add a name only once a real site
    /// is seen publishing it. Shared with Android: shared/tables/en/sections.json.
    private static let condensedSectionNames = Set(SharedTables.strings(SharedTables.load("sections"), "condensed"))

    private static func isHowToSection(_ item: Any) -> Bool {
        guard let object = item as? [String: Any] else { return false }
        return optString(object, "@type").caseInsensitiveCompare("HowToSection") == .orderedSame
    }

    private static func isCondensedSection(_ item: Any) -> Bool {
        guard isHowToSection(item), let object = item as? [String: Any] else { return false }
        return condensedSectionNames.contains(stripHtml(optString(object, "name")).kTrimmed.lowercased())
    }

    private static func extractInstructions(_ node: Any?) -> [String] {
        var steps: [String] = []

        // HowToSection names ("For the sauce") are not emitted: sections are flattened into
        // one list of steps, so cook mode's step numbering stays simple. Showing them as
        // headers in the reading view would be a possible future feature; it would need the
        // section boundaries carried through Recipe rather than dropped here.
        func addStep(_ item: Any) {
            if let s = item as? String {
                let text = stripHtml(s).kTrimmed
                if !text.isEmpty { steps.append(text) }
            } else if let object = item as? [String: Any] {
                let type = optString(object, "@type")
                if type.caseInsensitiveCompare("HowToSection") == .orderedSame {
                    if let nested = opt(object, "itemListElement") as? [Any] {
                        for element in nested { addStep(element) }
                    }
                } else {
                    let text = optString(object, "text")
                    let raw = text.kIsBlank ? optString(object, "name") : text
                    let stripped = stripHtml(raw)
                    if !stripped.kIsBlank { steps.append(stripped.kTrimmed) }
                }
            }
        }

        if let array = node as? [Any] {
            // With two or more sections, drop a condensed duplicate of the recipe (see
            // condensedSectionNames) - but only when what's left still has steps, so a
            // recipe is never emptied by this. A lone section is never skipped.
            if array.filter(isHowToSection).count >= 2 && array.contains(where: isCondensedSection) {
                for element in array where !isCondensedSection(element) { addStep(element) }
                if steps.isEmpty { for element in array { addStep(element) } }
            } else {
                for element in array { addStep(element) }
            }
        } else if let s = node as? String {
            // Strip each line AFTER splitting on the raw string's literal newlines, not
            // before: stripping the whole block first would normalize a <br>-separated set
            // of lines into one collapsed line.
            steps += s.components(separatedBy: "\n").map { stripHtml($0).kTrimmed }.filter { !$0.isEmpty }
        }
        return steps
    }

    private static func extractYield(_ node: Any?) -> String? {
        guard let node else { return nil }
        if let s = node as? String {
            let text = stripHtml(s)
            return text.kIsBlank ? nil : text
        }
        if let array = node as? [Any] {
            return Servings.pickYield(array.compactMap { $0 is NSNull ? nil : extractYield($0) })
        }
        if let object = node as? [String: Any] {
            let text = stripHtml(optString(object, "value"))
            return text.kIsBlank ? nil : text
        }
        return javaString(node) // e.g. "recipeYield": 6 gives "6"
    }

    // MARK: - Durations

    private static let isoDuration = JRegex(
        #"^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$"#,
        ignoreCase: true
    )

    /// An English duration phrase, as Condé Nast sites (Bon Appétit, Epicurious) publish
    /// instead of ISO: "20 minutes", "1 hour", "1 hour 30 minutes", "1 hr, 5 mins",
    /// "1 hour and 30 minutes". Whole-string only (used with matchEntire): whole numbers, an
    /// hours part and/or a minutes part, nothing else. A range ("1-2 hours", "20 to 25
    /// minutes"), a fraction or a word ("Overnight") does not match and is shown as written.
    private static let phraseDuration = JRegex(
        #"\s*(?:(\d+)\s*(?:hours|hour|hrs|hr|h)"#
            + #"(?:\s*,?\s*(?:\band\s+)?(\d+)\s*(?:minutes|minute|mins|min|m))?"#
            + #"|(\d+)\s*(?:minutes|minute|mins|min|m))\s*"#,
        ignoreCase: true
    )

    /// Turns an ISO-8601 duration like "PT1H30M", or a plain English phrase like
    /// "1 hour 30 minutes", into "1h 30m". Either one totalling zero ("PT0S", "P0D",
    /// "0 minutes") is nil, so the label is hidden rather than showing "PT0S". Anything else
    /// is returned as written (trimmed): never guess at "Overnight" or "20 to 25 minutes".
    static func formatDuration(_ raw: String) -> String? {
        let text = raw.kTrimmed
        if text.kIsBlank { return nil }

        if let match = isoDuration.find(text), (1...4).contains(where: { !match[$0].kIsBlank }) {
            // Int32 with wrapping arithmetic, as Kotlin's Int: an overflowing figure reads as 0
            // (toIntOrNull) and an absurd total falls through to the raw text below.
            let days = Int32(match[1]) ?? 0
            let hours = Int32(match[2]) ?? 0
            let minutes = Int32(match[3]) ?? 0
            let seconds = Double(match[4]) ?? 0.0

            let secondMinutes = kotlinRoundToInt(seconds / 60.0)
            return renderMinutes(days &* 24 &* 60 &+ hours &* 60 &+ minutes &+ secondMinutes, asWritten: text)
        }

        if let match = phraseDuration.matchEntire(text) {
            // A figure too large for an Int is not a real time: show it as written.
            var hours: Int32 = 0
            if !match[1].isEmpty {
                guard let h = Int32(match[1]) else { return text }
                hours = h
            }
            let minutesText = match[2].isEmpty ? match[3] : match[2]
            var minutes: Int32 = 0
            if !minutesText.isEmpty {
                guard let m = Int32(minutesText) else { return text }
                minutes = m
            }
            return renderMinutes(hours &* 60 &+ minutes, asWritten: text)
        }

        return text
    }

    /// "1h 30m", "1h" or "20m"; nil for a zero total; `asWritten` for a total that
    /// overflowed to a negative number.
    private static func renderMinutes(_ totalMinutes: Int32, asWritten: String) -> String? {
        if totalMinutes == 0 { return nil }
        let h = totalMinutes / 60
        let m = totalMinutes % 60
        if h > 0 && m > 0 { return "\(h)h \(m)m" }
        if h > 0 { return "\(h)h" }
        if m > 0 { return "\(m)m" }
        return asWritten
    }

    /// Kotlin's `Double.roundToInt()`: half rounds up (Math.round), saturating at Int bounds.
    private static func kotlinRoundToInt(_ x: Double) -> Int32 {
        let r = (x + 0.5).rounded(.down)
        if r >= Double(Int32.max) { return Int32.max }
        if r <= Double(Int32.min) { return Int32.min }
        return Int32(r)
    }

    // MARK: - HTML to text (Jsoup's `parse(raw).text()`)

    /// Strips HTML tags and unescapes entities in text pulled out of JSON-LD, e.g. a
    /// `recipeInstructions.text` of "Mix &amp; pour" or "<p>Preheat the oven.</p>".
    ///
    /// Behaves like Jsoup's `Jsoup.parse(raw).text()`: tags removed, block tags and `<br>`
    /// becoming a space so words either side don't fuse (inline tags like `<b>` don't),
    /// comments and script/style contents dropped, named and numeric entities decoded
    /// (the legacy Latin-1 names even without their `;`, as browsers do), no-break spaces
    /// treated as spaces, zero-width spaces and soft hyphens dropped, whitespace runs
    /// collapsed to one space, and the result trimmed. A `<` that doesn't start a tag
    /// ("1 < 2") stays text.
    static func stripHtml(_ raw: String) -> String {
        let s = Array(raw.unicodeScalars)
        var text = String.UnicodeScalarView()
        var i = 0

        func isLetter(_ c: Unicode.Scalar) -> Bool {
            (c >= "a" && c <= "z") || (c >= "A" && c <= "Z")
        }
        /// Index of the '>' closing a tag begun at `from`, honouring quoted attribute values.
        func tagEnd(_ from: Int) -> Int? {
            var j = from
            var quote: Unicode.Scalar? = nil
            while j < s.count {
                let c = s[j]
                if let q = quote {
                    if c == q { quote = nil }
                } else if c == "\"" || c == "'" {
                    quote = c
                } else if c == ">" {
                    return j
                }
                j += 1
            }
            return nil
        }
        func tagName(_ from: Int) -> String {
            var j = from
            var name = ""
            while j < s.count, !(s[j] == ">" || s[j] == "/" || s[j].properties.isWhitespace) {
                name.unicodeScalars.append(s[j])
                j += 1
            }
            return name.lowercased()
        }
        func indexOf(_ needle: String, from: Int, caseInsensitive: Bool = false) -> Int? {
            let n = Array((caseInsensitive ? needle.lowercased() : needle).unicodeScalars)
            guard n.count <= s.count else { return nil }
            var j = from
            while j + n.count <= s.count {
                var hit = true
                for k in 0..<n.count {
                    var c = s[j + k]
                    if caseInsensitive, c >= "A", c <= "Z" { c = Unicode.Scalar(c.value + 32)! }
                    if c != n[k] { hit = false; break }
                }
                if hit { return j }
                j += 1
            }
            return nil
        }

        while i < s.count {
            let c = s[i]
            if c == "<", i + 1 < s.count {
                let n = s[i + 1]
                if isLetter(n) {
                    // Start tag.
                    guard let end = tagEnd(i + 1) else { break } // unterminated tag: dropped
                    let name = tagName(i + 1)
                    if blockTags.contains(name) || name == "br" { text.append(" ") }
                    i = end + 1
                    if name == "script" || name == "style" {
                        // Raw text: its contents are data, not text. Skip to the closing tag.
                        guard let close = indexOf("</" + name, from: i, caseInsensitive: true) else { break }
                        i = close
                    }
                    continue
                }
                if n == "/" {
                    if i + 2 < s.count, isLetter(s[i + 2]) {
                        guard let end = tagEnd(i + 2) else { break }
                        if blockTags.contains(tagName(i + 2)) || tagName(i + 2) == "br" { text.append(" ") }
                        i = end + 1
                        continue
                    }
                    if i + 2 < s.count, s[i + 2] == ">" { i += 3; continue }
                    // "</ ..." is a bogus comment up to the next '>'.
                    guard let end = indexOf(">", from: i + 2) else { break }
                    i = end + 1
                    continue
                }
                if n == "!" {
                    if i + 3 < s.count, s[i + 2] == "-", s[i + 3] == "-" {
                        guard let end = indexOf("-->", from: i + 4) else { break }
                        i = end + 3
                        continue
                    }
                    guard let end = indexOf(">", from: i + 2) else { break }
                    i = end + 1
                    continue
                }
                if n == "?" {
                    guard let end = indexOf(">", from: i + 2) else { break }
                    i = end + 1
                    continue
                }
            }
            if c == "&", let (decoded, next) = decodeEntity(s, at: i) {
                text.append(contentsOf: decoded.unicodeScalars)
                i = next
                continue
            }
            text.append(c)
            i += 1
        }

        // Normalise whitespace the way Jsoup's text() does.
        var out = String.UnicodeScalarView()
        var pendingSpace = false
        for c in text {
            switch c.value {
            case 0x20, 0x09, 0x0A, 0x0C, 0x0D, 0xA0:
                pendingSpace = true
            case 0x200B, 0xAD:
                continue // invisible: zero-width space, soft hyphen
            default:
                if pendingSpace && !out.isEmpty { out.append(" ") }
                pendingSpace = false
                out.append(c)
            }
        }
        // Jsoup finishes with Java's String.trim(), which strips every code unit up to U+0020
        // (control characters included), not only whitespace.
        while let first = out.first, first.value <= 0x20 { out.removeFirst() }
        while let last = out.last, last.value <= 0x20 { out.removeLast() }
        return String(out)
    }

    private static let blockTags: Set<String> = [
        "html", "head", "body", "frameset", "script", "noscript", "style", "meta", "link", "title",
        "frame", "noframes", "section", "nav", "aside", "hgroup", "header", "footer", "p",
        "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "pre", "div", "blockquote", "hr",
        "address", "figure", "figcaption", "form", "fieldset", "ins", "del", "dl", "dt", "dd",
        "li", "table", "caption", "thead", "tfoot", "tbody", "colgroup", "col", "tr", "th", "td",
        "video", "audio", "canvas", "details", "menu", "plaintext", "template", "article",
        "main", "svg", "math", "center", "dir", "applet", "marquee", "listing", "summary",
    ]

    /// Decodes the character reference starting at `s[at]` (an '&'). Returns the text and
    /// the index after the reference, or nil when it isn't one (the '&' is then literal).
    private static func decodeEntity(_ s: [Unicode.Scalar], at: Int) -> (String, Int)? {
        var j = at + 1
        guard j < s.count else { return nil }

        if s[j] == "#" {
            j += 1
            var hex = false
            if j < s.count, s[j] == "x" || s[j] == "X" { hex = true; j += 1 }
            var digits = ""
            while j < s.count, (hex ? s[j].properties.isASCIIHexDigit : (s[j] >= "0" && s[j] <= "9")) {
                digits.unicodeScalars.append(s[j])
                j += 1
            }
            if digits.isEmpty { return nil }
            if j < s.count, s[j] == ";" { j += 1 }
            let code = UInt32(digits, radix: hex ? 16 : 10) ?? 0xFFFD
            if let mapped = windows1252[code] { return (String(mapped), j) }
            // Jsoup passes &#0; through as U+0000. A lone surrogate (&#xD800;) is one Java
            // char Jsoup keeps but a Swift String cannot hold, so it becomes U+FFFD here.
            guard let scalar = Unicode.Scalar(code) else { return ("\u{FFFD}", j) }
            return (String(Character(scalar)), j)
        }

        // Letters, then digits ("frac12"), as Jsoup's consumeLetterThenDigitSequence reads a
        // name: ASCII letters or anything Java's Character.isLetter accepts (a BMP letter).
        var name = ""
        while j < s.count, isEntityLetter(s[j]) { name.unicodeScalars.append(s[j]); j += 1 }
        while j < s.count, s[j] >= "0" && s[j] <= "9" { name.unicodeScalars.append(s[j]); j += 1 }
        if name.isEmpty { return nil }

        // Jsoup decodes a legacy name with or without ';', any other HTML5 name only with it,
        // and nothing else. Unlike a browser it never decodes a legacy name found at the
        // front of a longer one: "&amplifier" and "&notit;" stay exactly as written.
        let hasSemicolon = j < s.count && s[j] == ";"
        guard HtmlEntities.base.contains(name) || (hasSemicolon && HtmlEntities.full[name] != nil),
              let value = HtmlEntities.full[name] else { return nil }
        return (value, hasSemicolon ? j + 1 : j)
    }

    private static func isEntityLetter(_ c: Unicode.Scalar) -> Bool {
        if (c >= "a" && c <= "z") || (c >= "A" && c <= "Z") { return true }
        guard c.value <= 0xFFFF else { return false } // Java checks one UTF-16 char at a time
        switch c.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: return true
        default: return false
        }
    }

    /// Numeric references in 0x80-0x9F mean Windows-1252 on real pages; browsers (and Jsoup)
    /// map them, so "&#146;" is an apostrophe rather than a control character.
    private static let windows1252: [UInt32: Character] = [
        0x80: "€", 0x82: "‚", 0x83: "ƒ", 0x84: "„", 0x85: "…", 0x86: "†", 0x87: "‡", 0x88: "ˆ",
        0x89: "‰", 0x8A: "Š", 0x8B: "‹", 0x8C: "Œ", 0x8E: "Ž", 0x91: "‘", 0x92: "’", 0x93: "“",
        0x94: "”", 0x95: "•", 0x96: "–", 0x97: "—", 0x98: "˜", 0x99: "™", 0x9A: "š", 0x9B: "›",
        0x9C: "œ", 0x9E: "ž", 0x9F: "Ÿ",
    ]
}
