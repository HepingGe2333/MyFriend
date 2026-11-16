import Foundation

// ---------------------------------------------------------------------------
// 人格进化（`docs/design/persona-evolution.md`，0.13）。
//
// 目标：人格会随对话**缓慢**变化，但**不失控、不 OOC、不偷偷改用户写的内容**。
//
// 这条链路分三段，每一段都能单独测：
// 1. **评估**（`PersonaEvolutionService`）：每 20 条用户消息或每 24 小时调一次 LLM，
//    输入是当前人格的四个字段 + 最近的对话摘要，输出 JSON `{changes:[…]}`；
// 2. **校验**（本文件）：上限（≤3 条、增≤2 删≤1）、字段白名单（只有 traits/likes/
//    behaviors/quirks）、置信度 ≥0.6、同类去重、单条 ≤40 字——**不合法的一律丢掉**；
// 3. **落库与人审**：写 `persona_pending_patches`，用户在人格页「他最近的变化」里
//    接受/拒绝；接受才写回人格并把 `version+1`，7 天没人管自动过期。
//
// 为什么把校验写成纯函数：模型输出是**外部数据**（不可信），把「什么算合法」写在
// 一个不碰数据库、不碰网络的类型里，测试才能穷举坏输入。
// ---------------------------------------------------------------------------

public enum PersonaEvolution {

    // MARK: - 触发节奏

    /// 每多少条用户消息评估一次。
    public static let triggerUserMessages = 20
    /// 或者每隔多久（毫秒）评估一次。
    public static let triggerIntervalMillis: Int64 = 24 * 60 * 60 * 1000
    /// 评估时带最近多少条对话（只带最近的一段，不带原始长文本）。
    public static let recentMessageLimit = 40

    // MARK: - 单次评估的上限（规范 §2）

    /// 一次最多几条变化。
    public static let maxChanges = 3
    /// 一次最多新增几条。
    public static let maxAdds = 2
    /// 一次最多删除几条。
    public static let maxRemoves = 1
    /// 置信度低于它就不要（0.6）。
    public static let minConfidence: Double = 0.6
    /// 单条值的字数上限。
    public static let valueMaxChars = 40
    /// 待确认变化 7 天过期。
    public static let pendingTtlMillis: Int64 = 7 * 24 * 60 * 60 * 1000

    /// 允许变的字段（白名单）。**刻意不含** name/description/prompt/world/relationship：
    /// 那些是用户写的东西，模型不能借「进化」偷偷改。
    public static let allowedFields: [String] = ["traits", "likes", "behaviors", "quirks"]

    public enum Field: String, CaseIterable, Sendable {
        case traits
        case likes
        case behaviors
        case quirks

        /// 给人看的字段名。
        public var displayName: String {
            switch self {
            case .traits: return String(localized: "性格")
            case .likes: return String(localized: "喜欢的事")
            case .behaviors: return String(localized: "习惯")
            case .quirks: return String(localized: "个人设定")
            }
        }
    }

    public enum Operation: String, Sendable {
        case add
        case remove

        public var displayName: String {
            switch self {
            case .add: return String(localized: "多了")
            case .remove: return String(localized: "少了")
            }
        }
    }

    /// 一条通过校验的变化。
    public struct Change: Equatable, Sendable {
        public var field: Field
        public var operation: Operation
        public var value: String
        public var reason: String
        public var confidence: Double

        public init(field: Field, operation: Operation, value: String,
                    reason: String = "", confidence: Double = 1) {
            self.field = field
            self.operation = operation
            self.value = value
            self.reason = reason
            self.confidence = confidence
        }
    }

    /// 一次评估的结果（已经过校验，可能为空 = 「这次不改」）。
    public struct Evaluation: Equatable, Sendable {
        public var changes: [Change]

        public init(changes: [Change] = []) {
            self.changes = changes
        }

        public var isEmpty: Bool { changes.isEmpty }
    }

    // MARK: - 解析与校验

