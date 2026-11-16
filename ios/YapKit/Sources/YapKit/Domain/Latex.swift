// 来源：app/src/main/java/com/yap/app/util/Latex.kt
//
// 轻量、零依赖的 LaTeX → 可读 Unicode 转换，只服务聊天里的展示。
// 它有意只处理对话里常见的写法，不是完整的 TeX 实现。
//
// 为什么放在 Domain 而不是 UI：这是纯字符串变换，和 SwiftUI 无关；
// 而且行内 Markdown 的 `$…$`（见 InlineMarkdown）必须经过它，
// 两端渲染出来的「数学」才会是同一串字。

import Foundation

/// LaTeX 转可读文本。
public enum Latex {

    static let greek: [(String, String)] = [
        ("alpha", "α"), ("beta", "β"), ("gamma", "γ"), ("delta", "δ"), ("epsilon", "ε"),
        ("zeta", "ζ"), ("eta", "η"), ("theta", "θ"), ("iota", "ι"), ("kappa", "κ"),
        ("lambda", "λ"), ("mu", "μ"), ("nu", "ν"), ("xi", "ξ"), ("pi", "π"), ("rho", "ρ"),
        ("sigma", "σ"), ("tau", "τ"), ("upsilon", "υ"), ("phi", "φ"), ("chi", "χ"),
        ("psi", "ψ"), ("omega", "ω"), ("Gamma", "Γ"), ("Delta", "Δ"), ("Theta", "Θ"),
        ("Lambda", "Λ"), ("Xi", "Ξ"), ("Pi", "Π"), ("Sigma", "Σ"), ("Phi", "Φ"),
        ("Psi", "Ψ"), ("Omega", "Ω")
    ]

    static let symbols: [(String, String)] = [
        ("times", "×"), ("cdot", "·"), ("div", "÷"), ("pm", "±"), ("mp", "∓"),
        ("le", "≤"), ("leq", "≤"), ("ge", "≥"), ("geq", "≥"), ("neq", "≠"), ("ne", "≠"),
        ("approx", "≈"), ("equiv", "≡"), ("sim", "∼"), ("propto", "∝"),
        ("infty", "∞"), ("partial", "∂"), ("nabla", "∇"), ("forall", "∀"), ("exists", "∃"),
        ("in", "∈"), ("notin", "∉"), ("subset", "⊂"), ("subseteq", "⊆"), ("supset", "⊃"),
        ("cup", "∪"), ("cap", "∩"), ("emptyset", "∅"), ("to", "→"), ("rightarrow", "→"),
        ("leftarrow", "←"), ("Rightarrow", "⇒"), ("Leftarrow", "⇐"), ("leftrightarrow", "↔"),
        ("sum", "∑"), ("prod", "∏"), ("int", "∫"), ("oint", "∮"), ("sqrt", "√"),
        ("angle", "∠"), ("perp", "⊥"), ("parallel", "∥"), ("degree", "°"),
        ("ldots", "…"), ("cdots", "⋯"), ("quad", "  "), ("qquad", "    ")
    ]

    static let superscripts: [(Character, Character)] = [
        ("0", "⁰"), ("1", "¹"), ("2", "²"), ("3", "³"), ("4", "⁴"), ("5", "⁵"),
        ("6", "⁶"), ("7", "⁷"), ("8", "⁸"), ("9", "⁹"), ("+", "⁺"), ("-", "⁻"),
        ("=", "⁼"), ("(", "⁽"), (")", "⁾"), ("n", "ⁿ"), ("i", "ⁱ"), ("x", "ˣ"),
        ("a", "ᵃ"), ("b", "ᵇ"), ("c", "ᶜ"), ("d", "ᵈ"), ("e", "ᵉ"), ("k", "ᵏ"),
        ("m", "ᵐ"), ("p", "ᵖ"), ("t", "ᵗ"), ("y", "ʸ")
    ]

    static let subscripts: [(Character, Character)] = [
        ("0", "₀"), ("1", "₁"), ("2", "₂"), ("3", "₃"), ("4", "₄"), ("5", "₅"),
        ("6", "₆"), ("7", "₇"), ("8", "₈"), ("9", "₉"), ("+", "₊"), ("-", "₋"),
        ("=", "₌"), ("(", "₍"), (")", "₎"), ("a", "ₐ"), ("e", "ₑ"), ("i", "ᵢ"),
        ("j", "ⱼ"), ("n", "ₙ"), ("p", "ₚ"), ("r", "ᵣ"), ("s", "ₛ"), ("t", "ₜ"),
        ("u", "ᵤ"), ("v", "ᵥ"), ("x", "ₓ")
    ]

