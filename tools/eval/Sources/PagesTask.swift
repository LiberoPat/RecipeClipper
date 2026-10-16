import Foundation

/// Task 2: a page with no recipe data. Today: `NoRecipeFound`. #103: window → model → PageRecipeCheck.
/// Three arms on the same windows (#128): #103's one call copying the lines out, with a 1,024-token
/// reply (Android before #128) and with the apps' 4,096, each scored with main's line-by-line
/// check and with the one-card rule; and line runs (tried in #128, not shipped): the window
/// numbered, the model naming runs of line numbers, the lines taken from the window (`PageLines`,
/// harness only), then the check. `EVAL_ONLY=host,host` limits the pages.
enum PagesTask {
    /// The apps' instructions (FoundationModelsPageRecipeExtractor: the lines copied out) and its fields as JSON.
    static let copyInstructions = """
        You pick a recipe out of a web page's text. Copy every value exactly as it is written on \
        the page, character for character: never write, fix, translate, shorten or summarise. One \
        ingredient line per item and one step per item, in page order. Leave out anything that \
        isn't on the page. If the page holds no recipe, leave the name and the lists empty.
        Reply with a JSON object: "name", "ingredients" (array), "steps" (array), and "yield", "prepTime", \
        "cookTime", "totalTime" as the page writes them, or null.
        """
    static let copySchema: [String: Any] = [
        "type": "object", "required": ["name", "ingredients", "steps"],
        "properties": [
            "name": ["type": "string"], "ingredients": ["type": "array", "items": ["type": "string"]],
            "steps": ["type": "array", "items": ["type": "string"]], "yield": ["type": ["string", "null"]],
            "prepTime": ["type": ["string", "null"]], "cookTime": ["type": ["string", "null"]], "totalTime": ["type": ["string", "null"]],
        ],
    ]

    /// The line-runs instructions #128 tried (as its iOS `@Generable PickedLines` asked) and their fields as JSON.
    static let runInstructions = """
        You find a recipe in a web page's text. Each line of the page starts with its number in \
        brackets, like [12]. Copy the name, the yield and the times exactly as the page writes \
        them. For the ingredients and the steps, give line numbers, not text: each run is the \
        first and last line of consecutive lines that are all the recipe's ingredient lines, or \
        all its method's steps, in page order. Leave out headings, notes, tips, ads and other \
        recipes. If the page holds no recipe, leave the name and the runs empty.
        Reply with a JSON object: "name", and "yield", "prepTime", "cookTime", "totalTime" as the \
        page writes them or null, then "ingredients" and "steps", each an array of runs \
        {"first": line number, "last": line number}.
        """
    static let runSchema: [String: Any] = {
        let run: [String: Any] = [
            "type": "object", "required": ["first", "last"],
            "properties": ["first": ["type": "integer"], "last": ["type": "integer"]],
        ]
        return [
            "type": "object", "required": ["name", "ingredients", "steps"],
            "properties": [
                "name": ["type": "string"], "yield": ["type": ["string", "null"]],
                "prepTime": ["type": ["string", "null"]], "cookTime": ["type": ["string", "null"]], "totalTime": ["type": ["string", "null"]],
                "ingredients": ["type": "array", "items": run], "steps": ["type": "array", "items": run],
            ],
        ]
    }()

    /// iOS 26: contextSize 4,096 minus 1,800 reserved, at 3 characters a token.
    static let windowChars = (4096 - 1800) * 3

    enum Arm {
        case copy(replyTokens: Int)
        case runs

        var label: String {
            switch self {
            case .copy(let tokens): return "#103: window + LLM copies the lines (\(tokens) reply tokens) + PageRecipeCheck"
            case .runs: return "#128: window numbered + LLM names line runs (\(Llm.appReplyTokens) reply tokens) + PageRecipeCheck (one card)"
            }
        }

        var log: String {
            switch self {
            case .copy(let tokens): return "pages-copy-\(tokens)"
            case .runs: return "pages-runs"
            }
        }
    }

    /// `EVAL_ARMS=pages-copy-4096,…` (each arm's `log` name) runs only those arms.
    static let arms: [Arm] = {
        let all: [Arm] = [.copy(replyTokens: 1024), .copy(replyTokens: Llm.appReplyTokens), .runs]
        guard let only = ProcessInfo.processInfo.environment["EVAL_ARMS"]?.split(separator: ",").map(String.init) else { return all }
        return all.filter { only.contains($0.log) }
    }()

