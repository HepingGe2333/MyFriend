import Foundation

// ---------------------------------------------------------------------------
// 结构化人设卡（iOS 端移植）。
//
// 来源：app/src/main/java/com/yap/app/domain/PersonaCard.kt，逐条对齐字段名、上限、
// 裁剪优先级与兜底文案。这个文件是**纯逻辑**：不碰数据库、不碰 UI，任何解析/渲染入口
// 都不抛异常（坏输入一律退化成空卡或原卡）。
//
// 和旧版「一大段提示词」相比，人设卡把「你是谁 / 怎么说话 / 性格 / 在意什么 / 边界 /
// 生活 / 你们之间」拆成可单独修改的字段：系统提示词里只留 `stableStrategy` 这样一条短策略，
// 正文由 `renderContext` 按预算渲染成运行时上下文。模型可以在对话里通过 `PersonaCardPatch`
// 打补丁生成新版本（iOS 没有 revisions 表，版本号靠 `persona.version`）。
//
// 与 Android 的已知差异（都很小，已在各处注释）：
// - 长度按 Swift 的「字符（字素簇）」计，Kotlin 按 UTF-16 code unit 计；
//   中文日常内容两者一致，emoji 组合字符会略有出入。
// - JSON 解码用 Foundation 的严格 JSON 解析：坏 JSON / 类型不符返回空卡，与 Android 一致；
//   但 kotlinx 的 `isLenient`（单引号、无引号键）这里不接受，那种输入会退化成空卡。
// ---------------------------------------------------------------------------

/// 一个性格标签，`strength` 表示这个特质有多强（0..1）。
public struct PersonaTrait: Equatable, Sendable {
    public var name: String
    public var strength: Float

    public init(name: String = "", strength: Float = 0.5) {
        self.name = name
        self.strength = strength
    }
}

/// 身份层：名字、年龄感、身份/职业、和「你」的关系。
public struct PersonaIdentity: Equatable, Sendable {
    public var name: String
    /// 年龄感，如「二十多岁」。
    public var age: String
    /// 身份/职业。
    public var role: String
    /// 和「你」是什么关系。
    public var relation: String

    public init(name: String = "", age: String = "", role: String = "", relation: String = "") {
        self.name = name
        self.age = age
        self.role = role
        self.relation = relation
    }
}

/// 声音层：语速、句子长度、口头禅、称呼、语气小习惯。
public struct PersonaVoice: Equatable, Sendable {
    /// 语速，如「慢，偶尔停顿」。
    public var pace: String
    /// 句子长度偏好。
    public var sentence: String
    /// 口头禅。
    public var catchphrases: [String]
    /// 怎么称呼对方。
    public var address: String
    /// 标点/表情/语气小习惯。
    public var quirks: String

    public init(
        pace: String = "",
        sentence: String = "",
        catchphrases: [String] = [],
        address: String = "",
        quirks: String = ""
    ) {
        self.pace = pace
        self.sentence = sentence
        self.catchphrases = catchphrases
        self.address = address
        self.quirks = quirks
    }
}

/// 生活层：作息、当下在做什么、最近关心什么。属于最容易随对话变化、也最先被裁剪的细节。
public struct PersonaLife: Equatable, Sendable {
    /// 作息。
    public var schedule: String
    /// 现在正在做什么。
    public var doing: String
    /// 最近在关心什么。
    public var caresAbout: String

    public init(schedule: String = "", doing: String = "", caresAbout: String = "") {
        self.schedule = schedule
        self.doing = doing
        self.caresAbout = caresAbout
    }
}

/// 一张完整的人设卡。
public struct PersonaCard: Equatable, Sendable {
    public var identity: PersonaIdentity
    public var voice: PersonaVoice
    public var traits: [PersonaTrait]
    public var values: [String]
    public var boundaries: [String]
    public var life: PersonaLife
    /// 对「你」的认知与变化。
    public var bond: String

    public init(
        identity: PersonaIdentity = PersonaIdentity(),
        voice: PersonaVoice = PersonaVoice(),
        traits: [PersonaTrait] = [],
        values: [String] = [],
        boundaries: [String] = [],
        life: PersonaLife = PersonaLife(),
        bond: String = ""
    ) {
        self.identity = identity
        self.voice = voice
        self.traits = traits
        self.values = values
        self.boundaries = boundaries
        self.life = life
        self.bond = bond
    }

    /// 卡里是否还没有任何有效内容（空卡走稳定兜底策略，也代表「继续用旧提示词」）。
    public var isEmpty: Bool {
        identity.name.isBlank
            && identity.age.isBlank
            && identity.role.isBlank
            && identity.relation.isBlank
            && voice.pace.isBlank
            && voice.sentence.isBlank
            && voice.catchphrases.allSatisfy { $0.isBlank }
            && voice.address.isBlank
            && voice.quirks.isBlank
            && traits.allSatisfy { $0.name.isBlank }
            && values.allSatisfy { $0.isBlank }
            && boundaries.allSatisfy { $0.isBlank }
            && life.schedule.isBlank
            && life.doing.isBlank
            && life.caresAbout.isBlank
            && bond.isBlank
    }
}

/// 三档预算：注入正文的字数上限。
public enum PromptBudget: CaseIterable, Sendable {
    case lean
    case normal
    case rich

    public var maxChars: Int {
        switch self {
        case .lean: return 600
        case .normal: return 1400
        case .rich: return 2600
        }
    }
}

