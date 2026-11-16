import CryptoKit
import Foundation

// ---------------------------------------------------------------------------
// 人格提示词的翻译缓存（`spec/persona-translation/README.md`）。
//
// 为什么需要它：人设、世界、话风常常是用户用中文写的，而 App 可能跑在英文界面。
// 把中文设定直接塞进英文系统提示词，模型会在两种语言之间摇摆；所以「开始人格之前」
// 先把这些**设定文本**翻成当前界面语言，缓存起来复用。翻译失败就用原文（不阻塞聊天）。
//
// 这个文件只有三件事：
// 1. **源**（`PersonaTranslatableSource`）：从人格里取出"要翻译的字段"——只取人设/世界/话风，
//    消息正文、记忆正文、常用语（phrases）**永远不翻译**（§3：翻译会毁掉语感）；
// 2. **缓存形状**（`PersonaTranslation`）：lang / translatedAt / sourceHash / persona / world / speech；
// 3. **失效判定**：`sourceHash` 是"被翻译字段组成的**规范化 JSON**（§4）的 SHA-256"，
//    任一源字段变化 → 缓存失效。
//
// 隐私（§6）：这个类型的 `Completable` 载荷只含用户自己写的设定；它不碰 messages、
// 不碰 memories、不碰 apiKey。日志里也只出现"多少字/成功/失败"，不出现任何正文。
// ---------------------------------------------------------------------------

/// 语言判定与规范化（§5：源字段中 CJK 占比 > 0.2 视为中文；目标语言取 App 语言）。
public enum PersonaTranslationLanguage {

    /// App 当前界面语言的 BCP-47 短码（`zh` / `en`）。
    ///
    /// 与 App 层 `AppLanguage.current` 同一个判定口径（`Bundle.main.preferredLocalizations`
    /// 优先、没有 bundle 时退回 `Locale`）。Kit 不 import 资源、也不读 `Localizable.strings`：
    /// 这里只回答"是哪种语言"，翻译出来的**文本语言**由模型决定。
    public static func current() -> String {
        if let code = Bundle.main.preferredLocalizations.first?.lowercased() {
            if code.hasPrefix("en") { return "en" }
            if code.hasPrefix("zh") { return "zh" }
            return normalize(code)
        }
        let code = Locale.current.language.languageCode?.identifier.lowercased() ?? ""
        return normalize(code)
    }

    /// 把 `zh-Hans` / `zh_CN` / `en-US` 这类 tag 收成比较用的短码。
    public static func normalize(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let head = trimmed.split(whereSeparator: { $0 == "-" || $0 == "_" }).first.map(String.init) ?? ""
        return head.isEmpty ? trimmed : head
    }

    /// 一段文本"看起来是哪种语言"：CJK 字符占比 > 0.2 算中文，否则算英文。
    ///
    /// 空文本返回空串（未知）：没有内容就没有"源语言"这回事。
    public static func detect(_ text: String) -> String {
        let scalars = text.unicodeScalars.filter { !$0.properties.isWhitespace }
        guard !scalars.isEmpty else { return "" }
        let cjk = scalars.filter { scalar in
            (0x4E00...0x9FFF).contains(scalar.value)      // 基本汉字
                || (0x3400...0x4DBF).contains(scalar.value) // 扩展 A
                || (0x3000...0x303F).contains(scalar.value) // 中文标点
        }.count
        return Double(cjk) / Double(scalars.count) > 0.2 ? "zh" : "en"
    }

    /// 源与目标是否同一种语言（同语言不翻译、零请求）。
    public static func isSame(_ lhs: String, _ rhs: String) -> Bool {
        let a = normalize(lhs)
        let b = normalize(rhs)
        guard !a.isEmpty, !b.isEmpty else { return false }
        return a == b
    }
}

// MARK: - 要翻译的字段

/// 人格里"属于设定、可以翻译"的那部分。
///
/// 刻意用值类型把字段**拷贝**出来：翻译请求只拿这一份拷贝，拿不到 `PersonaEntity`，
/// 也就拿不到消息、记忆或数据库句柄。
public struct PersonaTranslatableSource: Equatable, Sendable {

