import Foundation

/// Task 1: an ingredient line's first amount, unit and name, against gold/ingredients.jsonl.
enum IngredientsTask {
    static let instructions = """
        You read one ingredient line from a recipe. Reply with a JSON object:
        "amount": the first quantity exactly as it is written in the line (for example "1 1/2", "½", "250", "0,5"); \
        for a range such as "2-3" give only the first number; "" when the line states no quantity.
        "unit": the unit word as written (for example "cups", "tbsp", "g", "can"), or "" for a count of items.
        "name": the ingredient's name only, without amounts, sizes, brands or preparation notes.
        Examples:
        "2 large eggs, beaten" -> {"amount": "2", "unit": "", "name": "eggs"}
        "1/4 teaspoon baking soda" -> {"amount": "1/4", "unit": "teaspoon", "name": "baking soda"}
        "1 (14 oz) can diced tomatoes" -> {"amount": "1", "unit": "can", "name": "diced tomatoes"}
        "Salt, to taste" -> {"amount": "", "unit": "", "name": "salt"}
        """
    static let schema: [String: Any] = [
        "type": "object", "required": ["amount", "unit", "name"],
        "properties": ["amount": ["type": "string"], "unit": ["type": "string"], "name": ["type": "string"]],
    ]
    private static let firstQuantity = try! NSRegularExpression(pattern: #"\d+ \d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?"#)

    /// The regex arm's amount: what the scaler did to the line when doubling it, halved. As written: nil.
    static func regexAmount(_ line: String) -> Double? {
        let doubled = IngredientScaler.scale(line, factor: 2, words: .english)
        guard doubled != line else { return nil }
        let ns = doubled as NSString
        guard let m = firstQuantity.firstMatch(in: doubled, range: NSRange(location: 0, length: ns.length)),
              let v = IngredientScaler.parse(ns.substring(with: m.range)) else { return nil }
        return v / 2
    }

    /// Code owns the number: the model's amount counts only if it is in the line as written, not
    /// inside another number, and the app's own parser reads it.
    /// The quantity inside what the model returned ("250" from "250g", "1 1/2" from "1 1/2 cups").
    private static let quantity = try! NSRegularExpression(
        pattern: #"\d+ \d+/\d+|\d+ ?[½¼¾⅓⅔⅛⅜⅝⅞⅙⅚⅕]|\d+⁄\d+|\d+/\d+|\d+(?:[.,]\d+)?|[½¼¾⅓⅔⅛⅜⅝⅞⅙⅚⅕]"#)

    static func number(_ amount: String?) -> String? {
        guard let a = amount else { return nil }
        let ns = a as NSString
        return quantity.firstMatch(in: a, range: NSRange(location: 0, length: ns.length)).map { ns.substring(with: $0.range) }
    }

    static func gated(_ amount: String?, _ line: String) -> (value: Double?, rejected: Bool) {
        guard let a = number(amount) else { return (nil, false) }
        guard let r = line.range(of: a) else { return (nil, true) }
        let before = r.lowerBound > line.startIndex ? line[line.index(before: r.lowerBound)] : " "
        let after = r.upperBound < line.endIndex ? line[r.upperBound] : " "
        if before.isNumber || after.isNumber || before == "/" || after == "/" { return (nil, true) }
        guard let v = IngredientScaler.parse(a) else { return (nil, true) }
        return (v, false)
    }

    static func outcome(_ value: Double?, _ gold: Double?) -> Outcome {
        guard let value else { return gold == nil ? .correct : .abstain }
        guard let gold else { return .wrong }
        return abs(value - gold) < 0.01 ? .correct : .wrong
    }

    static func run(_ goldPath: String, title: String, log logName: String, llm: Bool) -> String {
        let rows = (try! String(contentsOfFile: goldPath, encoding: .utf8)).split(separator: "\n")
            .compactMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [Any] }
        var regex = Tally(), model = Tally(), raw = Tally(), hybrid = Tally()
        var regexName = 0, modelName = 0, modelUnit = 0, unitTotal = 0
        var log: [String] = [], wrongs: [String] = []
        for row in rows {
            let line = row[0] as! String, gold = row[1] as? Double, goldUnit = row[2] as? String, goldName = row[3] as? String
            let start = Date()
            let r = regexAmount(line)
            let name = IngredientName.of(line, words: .english)
            regex.add(outcome(r, gold), seconds: Date().timeIntervalSince(start))
            if outcome(r, gold) == .wrong { wrongs.append("regex: \"\(line)\" doubled reads \"\(IngredientScaler.scale(line, factor: 2, words: .english))\"") }
            if Norm.nameMatches(name, goldName) { regexName += 1 }
            guard llm else { continue }
            let reply = Llm.chat(system: instructions, user: line, json: schema)
            let o = Llm.object(reply.text)
            let amount = (o?["amount"] as? String) ?? (o?["amount"] as? NSNumber).map { "\($0)" }
            let g = gated(amount, line)
            var t = outcome(g.value, gold)
            if g.rejected { model.rejected += 1 }
            if t == .wrong { wrongs.append("LLM (gated): \"\(line)\" read as amount \"\(amount ?? "")\"") }
            model.add(t, seconds: reply.seconds)
            raw.add(outcome(number(amount).flatMap { IngredientScaler.parse($0) }, gold), seconds: reply.seconds)
            t = outcome(r ?? g.value, gold)
            hybrid.add(t, seconds: r == nil ? reply.seconds : nil)
            if Norm.nameMatches(o?["name"] as? String, goldName) { modelName += 1 }
            if goldName != nil {
                unitTotal += 1
                let options = (goldUnit ?? "null").split(separator: "|").map { $0 == "null" ? nil : String($0) }
                // Lenient: a unit the model folded into the amount ("12 ounces") counts too.
                var unit = o?["unit"] as? String
                if unit?.isEmpty ?? true, let a = amount, let n = number(a) { unit = a.replacingOccurrences(of: n, with: "") }
                if options.contains(Norm.unit(unit)) { modelUnit += 1 }
            }
            log.append("\(line)\tgold=\(gold.map { "\($0)" } ?? "-")\tregex=\(r.map { "\($0)" } ?? "-")\tllm=\(amount ?? "-")\(g.rejected ? " (rejected)" : "")\tname=\(name ?? "-") | \(o?["name"] as? String ?? "-")\traw=\((reply.text ?? "-").replacingOccurrences(of: "\n", with: " "))")
        }
        try? log.joined(separator: "\n").write(toFile: "results/\(logName).tsv", atomically: true, encoding: .utf8)
        var out = "### \(title) (n = \(rows.count))\n\n\(Tally.header)\n"
        out += regex.row("Regex (IngredientScaler, as shipped)") + "\n"
        if llm {
            out += raw.row("LLM, number used as returned") + "\n"
            out += model.row("LLM + verbatim-number gate") + "\n"
            out += hybrid.row("Regex first, gated LLM when regex abstains") + "\n"
        }
        out += "\nIngredient name right: regex \(regexName)/\(rows.count)"
        if llm { out += ", LLM \(modelName)/\(rows.count); LLM unit right \(modelUnit)/\(unitTotal) (the regex exposes no separate unit)" }
        if !wrongs.isEmpty { out += "\nConfident wrong:\n" + wrongs.map { "- \($0)" }.joined(separator: "\n") + "\n" }
        return out + "\n"
    }
}