    /// What the model picked from `window` in this arm, before the check (nil: an unreadable reply).
    static func pick(_ arm: Arm, _ window: String) -> (picked: PageSelection?, seconds: Double) {
        switch arm {
        case .copy(let tokens):
            let reply = Llm.chat(system: copyInstructions, user: window, json: copySchema, maxTokens: tokens)
            guard let o = Llm.object(reply.text) else { keepUnreadable(arm, reply.text); return (nil, reply.seconds) }
            let picked = PageSelection(
                name: o["name"] as? String, ingredients: o["ingredients"] as? [String] ?? [], steps: o["steps"] as? [String] ?? [],
                yield: o["yield"] as? String, prepTime: o["prepTime"] as? String, cookTime: o["cookTime"] as? String,
                totalTime: o["totalTime"] as? String)
            return (picked, reply.seconds)
        case .runs:
            let reply = Llm.chat(system: runInstructions, user: PageLines.numbered(window), json: runSchema)
            guard let o = Llm.object(reply.text) else { keepUnreadable(arm, reply.text); return (nil, reply.seconds) }
            func runs(_ key: String) -> [LineRun] {
                (o[key] as? [[String: Any]] ?? []).compactMap { r in
                    guard let first = r["first"] as? Int, let last = r["last"] as? Int else { return nil }
                    return LineRun(first: first, last: last)
                }
            }
            let name = (o["name"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            let pick = PagePick(
                name: name, ingredients: runs("ingredients"), steps: runs("steps"),
                yield: o["yield"] as? String, prepTime: o["prepTime"] as? String, cookTime: o["cookTime"] as? String,
                totalTime: o["totalTime"] as? String)
            return (PageLines.selection(window, pick), reply.seconds)
        }
    }

    /// An unreadable reply, appended to results/<arm>-unreadable.txt to see why.
    static func keepUnreadable(_ arm: Arm, _ text: String?) {
        let path = "results/\(arm.log)-unreadable.txt"
        let old = (try? String(contentsOfFile: path, encoding: .utf8)) ?? ""
        try? (old + "\n=== \(text?.count ?? 0) characters\n" + (text ?? "(no reply)") + "\n").write(toFile: path, atomically: true, encoding: .utf8)
    }

    /// Main's check before #128: each line kept if `PageRecipeCheck.find` finds it, with no one-card rule.
    static func lineByLine(_ window: String, _ picked: PageSelection) -> PageSelection? {
        guard let name = picked.name.flatMap({ PageRecipeCheck.find(window, $0, kind: .name) }) else { return nil }
        let ingredients = picked.ingredients.compactMap { PageRecipeCheck.find(window, $0, kind: .ingredient) }
        let steps = picked.steps.compactMap { PageRecipeCheck.find(window, $0, kind: .step) }
        if ingredients.isEmpty && steps.isEmpty { return nil }
        return PageSelection(name: name, ingredients: ingredients, steps: steps)
    }

    static func run(_ dir: String, llm: Bool) -> String {
        let pages = EvalPage.load(dir)
        var windows: [(page: EvalPage, text: PageText, window: String)] = []
        var noWindow = 0
        let only = ProcessInfo.processInfo.environment["EVAL_ONLY"]?.split(separator: ",").map(String.init)
        for page in pages where only.map({ $0.contains { page.url.contains($0) } }) ?? true {
            let text = PageTextReader.read(html: page.html, url: page.url)
            guard let window = RecipeTextWindow.window(text, maxChars: windowChars) else { noWindow += 1; continue }
            windows.append((page, text, window))
        }
        let goldIng = pages.reduce(0) { $0 + $1.gold.ingredients.count }
        let goldSteps = pages.reduce(0) { $0 + $1.gold.instructions.count }
        let n = pages.count
        var out = "### Pages read as if they had no recipe data (n = \(n) pages)\n\n"
        out += "| Approach | Recipes shown | Name right | Gold ingredient lines found | Ingredient lines shown: right / wrong | Gold steps found | Steps shown: right / wrong | No window (model not asked) | Median latency |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|\n"
        out += "| Today (parsers only) | 0 | – | 0 / \(goldIng) | 0 / 0 | 0 / \(goldSteps) | 0 / 0 | – | – |\n"
        guard llm else { return out }
        var notes = ""
        for arm in arms {
            var replies: [(page: EvalPage, window: String, picked: PageSelection?)] = []
            var seconds: [Double] = []
            for (page, _, window) in windows {
                let asked = pick(arm, window)
                seconds.append(asked.seconds)
                replies.append((page, window, asked.picked))
            }
            let median = seconds.isEmpty ? "–" : String(format: "%.1f s", seconds.sorted()[seconds.count / 2])
            // A copy arm is scored twice from the same replies: with main's line-by-line check (#103)
            // and with the one-card rule (#128). Line runs always go through the one-card rule.
            let checks: [Bool]
            if case .runs = arm { checks = [true] } else { checks = [false, true] }
            for oneCard in checks {
                var recovered = 0, nameRight = 0, modelFailed = 0
                var rightIng = 0, wrongIng = 0, pickedIng = 0, rejectedIng = 0, coveredIng = 0, headingsShown = 0
                var rightSteps = 0, wrongSteps = 0, pickedSteps = 0, rejectedSteps = 0, coveredSteps = 0
                var log: [String] = []
                for (page, window, picked) in replies {
                    guard let picked else { modelFailed += 1; log.append("\(page.url)\tunreadable reply"); continue }
                    pickedIng += picked.ingredients.count
                    pickedSteps += picked.steps.count
                    let checked = oneCard ? PageRecipeCheck.verify(window, picked) : lineByLine(window, picked)
                    guard let kept = checked, let name = kept.name else {
                        log.append("\(page.url)\trejected whole (picked \(picked.ingredients.count) ingredients, name \(picked.name ?? "-"))"); continue
                    }
                    recovered += 1
                    if EvalPage.fold(name) == EvalPage.fold(page.gold.name) || EvalPage.matches(name, [page.gold.name]) { nameRight += 1 }
                    rejectedIng += picked.ingredients.count - kept.ingredients.count
                    rejectedSteps += picked.steps.count - kept.steps.count
                    // A group heading (#271: "For the filling:") shows as a subheading, not an ingredient
                    // line, so it is counted apart, neither right nor wrong.
                    let headings = kept.ingredients.filter { IngredientHeading.isHeading($0) }
                    let lines = kept.ingredients.filter { !IngredientHeading.isHeading($0) }
                    headingsShown += headings.count
                    let ri = lines.filter { EvalPage.matches($0, page.gold.ingredients) }.count
                    let rs = kept.steps.filter { EvalPage.matches($0, page.gold.instructions, step: true) }.count
                    let ci = page.gold.ingredients.filter { EvalPage.matches($0, kept.ingredients) }.count
                    let cs = page.gold.instructions.filter { EvalPage.matches($0, kept.steps, step: true) }.count
                    rightIng += ri; wrongIng += lines.count - ri; coveredIng += ci
                    rightSteps += rs; wrongSteps += kept.steps.count - rs; coveredSteps += cs
                    log.append("\(page.url)\tkept \(kept.ingredients.count)/\(picked.ingredients.count) ing (\(ri) right; \(ci) of \(page.gold.ingredients.count) gold found), "
                        + "\(kept.steps.count)/\(picked.steps.count) steps (\(rs) right; \(cs) of \(page.gold.instructions.count) gold found)")
                    for line in headings { log.append("\t  heading shown: \(line)") }
                    for line in lines where !EvalPage.matches(line, page.gold.ingredients) { log.append("\t  not gold ingredient: \(line)") }
                    for line in kept.steps where !EvalPage.matches(line, page.gold.instructions, step: true) { log.append("\t  not gold step: \(line.prefix(120))") }
                }
                let suffix = checks.count == 1 ? "" : oneCard ? ", one-card rule (#128)" : ", line by line (main)"
                let file = checks.count == 1 || !oneCard ? arm.log : arm.log + "-onecard"
                try? log.joined(separator: "\n").write(toFile: "results/\(file).tsv", atomically: true, encoding: .utf8)
                out += "| \(arm.label)\(suffix) | \(recovered) | \(nameRight) | \(coveredIng) / \(goldIng) | \(rightIng) / \(wrongIng) | \(coveredSteps) / \(goldSteps) | \(rightSteps) / \(wrongSteps) | \(noWindow) | \(median) |\n"
                notes += "\n\(arm.label)\(suffix): of \(pickedIng) ingredient and \(pickedSteps) step lines picked, the check dropped "
                    + "\(rejectedIng) and \(rejectedSteps) (pages it rejected whole not counted); \(modelFailed) unreadable replies; \(headingsShown) group headings shown (not counted as ingredient lines).\n"
            }
        }
        return out + notes
    }
}
