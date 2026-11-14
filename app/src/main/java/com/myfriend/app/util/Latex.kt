package com.myfriend.app.util

/**
 * Lightweight, dependency-free LaTeX-to-readable-Unicode conversion for chat display.
 * It intentionally handles the constructs common in conversational math, not full TeX.
 */
object Latex {
    private val greek = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ",
        "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "chi" to "χ",
        "psi" to "ψ", "omega" to "ω", "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ",
        "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ",
        "Psi" to "Ψ", "Omega" to "Ω"
    )
    private val symbols = mapOf(
        "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "neq" to "≠", "ne" to "≠",
        "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇", "forall" to "∀", "exists" to "∃",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃",
        "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "to" to "→", "rightarrow" to "→",
        "leftarrow" to "←", "Rightarrow" to "⇒", "Leftarrow" to "⇐", "leftrightarrow" to "↔",
        "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮", "sqrt" to "√",
        "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "degree" to "°",
        "ldots" to "…", "cdots" to "⋯", "quad" to "  ", "qquad" to "    "
    )
    private val superscripts = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵',
        '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻',
        '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ',
        'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ', 'k' to 'ᵏ',
        'm' to 'ᵐ', 'p' to 'ᵖ', 't' to 'ᵗ', 'y' to 'ʸ'
    )
    private val subscripts = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅',
        '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋',
        '=' to '₌', '(' to '₍', ')' to '₎', 'a' to 'ₐ', 'e' to 'ₑ', 'i' to 'ᵢ',
        'j' to 'ⱼ', 'n' to 'ₙ', 'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ',
        'u' to 'ᵤ', 'v' to 'ᵥ', 'x' to 'ₓ'
    )

    fun toReadable(tex: String): String {
        var text = tex.trim()
        text = text.replace(Regex("""\\left\s*"""), "").replace(Regex("""\\right\s*"""), "")
        text = text.replace(Regex("""\\text\{([^{}]*)\}""")) { it.groupValues[1] }
        text = text.replace(Regex("""\\mathrm\{([^{}]*)\}""")) { it.groupValues[1] }
        text = text.replace(Regex("""\\mathbf\{([^{}]*)\}""")) { it.groupValues[1] }
        text = text.replace(Regex("""\\operatorname\{([^{}]*)\}""")) { it.groupValues[1] }
        repeat(4) {
            text = text.replace(Regex("""\\frac\{([^{}]*)\}\{([^{}]*)\}""")) {
                val a = toReadable(it.groupValues[1])
                val b = toReadable(it.groupValues[2])
                "${wrap(a)}⁄${wrap(b)}"
            }
            text = text.replace(Regex("""\\sqrt\[([^\]]*)\]\{([^{}]*)\}""")) {
                "√[${it.groupValues[1]}](${toReadable(it.groupValues[2])})"
            }
            text = text.replace(Regex("""\\sqrt\{([^{}]*)\}""")) {
                "√(${toReadable(it.groupValues[1])})"
            }
        }
        superscripts.forEach { (plain, sup) -> text = text.replace("^{$plain}", sup.toString()) }
        subscripts.forEach { (plain, sub) -> text = text.replace("_{$plain}", sub.toString()) }
        text = text.replace(Regex("""\^([0-9nix+\-=()])""")) { match ->
            superscripts[match.groupValues[1].first()]?.toString() ?: match.value
        }
        text = text.replace(Regex("""_([0-9a-zijmnprstuvx+\-=()])""")) { match ->
            subscripts[match.groupValues[1].first()]?.toString() ?: match.value
        }
        symbols.forEach { (command, symbol) ->
            text = text.replace(Regex("\\\\$command(?![A-Za-z])"), symbol)
        }
        greek.forEach { (command, letter) ->
            text = text.replace(Regex("\\\\$command(?![A-Za-z])"), letter)
        }
        text = text.replace(Regex("""\\([A-Za-z]+)""")) { it.groupValues[1] }
        text = text.replace(Regex("""\^\{([^{}]*)\}""")) { it.groupValues[1] }
        text = text.replace(Regex("""_\{([^{}]*)\}""")) { it.groupValues[1] }
        text = text.replace("\\,", " ").replace("\\;", " ").replace("\\ ", " ")
        text = text.replace("\\{", "{").replace("\\}", "}")
        text = text.replace("{", "").replace("}", "")
        return text
    }

    private fun wrap(value: String): String =
        if (value.length <= 1) value else "($value)"
}