/// 人设卡的字段上限（与 Android 的私有常量一一对应）。
///
/// 公开出来是给编辑页夹输入用的——上限的**唯一事实来源**仍然是这里与 `sanitized()`，
/// 编辑页只是别让用户白敲一半字再被裁掉。
public enum PersonaCardLimits {
    public static let nameMax = 40
    public static let roleMax = 80
    public static let fieldMax = 240
    public static let listItemMax = 60
    public static let bondMax = 2000
    public static let bondRenderMax = 400
    public static let maxTraits = 12
    public static let maxListItems = 8
    public static let maxLegacyBoundaries = 8
    public static let maxLegacyPaceParts = 4
}

// MARK: - 字符串清洗

extension String {
    /// 是否是空白（Kotlin 的 `isBlank()`：空白字符 + 换行都算空）。
    fileprivate var isBlank: Bool {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// 折成一行并限制长度；超长时尽量在句读处停下，实在没有句读才补省略号。
    ///
    /// 逐字对应 Android 的 `String.cleaned(max)`。
    fileprivate func personaCleaned(_ max: Int) -> String {
        if max <= 0 { return "" }
        let oneLine = trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if oneLine.count <= max { return oneLine }
        let cut = String(oneLine.prefix(max - 1))
        let boundary = lastMarkerOffset(cut, in: PersonaCardText.cleanedBoundaryChars)
        if let boundary, boundary >= max / 2 {
            return String(cut.prefix(boundary + 1)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return cut.trimmingCharacters(in: .whitespacesAndNewlines) + "…"
    }

    /// Kotlin 的 `trimEnd()`：只去掉结尾的空白字符。
    fileprivate func personaTrimEnd() -> String {
        var text = self
        while let last = text.last, last.isWhitespace {
            text.removeLast()
        }
        return text
    }

    /// Kotlin 的 `trimEnd(vararg chars)`：只去掉结尾这些字符。
    fileprivate func personaTrimEnd(_ chars: Set<Character>) -> String {
        var text = self
        while let last = text.last, chars.contains(last) {
            text.removeLast()
        }
        return text
    }
}

/// 清洗、解析时用到的字符集合与标记词（与 Android 的常量逐字一致）。
private enum PersonaCardText {
    static let cleanedBoundaryChars: Set<Character> = ["。", "！", "？", "；", "，", "、", " "]
    static let sentenceBoundaryChars: Set<Character> = ["。", "！", "？", "；", "\n"]
    static let legacyNameBoundaryChars: Set<Character> = [
        "，", ",", "。", "、", "；", ";", "：", ":", "！", "!", "？", "?", " "
    ]
    static let legacyNameTailChars: Set<Character> = ["。", "，", ".", "!", "！", "？", "?"]

    static let boundaryMarkers = ["不要", "别", "禁止", "避免", "不能", "不得", "切勿"]
    static let voiceMarkers = ["说话", "语气", "语速", "表达", "称呼", "句子", "用词", "口头禅"]
    static let relationMarkers = [
        "恋人", "爱人", "伴侣", "朋友", "知己", "搭档", "同学", "同事", "家人", "导师", "邻居"
    ]
}

/// 找到 `text` 里最后一个属于 `markers` 的字符的位置（按字符偏移，0 起）。
private func lastMarkerOffset(_ text: String, in markers: Set<Character>) -> Int? {
    var result: Int?
    var offset = 0
    var index = text.startIndex
    while index < text.endIndex {
        if markers.contains(text[index]) { result = offset }
        index = text.index(after: index)
        offset += 1
    }
    return result
}

/// 找到 `text` 里第一个属于 `markers` 的字符的位置（按字符偏移，0 起）。
private func firstMarkerOffset(_ text: String, in markers: Set<Character>) -> Int? {
    var offset = 0
    var index = text.startIndex
    while index < text.endIndex {
        if markers.contains(text[index]) { return offset }
        index = text.index(after: index)
        offset += 1
    }
    return nil
}

// MARK: - 收敛

extension PersonaCard {
    /// 把任意来源的卡收敛到安全范围：去空项、夹紧 strength、限制字段长度。
    ///
    /// 逐字对应 Android 的 `PersonaCard.sanitized()`。
    func sanitized() -> PersonaCard {
        PersonaCard(
            identity: PersonaIdentity(
                name: identity.name.personaCleaned(PersonaCardLimits.nameMax),
                age: identity.age.personaCleaned(PersonaCardLimits.nameMax),
                role: identity.role.personaCleaned(PersonaCardLimits.roleMax),
                relation: identity.relation.personaCleaned(PersonaCardLimits.nameMax)
            ),
            voice: PersonaVoice(
                pace: voice.pace.personaCleaned(PersonaCardLimits.fieldMax),
                sentence: voice.sentence.personaCleaned(PersonaCardLimits.fieldMax),
                catchphrases: Array(
                    voice.catchphrases
                        .map { $0.personaCleaned(PersonaCardLimits.listItemMax) }
                        .filter { !$0.isBlank }
                        .prefix(PersonaCardLimits.maxListItems)
                ),
                address: voice.address.personaCleaned(PersonaCardLimits.nameMax),
                quirks: voice.quirks.personaCleaned(PersonaCardLimits.fieldMax)
            ),
            traits: Array(
                traits
                    .map { PersonaTrait(name: $0.name.personaCleaned(PersonaCardLimits.nameMax),
                                        strength: min(max($0.strength, 0), 1)) }
                    .filter { !$0.name.isBlank }
                    .prefix(PersonaCardLimits.maxTraits)
            ),
            values: Array(
                values.map { $0.personaCleaned(PersonaCardLimits.listItemMax) }
                    .filter { !$0.isBlank }
                    .prefix(PersonaCardLimits.maxListItems)
            ),
            boundaries: Array(
                boundaries.map { $0.personaCleaned(PersonaCardLimits.listItemMax) }
                    .filter { !$0.isBlank }
                    .prefix(PersonaCardLimits.maxListItems)
            ),
            life: PersonaLife(
                schedule: life.schedule.personaCleaned(PersonaCardLimits.fieldMax),
                doing: life.doing.personaCleaned(PersonaCardLimits.fieldMax),
                caresAbout: life.caresAbout.personaCleaned(PersonaCardLimits.fieldMax)
            ),
            bond: bond.personaCleaned(PersonaCardLimits.bondMax)
        )
    }
}

// MARK: - JSON 编解码

/// 人设卡的 JSON 编解码。用于本地持久化，不参与网络协议。
///
/// 编码语义对齐 Android 的 kotlinx 配置 `encodeDefaults = false` + `explicitNulls = false`：
/// **等于默认值的字段不写出**（空字符串、空数组、空对象、strength == 0.5），所以空卡编码出来是 `{}`。
/// 手写编码器而不是用 `Codable`：要的就是这份「与 kotlinx 逐字节接近」的确定性，
/// 以及字段声明顺序稳定（备份 diff 才不会每天变）。
public enum PersonaCardCodec {

    public static func encode(_ card: PersonaCard) -> String {
        // 非有限浮点在 kotlinx 里会直接序列化失败（runCatching -> "{}"），这里保持一致。
        guard card.traits.allSatisfy({ $0.strength.isFinite }) else { return "{}" }
        var pairs: [String] = []
        if let identity = encodeIdentity(card.identity) { pairs.append("\"identity\":\(identity)") }
        if let voice = encodeVoice(card.voice) { pairs.append("\"voice\":\(voice)") }
        if let traits = encodeTraits(card.traits) { pairs.append("\"traits\":\(traits)") }
        if let values = encodeStringArray(card.values), !card.values.isEmpty {
            pairs.append("\"values\":\(values)")
        }
        if let boundaries = encodeStringArray(card.boundaries), !card.boundaries.isEmpty {
            pairs.append("\"boundaries\":\(boundaries)")
        }
        if let life = encodeLife(card.life) { pairs.append("\"life\":\(life)") }
        if !card.bond.isEmpty { pairs.append("\"bond\":\(jsonString(card.bond))") }
        return "{" + pairs.joined(separator: ",") + "}"
    }

    /// nil / 空串 / 坏 JSON / 类型不匹配都返回空卡，绝不抛。
    public static func decode(_ raw: String?) -> PersonaCard {
        guard let raw else { return PersonaCard() }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return PersonaCard() }
        guard let data = trimmed.data(using: .utf8),
              let value = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]),
              let root = value as? [String: Any] else {
            return PersonaCard()
        }
        do {
            return try decodeCard(root).sanitized()
        } catch {
            return PersonaCard()
        }
    }