    /// 把一段 LaTeX 变成可以直接显示的字。
    public static func toReadable(_ tex: String) -> String {
        var text = tex.trimmed
        text = replace(#"\\left\s*"#, in: text) { _ in "" }
        text = replace(#"\\right\s*"#, in: text) { _ in "" }
        text = replace(#"\\text\{([^{}]*)\}"#, in: text) { $0[1] }
        text = replace(#"\\mathrm\{([^{}]*)\}"#, in: text) { $0[1] }
        text = replace(#"\\mathbf\{([^{}]*)\}"#, in: text) { $0[1] }
        text = replace(#"\\operatorname\{([^{}]*)\}"#, in: text) { $0[1] }
        // \frac 与 \sqrt 可能嵌套，跑四轮足够把常见的一两层剥干净。
        for _ in 0..<4 {
            text = replace(#"\\frac\{([^{}]*)\}\{([^{}]*)\}"#, in: text) { groups in
                let a = toReadable(groups[1])
                let b = toReadable(groups[2])
                return "\(wrap(a))⁄\(wrap(b))"
            }
            text = replace(#"\\sqrt\[([^\]]*)\]\{([^{}]*)\}"#, in: text) { groups in
                "√[\(groups[1])](\(toReadable(groups[2])))"
            }
            text = replace(#"\\sqrt\{([^{}]*)\}"#, in: text) { groups in
                "√(\(toReadable(groups[1])))"
            }
        }
        for (plain, sup) in superscripts {
            text = text.replacingOccurrences(of: "^{\(plain)}", with: String(sup))
        }
        for (plain, sub) in subscripts {
            text = text.replacingOccurrences(of: "_{\(plain)}", with: String(sub))
        }
        text = replace(#"\^([0-9nix+\-=()])"#, in: text) { groups in
            let first = Character(groups[1])
            if let sup = lookup(superscripts, first) { return String(sup) }
            return "^\(groups[1])"
        }
        text = replace(#"_([0-9a-zijmnprstuvx+\-=()])"#, in: text) { groups in
            let first = Character(groups[1])
            if let sub = lookup(subscripts, first) { return String(sub) }
            return "_\(groups[1])"
        }
        for (command, symbol) in symbols {
            text = replace("\\\\\(command)(?![A-Za-z])", in: text) { _ in symbol }
        }
        for (command, letter) in greek {
            text = replace("\\\\\(command)(?![A-Za-z])", in: text) { _ in letter }
        }
        text = replace(#"\\([A-Za-z]+)"#, in: text) { $0[1] }
        text = replace(#"\^\{([^{}]*)\}"#, in: text) { $0[1] }
        text = replace(#"_\{([^{}]*)\}"#, in: text) { $0[1] }
        text = text.replacingOccurrences(of: "\\,", with: " ")
            .replacingOccurrences(of: "\\;", with: " ")
            .replacingOccurrences(of: "\\ ", with: " ")
        text = text.replacingOccurrences(of: "\\{", with: "{")
            .replacingOccurrences(of: "\\}", with: "}")
        text = text.replacingOccurrences(of: "{", with: "")
            .replacingOccurrences(of: "}", with: "")
        return text
    }

    static func wrap(_ value: String) -> String {
        value.count <= 1 ? value : "(\(value))"
    }

    static func lookup(_ table: [(Character, Character)], _ key: Character) -> Character? {
        for (plain, mapped) in table where plain == key { return mapped }
        return nil
    }

    // MARK: 内部

    /// 带分组的正则替换：`groups[0]` 是整段匹配，`groups[n]` 是第 n 个分组。
    ///
    /// 用闭包而不是 `$1` 模板，是因为 `\frac` 的替换要递归调用自己。
    static func replace(_ pattern: String, in text: String, _ transform: ([String]) -> String) -> String {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return text }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        let matches = regex.matches(in: text, options: [], range: range)
        if matches.isEmpty { return text }
        var result = ""
        var cursor = text.startIndex
        for match in matches {
            guard let matchRange = Range(match.range, in: text) else { continue }
            if matchRange.lowerBound > cursor {
                result += String(text[cursor..<matchRange.lowerBound])
            }
            var groups: [String] = []
            for index in 0..<match.numberOfRanges {
                let groupRange = match.range(at: index)
                if groupRange.location == NSNotFound {
                    groups.append("")
                } else if let swiftRange = Range(groupRange, in: text) {
                    groups.append(String(text[swiftRange]))
                } else {
                    groups.append("")
                }
            }
            result += transform(groups)
            cursor = matchRange.upperBound
        }
        if cursor < text.endIndex { result += String(text[cursor...]) }
        return result
    }
}