    public var name: String
    public var description: String
    public var traits: [String]
    public var likes: [String]
    public var behaviors: [String]
    public var backstory: String
    public var quirks: String
    /// 人格的自由文本提示词（规范 §3 的 `systemPrompt`，若存在）。
    public var systemPrompt: String
    /// 世界预设（逐字段翻译）。
    public var world: WorldPreset?
    /// 话风（tone / habits / avoid 翻译；`phrases` **不翻译**）。
    public var speech: SpeechStyle?

    public init(
        name: String = "",
        description: String = "",
        traits: [String] = [],
        likes: [String] = [],
        behaviors: [String] = [],
        backstory: String = "",
        quirks: String = "",
        systemPrompt: String = "",
        world: WorldPreset? = nil,
        speech: SpeechStyle? = nil
    ) {
        self.name = name
        self.description = description
        self.traits = traits
        self.likes = likes
        self.behaviors = behaviors
        self.backstory = backstory
        self.quirks = quirks
        self.systemPrompt = systemPrompt
        self.world = world
        self.speech = speech
    }

    /// 从人格读出来（坏 JSON 等于没有世界/话风，不抛异常）。
    public init(persona: PersonaEntity) {
        self.init(
            name: persona.name,
            description: persona.description,
            traits: persona.traits,
            likes: persona.likes,
            behaviors: persona.behaviors,
            backstory: persona.backstory ?? "",
            quirks: persona.quirks ?? "",
            systemPrompt: persona.prompt,
            world: WorldPreset.decode(persona.worldJson),
            speech: SpeechStyle.decode(persona.speechStyleJson)
        )
    }

    /// 有没有值得翻译的内容（全空就不发请求）。
    public var isEmpty: Bool {
        name.isEmpty && description.isEmpty && traits.isEmpty && likes.isEmpty
            && behaviors.isEmpty && backstory.isEmpty && quirks.isEmpty
            && systemPrompt.isEmpty && world == nil && speech == nil
    }

    /// 源文本拼接（语言检测用）。
    public var joinedText: String {
        var parts = [name, description, backstory, quirks, systemPrompt]
        parts += traits + likes + behaviors
        if let world {
            parts.append(world.worldName)
            parts.append(world.era)
            parts.append(world.description)
            parts.append(world.notes)
            parts += world.schedule.map(\.renderedLine)
            parts += world.relationships.map(\.renderedLine)
            parts += world.obligations
        }
        if let speech {
            parts.append(speech.tone)
            parts += speech.habits
            parts += speech.avoid
        }
        return parts.filter { !$0.isEmpty }.joined(separator: "\n")
    }
}

// MARK: - 缓存形状

/// 一份翻译缓存：存在 `personas.translationJson` + `translationLang`。
public struct PersonaTranslation: Equatable, Sendable {

    public var lang: String
    public var translatedAt: Int64
    /// `sha256:…`（被翻译字段组成对象的规范化 JSON 哈希，任一源字段变化即失效）。
    public var sourceHash: String
    public var persona: PersonaFields
    /// 世界预设在缓存里的形状（nil = 这个人没有世界设定）。
    public var world: WorldPreset?
    /// 话风的翻译（`phrases` 原样保留在人格里，不进缓存）。
    public var speech: SpeechStyle?

    /// 人设字段（与规范 §4 的 `persona` 对象一一对应）。
    public struct PersonaFields: Equatable, Sendable {
        public var name: String
        public var description: String
        public var traits: [String]
        public var likes: [String]
        public var behaviors: [String]
        public var backstory: String
        public var quirks: String
        public var systemPrompt: String

        public init(
            name: String = "", description: String = "", traits: [String] = [],
            likes: [String] = [], behaviors: [String] = [], backstory: String = "",
            quirks: String = "", systemPrompt: String = ""
        ) {
            self.name = name
            self.description = description
            self.traits = traits
            self.likes = likes
            self.behaviors = behaviors
            self.backstory = backstory
            self.quirks = quirks
            self.systemPrompt = systemPrompt
        }

        public var isEmpty: Bool {
            name.isEmpty && description.isEmpty && traits.isEmpty && likes.isEmpty
                && behaviors.isEmpty && backstory.isEmpty && quirks.isEmpty && systemPrompt.isEmpty
        }
    }