    // MARK: 编码

    private static func encodeIdentity(_ identity: PersonaIdentity) -> String? {
        var parts: [String] = []
        if !identity.name.isEmpty { parts.append("\"name\":\(jsonString(identity.name))") }
        if !identity.age.isEmpty { parts.append("\"age\":\(jsonString(identity.age))") }
        if !identity.role.isEmpty { parts.append("\"role\":\(jsonString(identity.role))") }
        if !identity.relation.isEmpty { parts.append("\"relation\":\(jsonString(identity.relation))") }
        return parts.isEmpty ? nil : "{" + parts.joined(separator: ",") + "}"
    }

    private static func encodeVoice(_ voice: PersonaVoice) -> String? {
        var parts: [String] = []
        if !voice.pace.isEmpty { parts.append("\"pace\":\(jsonString(voice.pace))") }
        if !voice.sentence.isEmpty { parts.append("\"sentence\":\(jsonString(voice.sentence))") }
        if let catchphrases = encodeStringArray(voice.catchphrases) {
            parts.append("\"catchphrases\":\(catchphrases)")
        }
        if !voice.address.isEmpty { parts.append("\"address\":\(jsonString(voice.address))") }
        if !voice.quirks.isEmpty { parts.append("\"quirks\":\(jsonString(voice.quirks))") }
        return parts.isEmpty ? nil : "{" + parts.joined(separator: ",") + "}"
    }

    private static func encodeLife(_ life: PersonaLife) -> String? {
        var parts: [String] = []
        if !life.schedule.isEmpty { parts.append("\"schedule\":\(jsonString(life.schedule))") }
        if !life.doing.isEmpty { parts.append("\"doing\":\(jsonString(life.doing))") }
        if !life.caresAbout.isEmpty { parts.append("\"caresAbout\":\(jsonString(life.caresAbout))") }
        return parts.isEmpty ? nil : "{" + parts.joined(separator: ",") + "}"
    }

