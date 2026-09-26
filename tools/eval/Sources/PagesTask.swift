import Foundation

/// Task 2: a page with no recipe data. Today: `NoRecipeFound`. #103: window → model → PageRecipeCheck.
enum PagesTask {
    /// The iOS extractor's instructions (FoundationModelsPageRecipeExtractor) and its fields as JSON.
    static let instructions = """
        You pick a recipe out of a web page's text. Copy every value exactly as it is written on \
        the page, character for character: never write, fix, translate, shorten or summarise. One \
        ingredient line per item and one step per item, in page order. Leave out anything that \
        isn't on the page. If the page holds no recipe, leave the name and the lists empty.
        Reply with a JSON object: "name", "ingredients" (array), "steps" (array), and "yield", "prepTime", \
        "cookTime", "totalTime" as the page writes them, or null.
        """
    static let schema: [String: Any] = [
        "type": "object", "required": ["name", "ingredients", "steps"],
        "properties": [
            "name": ["type": "string"], "ingredients": ["type": "array", "items": ["type": "string"]],
            "steps": ["type": "array", "items": ["type": "string"]], "yield": ["type": ["string", "null"]],
            "prepTime": ["type": ["string", "null"]], "cookTime": ["type": ["string", "null"]], "totalTime": ["type": ["string", "null"]],
        ],
    ]
    /// iOS 26: contextSize 4,096 minus 1,800 reserved, at 3 characters a token.
    static let windowChars = (4096 - 1800) * 3

    static func run(_ dir: String, llm: Bool) -> String {
        let pages = EvalPage.load(dir)
        var noWindow = 0, recovered = 0, nameRight = 0, modelFailed = 0
        var goldIng = 0, rightIng = 0, wrongIng = 0, pickedIng = 0, rejectedIng = 0
        var goldSteps = 0, rightSteps = 0, wrongSteps = 0, pickedSteps = 0, rejectedSteps = 0
        var seconds: [Double] = [], log: [String] = []
        for page in pages {
            goldIng += page.gold.ingredients.count
            goldSteps += page.gold.instructions.count
            let text = PageTextReader.read(html: page.html, url: page.url)
            guard let window = RecipeTextWindow.window(text, maxChars: windowChars) else {
                noWindow += 1; log.append("\(page.url)\tno window"); continue
            }
            guard llm else { continue }
            let reply = Llm.chat(system: instructions, user: window, json: schema)
            seconds.append(reply.seconds)
            guard let o = Llm.object(reply.text) else { modelFailed += 1; log.append("\(page.url)\tunreadable reply"); continue }
            let picked = PageSelection(
                name: o["name"] as? String, ingredients: o["ingredients"] as? [String] ?? [], steps: o["steps"] as? [String] ?? [],
                yield: o["yield"] as? String, prepTime: o["prepTime"] as? String, cookTime: o["cookTime"] as? String,
                totalTime: o["totalTime"] as? String)
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
            let rs = recipe.instructions.filter { EvalPage.matches($0, page.gold.instructions) }.count
            rightIng += ri; wrongIng += recipe.ingredients.count - ri
            rightSteps += rs; wrongSteps += recipe.instructions.count - rs
            log.append("\(page.url)\tkept \(recipe.ingredients.count)/\(picked.ingredients.count) ing (\(ri) right of \(page.gold.ingredients.count)), "
                + "\(recipe.instructions.count)/\(picked.steps.count) steps (\(rs) right of \(page.gold.instructions.count)), \(Int(reply.seconds)) s")
            for line in recipe.ingredients where !EvalPage.matches(line, page.gold.ingredients) { log.append("\t  not gold: \(line)") }
        }
        try? log.joined(separator: "\n").write(toFile: "results/pages.tsv", atomically: true, encoding: .utf8)
        let n = pages.count
        var out = "### Pages read as if they had no recipe data (n = \(n) pages)\n\n"
        out += "| Approach | Recipes shown | Name right | Ingredient lines: right / gold | Wrong ingredient lines shown | Steps: right / gold | Wrong steps shown | No window (model not asked) | Median latency |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|\n"
        out += "| Today (parsers only) | 0 | – | 0 / \(goldIng) | 0 | 0 / \(goldSteps) | 0 | – | – |\n"
        if llm {
            let median = seconds.isEmpty ? "–" : String(format: "%.1f s", seconds.sorted()[seconds.count / 2])
            out += "| #103: window + LLM + PageRecipeCheck | \(recovered) | \(nameRight) | \(rightIng) / \(goldIng) | \(wrongIng) | \(rightSteps) / \(goldSteps) | \(wrongSteps) | \(noWindow) | \(median) |\n"
            out += "\nChecker: of \(pickedIng) ingredient and \(pickedSteps) step strings the model returned, it dropped "
                + "\(rejectedIng) and \(rejectedSteps) as not on the page (pages it rejected whole not counted); \(modelFailed) unreadable replies.\n"
        }
        return out
    }
}