    public init(
        lang: String,
        translatedAt: Int64,
        sourceHash: String,
        persona: PersonaFields,
        world: WorldPreset? = nil,
        speech: SpeechStyle? = nil
    ) {
        self.lang = lang
        self.translatedAt = translatedAt
        self.sourceHash = sourceHash
        self.persona = persona
        self.world = world
        self.speech = speech
    }

    // MARK: 源哈希

    /// 被翻译字段组成对象的**规范化 JSON** 的 SHA-256（`sha256:<hex>`，§4 / §4.1）。
    ///
    /// 算法与形状由规范 §4.1 钉死（两端必须逐字节同算法，否则一端翻的缓存在另一端永远"失效"）：
    /// - 对象是 `{"persona":{…},"world":{…} 或 null,"speech":{…} 或 null}`；
    /// - `persona` 固定 8 键、`world` 固定 7 键、`speech` 固定 3 键，空串/空数组照写；
    /// - 值用源字段的原值，**这里不再 trim / 过滤**（宽容解析已做过归一，规范化阶段二次加工会跨端漂移）；
    /// - 键按字典序排序、无多余空白（`json_encode` 等价的紧凑形式），UTF-8 字节做 SHA-256；
    /// - 十六进制小写、`sha256:` 前缀；`phrases` 不参与（§3：它不被翻译）。
    ///
    /// 这里刻意**不用**"字段名=值 + 分隔符"的拼接：那种写法是两端各写一套的私有约定，
    /// 值里出现分隔符就可能撞车；规范化 JSON 是规范里写明的、可被另一端点字节复现的形状。
    public static func sourceHash(for source: PersonaTranslatableSource) -> String {
        let object = translatableObject(
            persona: PersonaFields(
                name: source.name,
                description: source.description,
                traits: source.traits,
                likes: source.likes,
                behaviors: source.behaviors,
                backstory: source.backstory,
                quirks: source.quirks,
                systemPrompt: source.systemPrompt
            ),
            world: source.world,
            speech: source.speech
        )
        // `.sortedKeys` + Foundation 的紧凑输出就是规范要的"键按字典序、无多余空白"
        // （键全是 ASCII，字典序即字节序）。`.withoutEscapingSlashes` 不能省：Foundation 默认把 `/`
        // 转义成 `\/`，而 Android（kotlinx）与 Python 都不转义——人设/notes 里出现 `和/或` 或 URL
        // 就会让两端对同一份人格算出两个哈希（规范 §4.1 第 5 条：非 ASCII 与 `/` 都不转义）。
        // 对象里只有 String / [String] / [String: Any] / NSNull，序列化不会失败；
        // `?? Data("{}".utf8)` 只是让函数保持全函数（绝不返回空哈希）。
        let options: JSONSerialization.WritingOptions = [.sortedKeys, .withoutEscapingSlashes]
        let data = (try? JSONSerialization.data(withJSONObject: object, options: options))
            ?? Data("{}".utf8)
        let digest = SHA256.hash(data: data)
        return "sha256:" + digest.map { String(format: "%02x", $0) }.joined()
    }