    private static func encodeTraits(_ traits: [PersonaTrait]) -> String? {
        guard !traits.isEmpty else { return nil }
        let items = traits.map { trait -> String in
            var parts: [String] = []
            if !trait.name.isEmpty { parts.append("\"name\":\(jsonString(trait.name))") }
            // Float 比较：0.5 是可精确表示的默认值，与 kotlinx 的 encodeDefaults=false 一致。
            if trait.strength != 0.5 { parts.append("\"strength\":\(numberText(trait.strength))") }
            return "{" + parts.joined(separator: ",") + "}"
        }
        return "[" + items.joined(separator: ",") + "]"
    }

    private static func encodeStringArray(_ values: [String]) -> String? {
        guard !values.isEmpty else { return nil }
        return "[" + values.map(jsonString).joined(separator: ",") + "]"
    }

    /// 浮点的 JSON 文本：`1.0` 也要写成 `1.0`（与 kotlinx 的 Float.toString 一致）。
    private static func numberText(_ value: Float) -> String {
        if !value.isFinite { return "0.5" }
        return String(value)
    }

    private static func jsonString(_ value: String) -> String {
        var output = "\""
        for scalar in value.unicodeScalars {
            switch scalar {
            case "\"": output += "\\\""
            case "\\": output += "\\\\"
            case "\n": output += "\\n"
            case "\r": output += "\\r"
            case "\t": output += "\\t"
            default:
                if scalar.value < 0x20 {
                    output += String(format: "\\u%04x", scalar.value)
                } else {
                    output.unicodeScalars.append(scalar)
                }
            }
        }
        output += "\""
        return output
    }

    // MARK: 解码

    private enum DecodeFailure: Error {
        case typeMismatch
    }

    private static func decodeCard(_ root: [String: Any]) throws -> PersonaCard {
        var identity = PersonaIdentity()
        if let obj = try objectValue(root, "identity") {
            identity = PersonaIdentity(
                name: try stringValue(obj, "name"),
                age: try stringValue(obj, "age"),
                role: try stringValue(obj, "role"),
                relation: try stringValue(obj, "relation")
            )
        }
        var voice = PersonaVoice()
        if let obj = try objectValue(root, "voice") {
            voice = PersonaVoice(
                pace: try stringValue(obj, "pace"),
                sentence: try stringValue(obj, "sentence"),
                catchphrases: try stringListValue(obj, "catchphrases"),
                address: try stringValue(obj, "address"),
                quirks: try stringValue(obj, "quirks")
            )
        }
        var life = PersonaLife()
        if let obj = try objectValue(root, "life") {
            life = PersonaLife(
                schedule: try stringValue(obj, "schedule"),
                doing: try stringValue(obj, "doing"),
                caresAbout: try stringValue(obj, "caresAbout")
            )
        }
        return PersonaCard(
            identity: identity,
            voice: voice,
            traits: try traitsValue(root, "traits"),
            values: try stringListValue(root, "values"),
            boundaries: try stringListValue(root, "boundaries"),
            life: life,
            bond: try stringValue(root, "bond")
        )
    }

    /// JSON null 在 `explicitNulls = false` 里等价于「字段没出现」。
    private static func objectValue(_ dict: [String: Any], _ key: String) throws -> [String: Any]? {
        guard let raw = dict[key], !(raw is NSNull) else { return nil }
        guard let obj = raw as? [String: Any] else { throw DecodeFailure.typeMismatch }
        return obj
    }

    private static func stringValue(_ dict: [String: Any], _ key: String) throws -> String {
        guard let raw = dict[key], !(raw is NSNull) else { return "" }
        guard let text = raw as? String else { throw DecodeFailure.typeMismatch }
        return text
    }

    private static func stringListValue(_ dict: [String: Any], _ key: String) throws -> [String] {
        guard let raw = dict[key], !(raw is NSNull) else { return [] }
        guard let array = raw as? [Any] else { throw DecodeFailure.typeMismatch }
        return try array.map { element in
            guard let text = element as? String else { throw DecodeFailure.typeMismatch }
            return text
        }
    }

    private static func traitsValue(_ dict: [String: Any], _ key: String) throws -> [PersonaTrait] {
        guard let raw = dict[key], !(raw is NSNull) else { return [] }
        guard let array = raw as? [Any] else { throw DecodeFailure.typeMismatch }
        return try array.map { element in
            guard let obj = element as? [String: Any] else { throw DecodeFailure.typeMismatch }
            let name = try stringValue(obj, "name")
            var strength: Float = 0.5
            if let rawStrength = obj["strength"], !(rawStrength is NSNull) {
                if isJSONBoolean(rawStrength) { throw DecodeFailure.typeMismatch }
                guard let number = rawStrength as? NSNumber else { throw DecodeFailure.typeMismatch }
                strength = number.floatValue
            }
            return PersonaTrait(name: name, strength: strength)
        }
    }

    private static func isJSONBoolean(_ value: Any) -> Bool {
        guard let number = value as? NSNumber else { return false }
        return CFGetTypeID(number) == CFBooleanGetTypeID()
    }
}

// MARK: - 卡 -> 提示词

/// 人设卡 -> 提示词。
///
/// 分成两层：
/// - `stableStrategy` 很短、很稳定，放系统提示词最前面，任何预算下都不删；
/// - `renderContext` 是随预算伸缩的正文，按「细节先掉、身份与声音最后掉」的顺序整段丢弃。
public enum PersonaCardPrompt {