    /// 把模型输出解析成**已校验**的变化列表；整份坏掉时返回 nil（调用方当作「没评估成功」）。
    ///
    /// 容错点（模型经常这么写）：
    /// - `op` / `operation` / `action` 三种键名；
    /// - `confidence` 是数字或字符串；
    /// - JSON 前后有解释、包在代码块里（`PromptGuidanceJSON.object`）；
    /// - `changes` 直接是数组，或被包在 `{"changes":[…]}` 里。
    public static func parse(_ raw: String, persona: PersonaEntity) -> Evaluation? {
        guard let object = PromptGuidanceJSON.object(from: raw) else { return nil }
        let list: [[String: Any]]
        if let array = object["changes"] as? [[String: Any]] {
            list = array
        } else if let array = object["changes"] as? [Any] {
            list = array.compactMap { $0 as? [String: Any] }
        } else if object["field"] != nil {
            // 模型偶尔只输出一条（没有 changes 包壳）：当成只有一条。
            list = [object]
        } else {
            list = []
        }
        return Evaluation(changes: sanitize(list, persona: persona))
    }

    /// 校验：白名单 → 置信度 → 单条长度 → 去重 → 条数上限（增 2 / 删 1 / 总 3）。
    ///
    /// 顺序是有讲究的：先丢掉不合法的，**再**数条数。否则模型给出 3 条低置信度 + 1 条合法的，
    /// 合法的会被上限挤掉（它本该被保留）。
    public static func sanitize(_ rawChanges: [[String: Any]], persona: PersonaEntity) -> [Change] {
        var candidates: [Change] = []
        for raw in rawChanges {
            guard let fieldRaw = (raw["field"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
                  let field = Field(rawValue: fieldRaw) else { continue }
            let opRaw = ((raw["op"] as? String) ?? (raw["operation"] as? String) ?? (raw["action"] as? String) ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            // 容忍 "ADD" / "add_value" / "删除" 这类写法。
            let operation: Operation
            if opRaw.hasPrefix("add") || opRaw == "增" { operation = .add }
            else if opRaw.hasPrefix("remove") || opRaw.hasPrefix("delete") || opRaw == "删" { operation = .remove }
            else { continue }
            guard let valueRaw = (raw["value"] as? String) ?? (raw["content"] as? String) else { continue }
            let value = String(
                valueRaw.trimmingCharacters(in: .whitespacesAndNewlines).prefix(valueMaxChars)
            )
            guard !value.isEmpty else { continue }
            let confidence = number(raw["confidence"]) ?? 1
            guard confidence >= minConfidence else { continue }
            let reason = String(((raw["reason"] as? String) ?? "").prefix(200))
                .trimmingCharacters(in: .whitespacesAndNewlines)
            candidates.append(Change(
                field: field, operation: operation, value: value,
                reason: reason, confidence: confidence
            ))
        }

        var adds: [Change] = []
        var removes: [Change] = []
        var seen = Set<String>()
        for change in candidates {
            // 去重键：字段 + 动作 + 值（忽略大小写与首尾空白）。同一件事说两遍只算一次。
            let key = change.field.rawValue + "|" + change.operation.rawValue + "|"
                + change.value.lowercased()
            if seen.contains(key) { continue }
            // 和现状比对：加一个已经有的、删一个不存在的，都不算变化。
            let existing = existingValues(of: change.field, in: persona)
            let alreadyThere = existing.contains {
                $0.lowercased() == change.value.lowercased()
            }
            switch change.operation {
            case .add where alreadyThere: continue
            case .remove where !alreadyThere: continue
            default: break
            }
            seen.insert(key)
            switch change.operation {
            case .add:
                if adds.count < maxAdds { adds.append(change) }
            case .remove:
                if removes.count < maxRemoves { removes.append(change) }
            }
        }
        // 总数上限：先加后删（「他最近变得…」通常比「他不再…」更常见，也更容易被接受）。
        return Array((adds + removes).prefix(maxChanges))
    }

    private static func number(_ value: Any?) -> Double? {
        if let number = value as? NSNumber { return number.doubleValue }
        if let text = value as? String { return Double(text.trimmingCharacters(in: .whitespaces)) }
        return nil
    }

    /// 一个字段当前的值（quirks 按「；/换行」拆成句子，好和 add/remove 对齐）。
    public static func existingValues(of field: Field, in persona: PersonaEntity) -> [String] {
        switch field {
        case .traits: return persona.traits
        case .likes: return persona.likes
        case .behaviors: return persona.behaviors
        case .quirks:
            return (persona.quirks ?? "")
                .split(whereSeparator: { "；;\n".contains($0) })
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
        }
    }

    // MARK: - 落库形状

    /// changes → `persona_pending_patches.patchJson`。
    ///
    /// 形状是 `{"changes":[…]}`（与 Android `PersonaEvolution.encodePatch` 一致，
    /// 两端的同一张表里放的是同一种 JSON）；每条带 confidence（Android 会忽略它，
    /// iOS 接受回放时用不到，但保留便于排查「模型当时有多确定」）。
    /// 字段顺序固定、confidence 保留三位小数：同一份变化一定得到同一串
    ///（`PersonaPendingPatchStore.insertIfNew` 靠它去重）。
    public static func encodePatch(_ changes: [Change]) -> String {
        let objects: [[String: Any]] = changes.map { change in
            [
                "field": change.field.rawValue,
                "op": change.operation.rawValue,
                "value": change.value,
                "reason": change.reason,
                "confidence": (change.confidence * 1000).rounded() / 1000
            ]
        }
        guard JSONSerialization.isValidJSONObject(objects),
              let data = try? JSONSerialization.data(withJSONObject: ["changes": objects], options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return #"{"changes":[]}"#
        }
        return text
    }

    /// `patchJson` → changes（坏 JSON 返回空数组，绝不抛）。
    ///
    /// 两种形状都认：`{"changes":[…]}`（本端与 Android 写库的形状）与裸数组
    /// （早期/手写数据）。认不出就是「没有变化」，不会把坏 JSON 当天量变化应用。
    public static func decodePatch(_ json: String) -> [Change] {
        guard let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) else {
            return []
        }
        let array: [[String: Any]]
        if let list = root as? [[String: Any]] {
            array = list
        } else if let object = root as? [String: Any],
                  let list = object["changes"] as? [[String: Any]] {
            array = list
        } else {
            return []
        }
        return array.compactMap { raw in
            guard let fieldRaw = raw["field"] as? String, let field = Field(rawValue: fieldRaw),
                  let opRaw = raw["op"] as? String, let operation = Operation(rawValue: opRaw),
                  let value = raw["value"] as? String, !value.isEmpty else { return nil }
            return Change(
                field: field, operation: operation, value: value,
                reason: (raw["reason"] as? String) ?? "",
                confidence: number(raw["confidence"]) ?? 1
            )
        }
    }

    /// 给用户看的一行：「性格多了「更耐心」」。
    public static func summaryLine(_ change: Change) -> String {
        switch change.operation {
        case .add: return String(format: String(localized: "%@多了「%@」"), change.field.displayName, change.value)
        case .remove: return String(format: String(localized: "%@少了「%@」"), change.field.displayName, change.value)
        }
    }

    // MARK: - 写回人格

    /// 把 changes 写进人格的四个字段；返回新人格与实际生效的条数。
    ///
    /// **不碰** name / description / prompt / personaCardJson / world / 关系：
    /// 白名单在 `sanitize` 里已经卡过，这里再按字段分支一次（两道防线，避免以后有人
    /// 给 `Field` 加成员却忘了这里）。
    public static func apply(
        _ changes: [Change],
        to persona: PersonaEntity,
        now: Int64
    ) -> (persona: PersonaEntity, applied: Int) {
        var updated = persona
        var applied = 0
        for change in changes {
            switch change.field {
            case .traits:
                if applyToList(&updated.traits, change) { applied += 1 }
            case .likes:
                if applyToList(&updated.likes, change) { applied += 1 }
            case .behaviors:
                if applyToList(&updated.behaviors, change) { applied += 1 }
            case .quirks:
                if applyToQuirks(&updated.quirks, change) { applied += 1 }
            }
        }
        guard applied > 0 else { return (persona, 0) }
        updated.updatedAt = now
        // 接受即 version+1（`docs/design/persona-evolution.md` §2）。
        updated.version = max(updated.version, 1) + 1
        return (updated, applied)
    }

    private static func applyToList(_ list: inout [String], _ change: Change) -> Bool {
        let lowered = change.value.lowercased()
        switch change.operation {
        case .add:
            guard !list.contains(where: { $0.lowercased() == lowered }) else { return false }
            list.append(change.value)
            return true
        case .remove:
            guard list.contains(where: { $0.lowercased() == lowered }) else { return false }
            list.removeAll { $0.lowercased() == lowered }
            return true
        }
    }

    private static func applyToQuirks(_ quirks: inout String?, _ change: Change) -> Bool {
        var lines = existingValues(of: .quirks, in: PersonaEntity(
            id: "", name: "", quirks: quirks
        ))
        let lowered = change.value.lowercased()
        switch change.operation {
        case .add:
            guard !lines.contains(where: { $0.lowercased() == lowered }) else { return false }
            lines.append(change.value)
        case .remove:
            guard lines.contains(where: { $0.lowercased() == lowered }) else { return false }
            lines.removeAll { $0.lowercased() == lowered }
        }
        let joined = lines.joined(separator: "；")
        quirks = joined.isEmpty ? nil : String(joined.prefix(PersonaFieldLimits.quirksMax))
        return true
    }

    // MARK: - 评估提示词

    /// 评估用的提示词（system + user）。
    ///
    /// 输入只要两样：当前人格那四个字段、最近一段对话的摘要。**不带**原始长文本、
    /// 不带用户写的整段 prompt（避免模型顺手重写它）。
    public static func messages(
        persona: PersonaEntity,
        recentDialogue: String,
        earlierSummary: String?
    ) -> [CompletionMessage] {
        let system = """
        你是人格进化的评估器。你看的是「他」最近和对方的对话，判断这个人格该不该有**很慢很小**的变化。
        只输出 JSON 对象，不要解释、不要 markdown：
        {"changes":[{"field":"traits|likes|behaviors|quirks","op":"add|remove","value":"≤40字","reason":"一句话","confidence":0.0-1.0}]}
        规则：
        - 最多 3 条，其中 add 最多 2 条、remove 最多 1 条；没有值得改的就输出 {"changes":[]}；
        - 只允许改 traits（性格）、likes（喜欢的事）、behaviors（习惯）、quirks（个人设定）；
          绝对不要改名字、简介、整段提示词、世界设定或关系；
        - confidence 低于 0.6 的不要输出；拿不准就不要写；
        - 不因为一次争吵就删掉「温柔」这种核心性格；不把对方随口一句评价变成 traits；
        - 不要重复人格里已经有的内容，同类变化不要写两遍；
        - value 写成一句能直接放进人格卡的话，不要带引号、不要带解释。
        """
        var user = "## 他现在的样子\n"
        user += "- 性格：" + (persona.traits.isEmpty ? "（没写）" : persona.traits.joined(separator: "、")) + "\n"
        user += "- 喜欢的事：" + (persona.likes.isEmpty ? "（没写）" : persona.likes.joined(separator: "、")) + "\n"
        user += "- 习惯：" + (persona.behaviors.isEmpty ? "（没写）" : persona.behaviors.joined(separator: "、")) + "\n"
        user += "- 个人设定：" + ((persona.quirks ?? "").isEmpty ? "（没写）" : (persona.quirks ?? "")) + "\n"
        let summary = (earlierSummary ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !summary.isEmpty {
            user += "\n## 更早之前聊过什么（摘要）\n" + String(summary.prefix(1200)) + "\n"
        }
        let dialogue = recentDialogue.trimmingCharacters(in: .whitespacesAndNewlines)
        user += "\n## 最近的对话\n" + (dialogue.isEmpty ? "（最近没聊什么）" : String(dialogue.prefix(4000)))
        user += "\n\n请判断：他该不该有新的小变化？只输出 JSON。"
        return [.system(system), .user(user)]
    }
}
