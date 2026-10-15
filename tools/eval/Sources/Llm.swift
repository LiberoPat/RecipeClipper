import Foundation

/// Local model clients for the harness. Nothing here ships in the app.
enum Llm {
    /// The generative stand-in for Apple Foundation Models / Gemini Nano: a ~3B instruct model
    /// served by Ollama (`EVAL_MODEL`, default qwen2.5:3b), temperature 0.
    static let model = ProcessInfo.processInfo.environment["EVAL_MODEL"] ?? "qwen2.5:3b"

    private static func post(_ url: String, _ body: [String: Any]) -> [String: Any]? {
        var request = URLRequest(url: URL(string: url)!)
        request.httpMethod = "POST"
        request.timeoutInterval = 300
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        var result: [String: Any]?
        let done = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { data, _, _ in
            result = data.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
            done.signal()
        }.resume()
        done.wait()
        return result
    }

    /// The most reply tokens the apps allow: Android's `maxOutputTokens` (4,096, the Prompt API's
    /// maximum, #128). On iOS 26 the reply shares `contextSize` (4,096) with the page.
    static let appReplyTokens = 4096

    /// One chat turn: `system` instructions, `user` text. `json`: ask for a JSON object (Ollama's
    /// `format`, a schema when given); `maxTokens` caps the reply. Returns the reply and the seconds it took.
    static func chat(system: String, user: String, json: Any? = nil, maxTokens: Int = appReplyTokens) -> (text: String?, seconds: Double) {
        var body: [String: Any] = [
            "model": model, "stream": false,
            "options": ["temperature": 0, "num_ctx": 8192, "num_predict": maxTokens],
            "messages": [["role": "system", "content": system], ["role": "user", "content": user]],
        ]
        if let json { body["format"] = json }
        let start = Date()
        let reply = post("http://localhost:11434/api/chat", body)
        let text = (reply?["message"] as? [String: Any])?["content"] as? String
        return (text, Date().timeIntervalSince(start))
    }

    static func object(_ text: String?) -> [String: Any]? {
        guard let data = text?.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    /// JevK5 (open-weights System One, Qwen3.5-4B + LoRA, GGUF through llama-server on :8090):
    /// the options as letters, one forward pass, a softmax over the letters' log-probabilities
    /// at the model card's temperature. Our own prompt, not the jevk5 package's.
    static let jevTemperature = 1.22

    static func jev(state: String, question: String, options: [String]) -> (probs: [Double], seconds: Double)? {
        let letters = options.indices.map { String(UnicodeScalar(65 + $0)!) }
        let listed = zip(letters, options).map { "\($0)) \($1)" }.joined(separator: "\n")
        let prompt = "State: \(state)\nQuestion: \(question)\n\(listed)\nAnswer with the letter only."
        let start = Date()
        guard let reply = post("http://localhost:8090/v1/chat/completions", [
            "messages": [["role": "user", "content": prompt]], "max_tokens": 1, "temperature": 0,
            "logprobs": true, "top_logprobs": 20, "chat_template_kwargs": ["enable_thinking": false],
        ]),
            let choice = (reply["choices"] as? [[String: Any]])?.first,
            let content = ((choice["logprobs"] as? [String: Any])?["content"] as? [[String: Any]])?.first,
            let top = content["top_logprobs"] as? [[String: Any]]
        else { return nil }
        var logits = letters.map { _ in -Double.infinity }
        for entry in top {
            guard let token = (entry["token"] as? String)?.trimmingCharacters(in: .whitespaces),
                  let i = letters.firstIndex(of: token), let lp = entry["logprob"] as? Double else { continue }
            logits[i] = max(logits[i], lp / jevTemperature)
        }
        let top1 = logits.max() ?? 0
        let exps = logits.map { $0.isFinite ? exp($0 - top1) : 0 }
        let sum = exps.reduce(0, +)
        return (sum > 0 ? exps.map { $0 / sum } : exps, Date().timeIntervalSince(start))
    }
}