    private static let strategyMax = 80
    private static let strategyFallback = "用自然、简短、像真人发消息的方式说话。"
    private static let strategyTail = "；先回应再展开，别解释自己是 AI。"
    private static let placeholder = "（还没有设定）"
    private static let protectedPriority = 100

    // 各段落/行的丢弃优先级：数字越小越先被丢掉（与 Android 逐字一致）。
    private static let pLife = 8
    private static let pQuirk = 12
    private static let pBond = 34
    private static let pValues = 52
    private static let pBoundaries = 56
    private static let pCatchphrase = 60
    private static let pTrait = 64
    private static let pSentence = 76
    private static let pAge = 80
    private static let pAddress = 82
    private static let pRelation = 84
    private static let pRole = 88

    /// 约 60 字以内的稳定策略；空卡返回通用兜底，保证长度不超过 80 字。
    public static func stableStrategy(_ card: PersonaCard) -> String {
        let clean = card.sanitized()
        let name = clean.identity.name.isBlank ? clean.identity.role : clean.identity.name
        if clean.isEmpty || name.isBlank { return strategyFallback }

        var result = "你是" + name.personaCleaned(16)
        var extras: [String] = []
        if !clean.identity.role.isBlank && !clean.identity.name.isBlank {
            extras.append("，" + clean.identity.role.personaCleaned(16))
        }
        if !clean.identity.relation.isBlank {
            extras.append("，对方的" + clean.identity.relation.personaCleaned(12))
        }
        if !clean.voice.address.isBlank {
            extras.append("，称呼对方「" + clean.voice.address.personaCleaned(8) + "」")
        }
        if !clean.voice.pace.isBlank {
            // 旧提示词里常写成「你说话从容、克制」「语速偏慢」，先剥掉主语再拼，免得出现「说话你说话…」。
            var pace = clean.voice.pace.personaCleaned(12)
            for prefix in ["你说话", "我说话", "说话", "语速", "：", "，"] where pace.hasPrefix(prefix) {
                pace = String(pace.dropFirst(prefix.count))
            }
            pace = pace.trimmingCharacters(in: .whitespacesAndNewlines)
            if !pace.isBlank { extras.append("，说话" + pace) }
        }
        if let firstTrait = clean.traits.first {
            extras.append("，" + firstTrait.name.personaCleaned(10))
        }
        for extra in extras {
            if result.count + extra.count + strategyTail.count <= strategyMax {
                result += extra
            }
        }
        result += strategyTail
        if result.count > strategyMax {
            result = String(result.prefix(strategyMax))
        }
        return result.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 人设卡正文 -> Markdown 段落，按 `budget` 裁剪。
    ///
    /// 裁剪单位是整行（不会从句子里截断）：生活细节、语气小习惯最先掉，
    /// 身份与声音至少各保留一行；两段标题始终输出，空内容用占位句兜底。
    public static func renderContext(_ card: PersonaCard, budget: PromptBudget = .normal) -> String {
        let sections = buildSections(card.sanitized())
        var kept = Set<Int>()
        for section in sections {
            for line in section.lines { kept.insert(line.id) }
        }
        var rendered = renderSections(sections, kept: kept)

        if rendered.count > budget.maxChars {
            var droppable: [CardLine] = []
            for section in sections {
                for line in section.lines where line.priority < protectedPriority {
                    droppable.append(line)
                }
            }
            // Android 的 sortedBy 是稳定排序：同优先级按行号（id）保序。
            droppable.sort { left, right in
                if left.priority != right.priority { return left.priority < right.priority }
                return left.id < right.id
            }
            for line in droppable {
                if rendered.count <= budget.maxChars { break }
                if kept.remove(line.id) != nil {
                    rendered = renderSections(sections, kept: kept)
                }
            }
        }
        if rendered.count > budget.maxChars {
            rendered = truncateToBudget(rendered, maxChars: budget.maxChars)
        }
        return rendered.personaTrimEnd()
    }

    /// 从旧版「一大段提示词」尽力解析出人设卡。
    ///
    /// 识别不出的原文整体保留在 bond（≤2000 字时全文保留），并补一个默认 trait，
    /// 保证旧人格不会因为结构化而丢失设定。
    public static func fromLegacyPrompt(_ prompt: String) -> PersonaCard {
        let raw = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.isEmpty { return PersonaCard() }
        return parseLegacy(raw).sanitized()
    }

    // MARK: 正文裁剪的实现

    private struct CardLine {
        let id: Int
        let text: String
        let priority: Int
    }

    private struct CardSection {
        let title: String
        let required: Bool
        let lines: [CardLine]
    }

    private static func buildSections(_ card: PersonaCard) -> [CardSection] {
        var nextId = 0
        func makeLine(_ text: String, _ priority: Int) -> CardLine {
            let line = CardLine(id: nextId, text: text, priority: priority)
            nextId += 1
            return line
        }

        // 你是谁：名字/年龄感/身份/关系，第一行受保护。
        var identityLines: [CardLine] = []
        func addIdentity(_ text: String, _ priority: Int) {
            identityLines.append(makeLine(text, identityLines.isEmpty ? protectedPriority : priority))
        }
        if !card.identity.name.isBlank { addIdentity("- 名字：\(card.identity.name)", pRole) }
        if !card.identity.age.isBlank { addIdentity("- 年龄感：\(card.identity.age)", pAge) }
        if !card.identity.role.isBlank { addIdentity("- 身份：\(card.identity.role)", pRole) }
        if !card.identity.relation.isBlank {
            addIdentity("- 和你的关系：\(card.identity.relation)", pRelation)
        }

        // 说话方式：语速/句子/口头禅/称呼/小习惯，第一行受保护。
        var voiceLines: [CardLine] = []
        func addVoice(_ text: String, _ priority: Int) {
            voiceLines.append(makeLine(text, voiceLines.isEmpty ? protectedPriority : priority))
        }
        if !card.voice.pace.isBlank { addVoice("- 语速：\(card.voice.pace)", pAddress) }
        if !card.voice.sentence.isBlank { addVoice("- 句子：\(card.voice.sentence)", pSentence) }
        if !card.voice.catchphrases.isEmpty {
            let phrases = card.voice.catchphrases.map { "「\($0)」" }.joined(separator: "、")
            addVoice("- 口头禅：\(phrases)", pCatchphrase)
        }
        if !card.voice.address.isBlank { addVoice("- 称呼你：\(card.voice.address)", pAddress) }
        if !card.voice.quirks.isBlank { addVoice("- 小习惯：\(card.voice.quirks)", pQuirk) }

        let traitLines = card.traits.map {
            makeLine("- \($0.name)（强度 \(strengthLabel($0.strength))）", pTrait)
        }
        let valueLines = card.values.map { makeLine("- \($0)", pValues) }
        let boundaryLines = card.boundaries.map { makeLine("- \($0)", pBoundaries) }

        var lifeLines: [CardLine] = []
        if !card.life.schedule.isBlank { lifeLines.append(makeLine("- 作息：\(card.life.schedule)", pLife)) }
        if !card.life.doing.isBlank { lifeLines.append(makeLine("- 现在在：\(card.life.doing)", pLife)) }
        if !card.life.caresAbout.isBlank {
            lifeLines.append(makeLine("- 最近在意：\(card.life.caresAbout)", pLife))
        }

        var bondLines: [CardLine] = []
        if !card.bond.isBlank {
            bondLines.append(makeLine(card.bond.personaCleaned(PersonaCardLimits.bondRenderMax), pBond))
        }

        return [
            CardSection(title: "你是谁", required: true, lines: identityLines),
            CardSection(title: "说话方式", required: true, lines: voiceLines),
            CardSection(title: "性格", required: false, lines: traitLines),
            CardSection(title: "在意的事", required: false, lines: valueLines),
            CardSection(title: "边界", required: false, lines: boundaryLines),
            CardSection(title: "生活", required: false, lines: lifeLines),
            CardSection(title: "你们之间", required: false, lines: bondLines)
        ]
    }

    private static func renderSections(_ sections: [CardSection], kept: Set<Int>) -> String {
        var output: [String] = []
        for section in sections {
            let lines = section.lines.filter { kept.contains($0.id) }.map { $0.text }
            let body: String
            if !lines.isEmpty {
                body = lines.joined(separator: "\n")
            } else if section.required {
                body = placeholder
            } else {
                continue
            }
            output.append("### \(section.title)\n\(body)")
        }
        return output.joined(separator: "\n\n")
    }

    /// 兜底：极端输入下按句读处截断，保证不超过预算且不以半个字/半个段落收尾。
    private static func truncateToBudget(_ text: String, maxChars: Int) -> String {
        if text.count <= maxChars { return text }
        let cut = String(text.prefix(maxChars))
        let boundary = lastMarkerOffset(cut, in: PersonaCardText.sentenceBoundaryChars)
        let result: String
        if let boundary, boundary >= maxChars / 2 {
            result = String(cut.prefix(boundary + 1))
        } else {
            result = cut
        }
        return result.personaTrimEnd()
    }

    private static func strengthLabel(_ strength: Float) -> String {
        let clamped = min(max(strength, 0), 1)
        let tenths = Int((clamped * 10).rounded())
        return tenths >= 10 ? "1.0" : "0.\(tenths)"
    }

    // MARK: 旧版提示词解析

    private static func parseLegacy(_ raw: String) -> PersonaCard {
        var name = ""
        var role = ""
        var relation = ""
        var paceParts: [String] = []
        var boundaryParts: [String] = []
        var heading = ""

        for rawLine in raw.components(separatedBy: .newlines) {
            let trimmed = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { continue }
            if trimmed.hasPrefix("#") {
                heading = String(trimmed.drop(while: { $0 == "#" }))
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                continue
            }
            var body = stripBulletPrefix(trimmed)
            body = stripOrderPrefix(body)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if body.isEmpty { continue }

            // 「你是X，一个……的朋友。」
            if name.isBlank {
                let prefix: String?
                if body.hasPrefix("你是") {
                    prefix = "你是"
                } else if body.hasPrefix("你叫") {
                    prefix = "你叫"
                } else {
                    prefix = nil
                }
                if let prefix {
                    let after = String(body.dropFirst(prefix.count))
                    let cut = firstMarkerOffset(after, in: PersonaCardText.legacyNameBoundaryChars)
                    let parsedName = (cut.map { String(after.prefix($0)) } ?? after)
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    if !parsedName.isBlank && parsedName.count <= 20 {
                        name = parsedName
                        let tail: String
                        if let cut {
                            tail = String(after.dropFirst(cut + 1))
                                .trimmingCharacters(in: .whitespacesAndNewlines)
                                .personaTrimEnd(PersonaCardText.legacyNameTailChars)
                        } else {
                            tail = ""
                        }
                        if !tail.isBlank {
                            role = tail
                            relation = PersonaCardText.relationMarkers.first(where: { tail.contains($0) }) ?? ""
                        }
                        continue
                    }
                }
            }

            // 「不要…」「别…」这类句子 -> 边界
            if isBoundaryLine(body, heading: heading) {
                boundaryParts.append(body)
                continue
            }
            // 「说话…」「语气…」或位于「表达方式」小节下的句子 -> 语速/语气描述
            if isVoiceLine(body, heading: heading) {
                paceParts.append(body)
            }
        }

        let recognized = !name.isBlank || !role.isBlank
            || !paceParts.isEmpty || !boundaryParts.isEmpty
        return PersonaCard(
            identity: PersonaIdentity(name: name, role: role, relation: relation),
            voice: PersonaVoice(
                pace: paceParts.prefix(PersonaCardLimits.maxLegacyPaceParts).joined(separator: "；")
            ),
            // 识别不出任何结构时，至少要留下「这是原文里的自我设定」这一条，避免人格塌成空卡。
            traits: recognized ? [] : [PersonaTrait(name: "保持原文里的自我设定", strength: 0.6)],
            boundaries: Array(boundaryParts.prefix(PersonaCardLimits.maxLegacyBoundaries)),
            // bond 是自由文本段：≤2000 字时整体保留原文，保证旧设定不丢。
            bond: legacyBond(raw)
        )
    }

    /// Kotlin `trimmed.replace(BULLET_PREFIX, "")`：只剥掉行首的项目符号。
    private static func stripBulletPrefix(_ text: String) -> String {
        let bullets: Set<Character> = ["-", "*", "•", "·", ">"]
        var body = text
        while let first = body.first, bullets.contains(first) {
            body.removeFirst()
        }
        while let first = body.first, first.isWhitespace {
            body.removeFirst()
        }
        return body
    }

    /// Kotlin `replace(ORDER_PREFIX, "")`：只剥掉行首的「1. / 2、」这类编号。
    private static func stripOrderPrefix(_ text: String) -> String {
        var index = text.startIndex
        var digits = 0
        while index < text.endIndex, text[index].isASCII, text[index].isNumber {
            index = text.index(after: index)
            digits += 1
        }
        guard digits > 0, index < text.endIndex else { return text }
        let punctuation: Set<Character> = [".", "、", ")", "）"]
        guard punctuation.contains(text[index]) else { return text }
        var body = String(text[text.index(after: index)...])
        while let first = body.first, first.isWhitespace {
            body.removeFirst()
        }
        return body
    }

    private static func isBoundaryLine(_ body: String, heading: String) -> Bool {
        if body.hasPrefix("不") || body.hasPrefix("别") { return true }
        if PersonaCardText.boundaryMarkers.contains(where: { body.hasPrefix($0) }) { return true }
        return heading.contains("边界") || heading.contains("原则") || heading.contains("禁止")
    }

    private static func isVoiceLine(_ body: String, heading: String) -> Bool {
        if heading.contains("表达") || heading.contains("说话") || heading.contains("语气") { return true }
        return PersonaCardText.voiceMarkers.contains(where: { body.contains($0) })
    }

    private static func legacyBond(_ raw: String) -> String {
        if raw.count <= PersonaCardLimits.bondMax { return raw }
        let cut = String(raw.prefix(PersonaCardLimits.bondMax))
        let boundary = lastMarkerOffset(cut, in: PersonaCardText.sentenceBoundaryChars)
        if let boundary, boundary >= PersonaCardLimits.bondMax * 3 / 4 {
            return String(cut.prefix(boundary + 1))
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return cut.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

// MARK: - 模型补丁

/// 模型提出的人设补丁。
///
/// 约定：只覆盖补丁里出现过的字段；列表字段是「替换」语义；未知字段、类型不符的字段一律忽略；
/// 坏 JSON 返回原卡，保证打补丁失败不会破坏已有的人设。
public enum PersonaCardPatch {

    private static let fallbackSummary = String(localized: "调整了人设")
    private static let summaryMax = 60

    /// 把 JSON 补丁应用到人设卡上。坏 JSON / 非对象一律返回原卡。
    public static func apply(_ card: PersonaCard, patchJson: String) -> PersonaCard {
        guard let root = jsonObject(patchJson) else { return card }

        var identity = card.identity
        if let obj = objectOrNil(root, "identity") {
            identity = PersonaIdentity(
                name: textOrNil(obj, "name") ?? identity.name,
                age: textOrNil(obj, "age") ?? identity.age,
                role: textOrNil(obj, "role") ?? identity.role,
                relation: textOrNil(obj, "relation") ?? identity.relation
            )
        }

        var voice = card.voice
        if let obj = objectOrNil(root, "voice") {
            voice = PersonaVoice(
                pace: textOrNil(obj, "pace") ?? voice.pace,
                sentence: textOrNil(obj, "sentence") ?? voice.sentence,
                catchphrases: stringListOrNil(obj, "catchphrases") ?? voice.catchphrases,
                address: textOrNil(obj, "address") ?? voice.address,
                quirks: textOrNil(obj, "quirks") ?? voice.quirks
            )
        }

        var traits = card.traits
        if let array = arrayOrNil(root, "traits") {
            traits = array.compactMap { element -> PersonaTrait? in
                guard let obj = element as? [String: Any],
                      let name = textOrNil(obj, "name"),
                      !name.isBlank else { return nil }
                var strength: Float = 0.5
                if let raw = obj["strength"], !(raw is NSNull) {
                    if let text = raw as? String {
                        strength = Float(text) ?? 0.5
                    } else if let number = raw as? NSNumber, !isJSONBoolean(number) {
                        strength = number.floatValue
                    }
                }
                return PersonaTrait(name: name, strength: strength)
            }
        }

        let values = stringListOrNil(root, "values") ?? card.values
        let boundaries = stringListOrNil(root, "boundaries") ?? card.boundaries

        var life = card.life
        if let obj = objectOrNil(root, "life") {
            life = PersonaLife(
                schedule: textOrNil(obj, "schedule") ?? life.schedule,
                doing: textOrNil(obj, "doing") ?? life.doing,
                caresAbout: textOrNil(obj, "caresAbout") ?? life.caresAbout
            )
        }

        let bond = textOrNil(root, "bond") ?? card.bond

        return PersonaCard(
            identity: identity,
            voice: voice,
            traits: traits,
            values: values,
            boundaries: boundaries,
            life: life,
            bond: bond
        ).sanitized()
    }

    /// 给用户看的一行摘要，如「说话习惯·称呼、性格·嘴硬」；解析失败返回「调整了人设」。
    public static func summarize(_ patchJson: String) -> String {
        guard let root = jsonObject(patchJson) else { return fallbackSummary }
        var parts: [String] = []
        // 摘要里的固定词都跟随界面语言；用户填的标签原样保留。
        let itemSeparator = String(localized: "、")

        if let obj = objectOrNil(root, "identity") {
            let label: String?
            if textOrNil(obj, "name") != nil {
                label = String(localized: "名字")
            } else if textOrNil(obj, "role") != nil {
                label = String(localized: "身份")
            } else if textOrNil(obj, "age") != nil {
                label = String(localized: "年龄感")
            } else if textOrNil(obj, "relation") != nil {
                label = String(localized: "关系")
            } else {
                label = nil
            }
            if let label {
                parts.append(String(format: String(localized: "身份·%@"), label))
            }
        }

        if let obj = objectOrNil(root, "voice") {
            // 只挑一个最能代表这次改动的点，保持摘要是一行。
            let label: String?
            if textOrNil(obj, "address") != nil {
                label = String(localized: "称呼")
            } else if textOrNil(obj, "pace") != nil {
                label = String(localized: "语速")
            } else if stringListOrNil(obj, "catchphrases") != nil {
                label = String(localized: "口头禅")
            } else if textOrNil(obj, "sentence") != nil {
                label = String(localized: "句子长短")
            } else if textOrNil(obj, "quirks") != nil {
                label = String(localized: "小习惯")
            } else {
                label = nil
            }
            if let label {
                parts.append(String(format: String(localized: "说话习惯·%@"), label))
            }
        }

        if let array = arrayOrNil(root, "traits") {
            let names = array.compactMap { element -> String? in
                guard let obj = element as? [String: Any] else { return nil }
                return textOrNil(obj, "name")
            }
            if !names.isEmpty {
                parts.append(String(
                    format: String(localized: "性格·%@"),
                    names.prefix(3).joined(separator: itemSeparator)
                ))
            }
        }

        if stringListOrNil(root, "values") != nil { parts.append(String(localized: "在意的事")) }
        if stringListOrNil(root, "boundaries") != nil { parts.append(String(localized: "边界")) }
        if objectOrNil(root, "life") != nil { parts.append(String(localized: "生活")) }
        if textOrNil(root, "bond") != nil { parts.append(String(localized: "你们之间")) }

        if parts.isEmpty { return fallbackSummary }
        let summary = parts.joined(separator: itemSeparator)
        if summary.count > summaryMax {
            return String(summary.prefix(summaryMax - 1)) + "…"
        }
        return summary
    }

    // MARK: 温和的 JSON 取值工具（类型不符一律当作「字段没出现」）

    private static func jsonObject(_ raw: String) -> [String: Any]? {
        guard let data = raw.data(using: .utf8) else { return nil }
        guard let value = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) else {
            return nil
        }
        return value as? [String: Any]
    }

    private static func objectOrNil(_ root: [String: Any], _ key: String) -> [String: Any]? {
        root[key] as? [String: Any]
    }

    private static func arrayOrNil(_ root: [String: Any], _ key: String) -> [Any]? {
        root[key] as? [Any]
    }

    /// 只认 JSON 字符串；数字/布尔/对象/null 都当作「字段没出现」。
    private static func textOrNil(_ obj: [String: Any], _ key: String) -> String? {
        obj[key] as? String
    }

    /// 只认「字符串数组」；不是数组时返回 nil（字段不动），数组里非字符串的元素被丢掉。
    private static func stringListOrNil(_ obj: [String: Any], _ key: String) -> [String]? {
        guard let array = obj[key] as? [Any] else { return nil }
        return array.compactMap { $0 as? String }
    }

    private static func isJSONBoolean(_ value: NSNumber) -> Bool {
        CFGetTypeID(value) == CFBooleanGetTypeID()
    }
}