    /// 被翻译字段的三个子对象：`persona` / `world` / `speech`（规范 §4 的规范化对象）。
    ///
    /// `jsonString()` 与 `sourceHash(for:)` **共用**这一个构造函数：缓存的 JSON 与哈希输入
    /// 永远是同一份形状（只是值取译文还是源字段），以后加字段只改这里，两边不会走偏。
    ///
    /// - `persona` 永远 8 个键都在（空字符串/空数组照样写）；
    /// - `world` / `speech` 在"没有"或"全空"时写 JSON `null`：不省键，也不写一个空对象
    ///   （全空的 `WorldPreset` 与 `nil` 语义相同，哈希也必须相同）；
    /// - `world` 固定 7 键、`schedule` 项固定 4 键、`relationships` 项固定 3 键且
    ///   `intimacy` 只在非 nil 时写；`speech` 固定 `tone/habits/avoid`，
    ///   "全空"只看这三项（`phrases` 永不出现，§3 / §4.1）。
    private static func translatableObject(
        persona: PersonaFields,
        world: WorldPreset?,
        speech: SpeechStyle?
    ) -> [String: Any] {
        var object: [String: Any] = [
            "persona": [
                "name": persona.name,
                "description": persona.description,
                "traits": persona.traits,
                "likes": persona.likes,
                "behaviors": persona.behaviors,
                "backstory": persona.backstory,
                "quirks": persona.quirks,
                "systemPrompt": persona.systemPrompt
            ]
        ]
        if let world, !world.isEmpty {
            // 与 Android `WorldPresetCodec.encode` 逐键一致：即使某个字段为空也写出来
            // （不沿用 `WorldPreset.jsonString()` 的「空字符串省略」规则——那是人格列的写法，
            // 缓存与哈希是跨端协议，键集合必须固定）。
            object["world"] = [
                "worldName": world.worldName,
                "era": world.era,
                "description": world.description,
                "schedule": world.schedule.map { item in
                    ["day": item.day, "start": item.start, "end": item.end, "activity": item.activity]
                },
                "relationships": world.relationships.map { person -> [String: Any] in
                    var entry: [String: Any] = [
                        "name": person.name, "relation": person.relation, "note": person.note
                    ]
                    if let intimacy = person.intimacy { entry["intimacy"] = intimacy }
                    return entry
                },
                "obligations": world.obligations,
                "notes": world.notes
            ]
        } else {
            object["world"] = NSNull()
        }
        if let speech, Self.hasTranslatableSpeechFields(speech) {
            // `phrases` 不进翻译缓存（§3：翻译会毁掉语感），也不进哈希。
            // 「空话风」的判据同样只看 tone/habits/avoid：只有 phrases 的话风等于没有话风，
            // 否则改一句口头禅就会让 speech 在 null ↔ 空对象之间翻转、把整份译文作废。
            object["speech"] = [
                "tone": speech.tone,
                "habits": speech.habits,
                "avoid": speech.avoid
            ]
        } else {
            object["speech"] = NSNull()
        }
        return object
    }

    /// 话风里参与翻译/哈希的三项是否至少有一项非空（`phrases` 不算，§3）。
    ///
    /// 与 Android `SpeechStyle.translatableFieldsEmpty()` 同一条判据：`Phrases` 永不参与，
    /// 所以「只有常用语」的话风与「没有话风」必须同哈希、同缓存形状（`speech: null`）。
    private static func hasTranslatableSpeechFields(_ speech: SpeechStyle) -> Bool {
        !(speech.tone.isEmpty && speech.habits.isEmpty && speech.avoid.isEmpty)
    }

    // MARK: 缓存有效性

    /// 这份缓存还能用吗：语言一致 + 源哈希一致（源变化即失效）。
    public func isValid(for source: PersonaTranslatableSource, lang: String) -> Bool {
        PersonaTranslationLanguage.isSame(self.lang, lang)
            && sourceHash == Self.sourceHash(for: source)
    }

    // MARK: 解析 / 落库

    /// 从库里的 JSON 读出来；空 / 坏 JSON 返回 nil（等于没有缓存）。
    public static func decode(_ raw: String?) -> PersonaTranslation? {
        guard let object = PromptGuidanceJSON.object(from: raw) else { return nil }
        // 缓存里的字段也一律不截断：缓存是「同一份人格的另一种语言」，截断会让
        // 译文比原文短，`sourceHash` 对不上、每次启动都重译。
        let lang = PromptGuidanceJSON.string(object, "lang") ?? ""
        let hash = PromptGuidanceJSON.string(object, "sourceHash") ?? ""
        guard !lang.isEmpty, hash.hasPrefix("sha256:") else { return nil }
        let personaObject = object["persona"] as? [String: Any] ?? [:]
        let persona = PersonaFields(
            name: PromptGuidanceJSON.string(personaObject, "name") ?? "",
            description: PromptGuidanceJSON.string(personaObject, "description") ?? "",
            traits: PromptGuidanceJSON.stringList(personaObject, "traits"),
            likes: PromptGuidanceJSON.stringList(personaObject, "likes"),
            behaviors: PromptGuidanceJSON.stringList(personaObject, "behaviors"),
            backstory: PromptGuidanceJSON.string(personaObject, "backstory") ?? "",
            quirks: PromptGuidanceJSON.string(personaObject, "quirks") ?? "",
            systemPrompt: PromptGuidanceJSON.string(personaObject, "systemPrompt") ?? ""
        )
        let world = (object["world"] as? [String: Any]).map(WorldPreset.from(object:))
        let speech = (object["speech"] as? [String: Any]).map(SpeechStyle.from(object:))
        return PersonaTranslation(
            lang: lang,
            translatedAt: PromptGuidanceJSON.int(object, "translatedAt").map(Int64.init) ?? 0,
            sourceHash: hash,
            persona: persona,
            world: world,
            speech: speech
        )
    }

