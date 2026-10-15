import Foundation

/// Task 2: a page with no recipe data. Today: `NoRecipeFound`. #103: window → model → PageRecipeCheck.
/// Three arms on the same windows (#128): #103's one call copying the lines out, with a 1,024-token
/// reply (Android before #128) and with the apps' 4,096; and #128's line runs, as the apps ask now:
/// the window numbered, the model naming runs of line numbers, the lines taken from the window
/// (`PageLines`), then the same check.
enum PagesTask {
    /// #103's iOS instructions (the lines copied out) and its fields as JSON.
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

    /// #128's iOS instructions (FoundationModelsPageRecipeExtractor) and its `@Generable` fields as JSON.
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

    static let arms: [Arm] = [.copy(replyTokens: 1024), .copy(replyTokens: Llm.appReplyTokens), .runs]

    /// What the model picked from `window` in this arm, before the check (nil: an unreadable reply).
    static func pick(_ arm: Arm, _ window: String) -> (picked: PageSelection?, seconds: Double) {
        switch arm {
        case .copy(let tokens):
            let reply = Llm.chat(system: copyInstructions, user: window, json: copySchema, maxTokens: tokens)
            guard let o = Llm.object(reply.text) else { return (nil, reply.seconds) }
            let picked = PageSelection(
                name: o["name"] as? String, ingredients: o["ingredients"] as? [String] ?? [], steps: o["steps"] as? [String] ?? [],
                yield: o["yield"] as? String, prepTime: o["prepTime"] as? String, cookTime: o["cookTime"] as? String,
                totalTime: o["totalTime"] as? String)
            return (picked, reply.seconds)
        case .runs:
            let reply = Llm.chat(system: runInstructions, user: PageLines.numbered(window), json: runSchema)
            guard let o = Llm.object(reply.text) else { return (nil, reply.seconds) }
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

    static func run(_ dir: String, llm: Bool) -> String {
        let pages = EvalPage.load(dir)
        var windows: [(page: EvalPage, text: PageText, window: String)] = []
        var noWindow = 0
        for page in pages {
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
            var recovered = 0, nameRight = 0, modelFailed = 0
            var rightIng = 0, wrongIng = 0, pickedIng = 0, rejectedIng = 0, coveredIng = 0
            var rightSteps = 0, wrongSteps = 0, pickedSteps = 0, rejectedSteps = 0, coveredSteps = 0
            var seconds: [Double] = [], log: [String] = []
            for (page, text, window) in windows {
                let asked = pick(arm, window)
                seconds.append(asked.seconds)
                guard let picked = asked.picked else { modelFailed += 1; log.append("\(page.url)\tunreadable reply"); continue }
                pickedIng += picked.ingredients.count
                pickedSteps += picked.steps.count
                guard let recipe = PageRecipe.recipe(window: window, picked: picked, page: text, url: page.url) else {
                    log.append("\(page.url)\trejected whole (picked \(picked.ingredients.count) ingredients, name \(picked.name ?? "-"))"); continue
                }
                recovered += 1
                if EvalPage.fold(recipe.name) == EvalPage.fold(page.gold.name) || EvalPage.matches(recipe.name, [page.gold.name]) { nameRight += 1 }
                rejectedIng += picked.ingredients.count - recipe.ingredients.count
                rejectedSteps += picked.steps.count - recipe.instructions.count
                let ri = recipe.ingredients.filter { EvalPage.matches($0, page.gold.ingredients) }.count
                let rs = recipe.instructions.filter { EvalPage.matches($0, page.gold.instructions, step: true) }.count
                let ci = page.gold.ingredients.filter { EvalPage.matches($0, recipe.ingredients) }.count
                let cs = page.gold.instructions.filter { EvalPage.matches($0, recipe.instructions, step: true) }.count
                rightIng += ri; wrongIng += recipe.ingredients.count - ri; coveredIng += ci
                rightSteps += rs; wrongSteps += recipe.instructions.count - rs; coveredSteps += cs
                log.append("\(page.url)\tkept \(recipe.ingredients.count)/\(picked.ingredients.count) ing (\(ri) right; \(ci) of \(page.gold.ingredients.count) gold found), "
                    + "\(recipe.instructions.count)/\(picked.steps.count) steps (\(rs) right; \(cs) of \(page.gold.instructions.count) gold found), \(Int(asked.seconds)) s")
                for line in recipe.ingredients where !EvalPage.matches(line, page.gold.ingredients) { log.append("\t  not gold ingredient: \(line)") }
                for line in recipe.instructions where !EvalPage.matches(line, page.gold.instructions, step: true) { log.append("\t  not gold step: \(line.prefix(120))") }
            }
            try? log.joined(separator: "\n").write(toFile: "results/\(arm.log).tsv", atomically: true, encoding: .utf8)
            let median = seconds.isEmpty ? "–" : String(format: "%.1f s", seconds.sorted()[seconds.count / 2])
            out += "| \(arm.label) | \(recovered) | \(nameRight) | \(coveredIng) / \(goldIng) | \(rightIng) / \(wrongIng) | \(coveredSteps) / \(goldSteps) | \(rightSteps) / \(wrongSteps) | \(noWindow) | \(median) |\n"
            notes += "\n\(arm.label): of \(pickedIng) ingredient and \(pickedSteps) step lines picked, the check dropped "
                + "\(rejectedIng) and \(rejectedSteps) (pages it rejected whole not counted); \(modelFailed) unreadable replies.\n"
        }
        return out + notes
    }
}
