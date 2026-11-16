import Foundation

// ---------------------------------------------------------------------------
// 本地化（中 / 英）。
//
// 约定：
// - 两份 `Resources/{zh-Hans,en}.lproj/Localizable.strings` 的 **key 就是中文原文**，
//   所以代码里写 `L("保存")` 一眼能对上是哪一条；中文文件的值与 key 相同；
// - 需要插值的地方一律 `Lf("…%@…", …)` / `Lcount("…%d…", n)`，**不要**在调用点自己拼句子——
//   英语的语序和单复数都跟中文不一样；
// - 语言跟随系统（含 iOS 的 per-app language），App 内没有语言开关；
//   没有匹配语言的系统靠 `CFBundleDevelopmentRegion = zh-Hans` 回落中文。
// ---------------------------------------------------------------------------

/// App 当前生效的语言。
///
/// 判定以 `Bundle.main.preferredLocalizations` 为准：系统（或用户在设置里按 App 选的语言）
/// 会挑一个 `.lproj`，挑不到就回落到开发语言 `zh-Hans`。
enum AppLanguage: String, CaseIterable {
    case zhHans = "zh-Hans"
    case en = "en"

    static var current: AppLanguage {
        if let code = Bundle.main.preferredLocalizations.first?.lowercased() {
            if code.hasPrefix("en") { return .en }
            if code.hasPrefix("zh") { return .zhHans }
        }
        // 没有 bundle（单测、预览、命令行工具）时退回 Locale。
        let code = Locale.current.language.languageCode?.identifier.lowercased() ?? ""
        return code == "en" ? .en : .zhHans
    }

    /// 传给提示词「## 语言」小节的语言名。
    ///
    /// 与 Android `LanguageDirective.languageName()` 同一个语义：**用当前界面语言自己的说法**，
    /// 不把目标语言翻译成别的语言（中文界面「中文」，英文界面「English」，不是「英语」）。
    static var promptLanguageName: String {
        switch current {
        case .en: return "English"
        case .zhHans: return "中文"
        }
    }

    /// 系统提示词末尾的「语言」小节（完整文本，含 `## 语言` 标题）。
    ///
    /// 模板放在 `Localizable.strings` 的 `prompt_language_directive` 里：
    /// - 中文：`## 语言\n对方现在用%1$@跟你说话。…`
    /// - 英文：`## Language\nThey are speaking to you in %1$@ right now. …`
    ///
    /// 这是**唯一**一条 key 不是中文原文的资源：它的两份值不是「一句话的两种说法」，
    /// 而是两段面向模型的不同脚手架，用语义 key 更好认（与 Android 的 `R.string` 同名）。
    /// 资源缺失时回落到内置中文模板，绝不把 key 本身塞进提示词。
    static var languageDirective: String {
        let key = "prompt_language_directive"
        let template = NSLocalizedString("prompt_language_directive", comment: "")
        let resolved = template == key ? Self.chineseDirectiveFallback : template
        return String(format: resolved, promptLanguageName)
    }

    /// 与 Android `values/strings.xml` 的 `prompt_language_directive` 逐字一致
    /// （占位符按 iOS 的格式规则写成 `%1$@`）。
    static let chineseDirectiveFallback = "## 语言\n对方现在用%1$@跟你说话。请用同一种语言回答，专有名词可以保留原文。"

    static let englishDirectiveFallback = "## Language\nThey are speaking to you in %1$@ right now. Answer in the same language; proper nouns can stay as they are."
}

/// 取一条本地化文案。查不到时回落成 key 本身（也就是中文原文），
/// 不会把 `Localizable.strings` 的 key 或 "missing" 之类的东西露到界面上。
func L(_ key: String) -> String {
    NSLocalizedString(key, comment: "")
}

/// 带格式参数的文案。key 里用 `%@`（字符串）/ `%d`（整数）。
func Lf(_ key: String, _ args: CVarArg...) -> String {
    String(format: NSLocalizedString(key, comment: ""), arguments: args)
}

/// 带数量的文案：英语单复数用 `key + ".one"` 的第二条 key 承担，中文两条值相同。
///
/// 为什么不直接上 `.stringsdict`：这个工程只有两份 `.strings`，为几个数量词再引一套
/// 格式（还要在 pbxproj 里登记）不划算；`LocalizationResourceTests` 会同时钉住
/// 「两份 key 集合一致」和「占位符序列一致」，所以 `.one` 变体漏写会红。
///
/// `count` 会作为第一个格式参数传进去（模板里第一个占位符就是它）。
func Lcount(_ key: String, _ count: Int, _ args: CVarArg...) -> String {
    let oneKey = key + ".one"
    let one = NSLocalizedString(oneKey, comment: "")
    let template = (count == 1 && one != oneKey) ? one : NSLocalizedString(key, comment: "")
    return String(format: template, arguments: [count] + args)
}

/// 标点跟随语言：中文用顿号 / 全角逗号，英文用半角逗号。
///
/// 这些不是「文案」而是排版习惯，所以不进 `Localizable.strings`——翻译者不会想去翻一个顿号。
enum LText {
    /// 并列项之间：中文「、」，英文 ", "。
    static var listSeparator: String { AppLanguage.current == .en ? ", " : "、" }

    /// 一句话里两个短句之间：中文「，」，英文 ", "。
    static var clauseSeparator: String { AppLanguage.current == .en ? ", " : "，" }
}

/// 拼接两句本地化文案时的分隔：中文不加空格，英文加一个空格。
///
/// 为什么需要它：代码里原本把两句话直接 `+` 在一起（中文本来就不需要空格），
/// 一翻成英文就变成 "…himself.Turn it off…"。用这个常量拼，比把两句话硬合成一条 key 更稳。
extension LText {
    static var sentenceSeparator: String { AppLanguage.current == .en ? " " : "" }
}

// MARK: - 与 Kit 的种子人设对接

extension AppLanguage {
    /// 界面语言 → 种子人设语言。
    ///
    /// `SeedPersonas` 的数据在 Kit 里（0.11.0 P2-13：清空后重新播种要能单测），
    /// 而 Kit 不认识 App 的 `Bundle` 语言机制，所以由这里把当前语言"翻"成一个纯值。
    var seedLanguage: SeedLanguage {
        switch self {
        case .en: return .english
        case .zhHans: return .chinese
        }
    }
}