    /// 落库 JSON：键集合与 Android `PersonaTranslationCodec.encode` **逐键一致**。
    ///
    /// 没有世界 / 话风时写 `null` 而不是省键（两端读同一份缓存时不靠"缺键"猜语义）；
    /// 刻意没有 `schemaVersion`：缓存形状由两端的契约测试守，多一个只有 iOS 知道的键
    /// 就会让 Android 读到一份形状不同的 JSON。
    public func jsonString() -> String? {
        var object: [String: Any] = [
            "lang": lang,
            "translatedAt": translatedAt,
            "sourceHash": sourceHash
        ]
        // 三个子对象与 `sourceHash(for:)` 共用同一个构造函数：缓存 JSON 与哈希输入同形。
        object.merge(Self.translatableObject(persona: persona, world: world, speech: speech)) { _, new in new }
        guard JSONSerialization.isValidJSONObject(object),
              // 与 `sourceHash(for:)` 同一组选项：缓存 JSON 里的 `/` 也不能被转义成 `\/`，
              // 否则同一段缓存两端读起来字节不同（契约测试会红）。
              let data = try? JSONSerialization.data(
                  withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes]
              ),
              let text = String(data: data, encoding: .utf8) else { return nil }
        return text
    }

    // MARK: 逐字段回退合并

    /// 把模型输出**逐字段**并到原文上：返回的就是一份完整的翻译缓存。
    ///
    /// §7：坏 JSON / 缺字段 → **逐字段**回退原文，不是整份丢弃。
    /// 所以这个方法永远返回一份可用结果：拿到的字段用翻译，没拿到的字段用原文。
    public static func merge(
        source: PersonaTranslatableSource,
        lang: String,
        translatedAt: Int64,
        object: [String: Any]
    ) -> PersonaTranslation {
        let personaObject = object["persona"] as? [String: Any] ?? [:]
        let worldObject = object["world"] as? [String: Any]
        let speechObject = object["speech"] as? [String: Any]

        func pick(_ key: String, _ fallback: String) -> String {
            guard let value = PromptGuidanceJSON.string(personaObject, key),
                  !value.isEmpty else { return fallback }
            return value
        }
        func pickList(_ key: String, _ fallback: [String]) -> [String] {
            let values = PromptGuidanceJSON.stringList(personaObject, key)
            return values.isEmpty ? fallback : values
        }

        let persona = PersonaFields(
            name: pick("name", source.name),
            description: pick("description", source.description),
            traits: pickList("traits", source.traits),
            likes: pickList("likes", source.likes),
            behaviors: pickList("behaviors", source.behaviors),
            backstory: pick("backstory", source.backstory),
            quirks: pick("quirks", source.quirks),
            systemPrompt: pick("systemPrompt", source.systemPrompt)
        )

        let world = mergeWorld(source: source.world, object: worldObject)
        let speech = mergeSpeech(source: source.speech, object: speechObject)
        return PersonaTranslation(
            lang: lang,
            translatedAt: translatedAt,
            sourceHash: sourceHash(for: source),
            persona: persona,
            world: world,
            speech: speech
        )
    }

    /// 世界逐字段合并：模型没给/给坏的字段保留原文。
    static func mergeWorld(source: WorldPreset?, object: [String: Any]?) -> WorldPreset? {
        guard let source else { return nil }
        guard let object else { return source }
        // 日程 / 关系按行位置对齐：模型被要求"键名原样保留"，行数通常一致；
        // 行数不一致就整段回退原文（比按错位的行拼接要安全）。
        let schedule = PromptGuidanceJSON.objectList(object, "schedule")
        let mergedSchedule: [WorldScheduleItem]
        if schedule.count == source.schedule.count {
            mergedSchedule = source.schedule.enumerated().map { index, origin in
                let item = schedule[index]
                return WorldScheduleItem(
                    day: PromptGuidanceJSON.string(item, "day") ?? origin.day,
                    start: origin.start,
                    end: origin.end,
                    activity: PromptGuidanceJSON.string(item, "activity") ?? origin.activity
                )
            }
        } else {
            mergedSchedule = source.schedule
        }
        let relationships = PromptGuidanceJSON.objectList(object, "relationships")
        let mergedRelationships: [WorldRelationship]
        if relationships.count == source.relationships.count {
            mergedRelationships = source.relationships.enumerated().map { index, origin in
                let item = relationships[index]
                return WorldRelationship(
                    name: PromptGuidanceJSON.string(item, "name") ?? origin.name,
                    relation: PromptGuidanceJSON.string(item, "relation") ?? origin.relation,
                    note: PromptGuidanceJSON.string(item, "note") ?? origin.note,
                    intimacy: origin.intimacy
                )
            }
        } else {
            mergedRelationships = source.relationships
        }
        let obligations = PromptGuidanceJSON.stringList(object, "obligations")
        return WorldPreset(
            worldName: PromptGuidanceJSON.string(object, "worldName") ?? source.worldName,
            era: PromptGuidanceJSON.string(object, "era") ?? source.era,
            description: PromptGuidanceJSON.string(object, "description") ?? source.description,
            schedule: mergedSchedule,
            relationships: mergedRelationships,
            obligations: obligations.isEmpty ? source.obligations : obligations,
            notes: PromptGuidanceJSON.string(object, "notes") ?? source.notes
        )
    }

    /// 话风逐字段合并：`phrases` 永远用原文（不翻译、也不接受模型改动）。
    static func mergeSpeech(source: SpeechStyle?, object: [String: Any]?) -> SpeechStyle? {
        guard let source else { return nil }
        guard let object else { return source }
        let tone = PromptGuidanceJSON.string(object, "tone") ?? source.tone
        let habits = PromptGuidanceJSON.stringList(object, "habits")
        let avoid = PromptGuidanceJSON.stringList(object, "avoid")
        return SpeechStyle(
            tone: tone,
            phrases: source.phrases,
            habits: habits.isEmpty ? source.habits : habits,
            avoid: avoid.isEmpty ? source.avoid : avoid,
            source: source.source
        )
    }

    // MARK: 应用到人格（只改内存值，不写库）

    /// 缓存命中就用译文（同步、零网络，给提示词拼装用）。
    ///
    /// 这是 `PersonaTranslationService.appliedPersona` 的纯函数版本：聊天/主动消息的
    /// 拼装点只需要「有缓存就换」，不需要知道是谁在什么时候翻的。
    public static func applyingCached(to persona: PersonaEntity, lang: String) -> PersonaEntity {
        guard let cached = decode(persona.translationJson) else { return persona }
        let source = PersonaTranslatableSource(persona: persona)
        guard cached.isValid(for: source, lang: lang) else { return persona }
        return cached.applying(to: persona)
    }

    /// 把缓存叠回人格：名字/介绍/结构化字段/提示词/世界/话风都换成译文。
    ///
    /// 调用方（聊天提示词拼装）拿到的是一份**内存副本**：数据库里仍然是原文，
    /// 用户编辑页看到的也仍然是原文——译文只用于"这个人怎么跟模型说话"。
    public func applying(to persona: PersonaEntity) -> PersonaEntity {
        var updated = persona
        if !self.persona.name.isEmpty { updated.name = self.persona.name }
        if !self.persona.description.isEmpty { updated.description = self.persona.description }
        if !self.persona.backstory.isEmpty { updated.backstory = self.persona.backstory }
        if !self.persona.quirks.isEmpty { updated.quirks = self.persona.quirks }
        if !self.persona.traits.isEmpty { updated.traits = self.persona.traits }
        if !self.persona.likes.isEmpty { updated.likes = self.persona.likes }
        if !self.persona.behaviors.isEmpty { updated.behaviors = self.persona.behaviors }
        if !self.persona.systemPrompt.isEmpty { updated.prompt = self.persona.systemPrompt }
        if let world, !world.isEmpty { updated.worldJson = world.jsonString() }
        if let speech, !speech.isEmpty {
            // 常用语保留原文（§3）：译文的 tone/habits/avoid + 原文的 phrases。
            let original = SpeechStyle.decode(persona.speechStyleJson)
            var merged = speech
            merged.phrases = original?.phrases ?? []
            updated.speechStyleJson = merged.jsonString()
        }
        return updated
    }
}
