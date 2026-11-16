import Foundation

// ---------------------------------------------------------------------------
// 世界预设的**编辑器状态**（`spec/persona-guidance/README.md` §2.3）。
//
// 规范要求世界设定必须是可视化编辑器（不是 JSON 文本框）：日程与关系是行式列表，
// 义务是一行一条，其他背景（notes）是多行文本。这个文件只装「状态 + 增删改 + 校验」，
// 不 import SwiftUI、不读资源、不读时钟——所以它能被 `swift test` 逐条驱动
// （加一行、移一行、写坏时间、跨午夜、亲密度越界……），而界面只负责画。
//
// 与 Android `domain/WorldPresetEditor.kt` 一一对应（同一个 `WorldEditorState` 语义、
// 同一套校验项）：两端的行为差异只允许出现在「画」这一层。
//
// 出错时**不静默丢行**：坏行留在列表里、`issues()` 指出来（界面定位到具体行），
// 用户自己改或删。把写了一半的日程悄悄扔掉，比报错更糟。
// ---------------------------------------------------------------------------

/// 编辑器校验项：只描述"哪一段的第几行错了"，文案由界面本地化。
public struct WorldEditorIssue: Equatable, Sendable {

    public enum Field: String, Sendable {
        case scheduleDay
        case scheduleStart
        case scheduleEnd
        case scheduleActivity
        case relationshipName
        case relationshipRelation
        case relationshipIntimacy
        case obligation
        case notes
    }

    public var field: Field
    /// 第几行（0 起）；`notes` 这类整体字段用 0。
    public var index: Int

    public init(field: Field, index: Int) {
        self.field = field
        self.index = index
    }
}

/// 编辑器状态：界面持有它，每个动作返回下一份状态（纯函数，方便单测）。
public struct WorldEditorState: Equatable, Sendable {

    /// 日程的 `day` 下拉选项（规范 §2.1 允许的取值，顺序就是下拉顺序）。
    public static let dayOptions = [
        "每天", "工作日", "周末",
        "周一", "周二", "周三", "周四", "周五", "周六", "周日"
    ]

    /// 关系的 `relation` 下拉选项；最后一档是「自定义」（用户直接写别的词）。
    public static let relationOptions = ["家人", "朋友", "同事", "上级", "同学", "其他"]

    /// 义务的条数上限（与 `WorldPreset.maxObligations` 一致）。
    public static let maxObligations = WorldPreset.maxObligations

    public var worldName: String
    public var era: String
    public var description: String
    public var schedule: [WorldScheduleItem]
    public var relationships: [WorldRelationship]
    public var obligations: [String]
    public var notes: String

    public init(
        worldName: String = "",
        era: String = "",
        description: String = "",
        schedule: [WorldScheduleItem] = [],
        relationships: [WorldRelationship] = [],
        obligations: [String] = [],
        notes: String = ""
    ) {
        self.worldName = worldName
        self.era = era
        self.description = description
        self.schedule = schedule
        self.relationships = relationships
        self.obligations = obligations
        // 输入时就截断：界面不该让用户看到一个永远写不进去的第 1201 个字。
        self.notes = String(notes.prefix(WorldPreset.maxNotesChars))
    }

    /// 从已保存的世界预设读出来（坏 JSON 传 nil 就得到空状态）。
    public init(preset: WorldPreset?) {
        self.init(
            worldName: preset?.worldName ?? "",
            era: preset?.era ?? "",
            description: preset?.description ?? "",
            schedule: preset?.schedule ?? [],
            relationships: preset?.relationships ?? [],
            obligations: preset?.obligations ?? [],
            notes: preset?.notes ?? ""
        )
    }

    /// 空 = 没有世界预设（界面据此显示示例空状态、清空按钮）。
    public var isEmpty: Bool {
        worldName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && era.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && schedule.isEmpty && relationships.isEmpty && obligations.isEmpty
            && notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// 落库形状（超出上限的字段由 `WorldPreset.init` 收敛）。
    public var preset: WorldPreset {
        WorldPreset(
            worldName: worldName,
            era: era,
            description: description,
            schedule: schedule,
            relationships: relationships,
            obligations: obligations,
            notes: notes
        )
    }

    // MARK: - 日程

    /// 加一行：默认「工作日 09:00-18:00」，用户改哪一项都行。
    public func addSchedule(_ entry: WorldScheduleItem = WorldScheduleItem(
        day: "工作日", start: "09:00", end: "18:00", activity: ""
    )) -> WorldEditorState {
        var copy = self
        copy.schedule.append(entry)
        return copy
    }

    public func updateSchedule(_ index: Int,
                               _ transform: (WorldScheduleItem) -> WorldScheduleItem) -> WorldEditorState {
        var copy = self
        guard copy.schedule.indices.contains(index) else { return self }
        copy.schedule[index] = transform(copy.schedule[index])
        return copy
    }

    public func removeSchedule(_ index: Int) -> WorldEditorState {
        var copy = self
        guard copy.schedule.indices.contains(index) else { return self }
        copy.schedule.remove(at: index)
        return copy
    }

    /// 上下移动（`delta = -1` 上移、`+1` 下移）；到头了就原样返回，不抛异常。
    public func moveSchedule(_ index: Int, delta: Int) -> WorldEditorState {
        var copy = self
        guard copy.schedule.indices.contains(index) else { return self }
        let target = index + delta
        guard copy.schedule.indices.contains(target) else { return self }
        copy.schedule.swapAt(index, target)
        return copy
    }

    // MARK: - 人际关系

    public func addRelationship(_ entry: WorldRelationship = WorldRelationship()) -> WorldEditorState {
        var copy = self
        copy.relationships.append(entry)
        return copy
    }

    public func updateRelationship(
        _ index: Int,
        _ transform: (WorldRelationship) -> WorldRelationship
    ) -> WorldEditorState {
        var copy = self
        guard copy.relationships.indices.contains(index) else { return self }
        copy.relationships[index] = transform(copy.relationships[index])
        return copy
    }

    public func removeRelationship(_ index: Int) -> WorldEditorState {
        var copy = self
        guard copy.relationships.indices.contains(index) else { return self }
        copy.relationships.remove(at: index)
        return copy
    }

    public func moveRelationship(_ index: Int, delta: Int) -> WorldEditorState {
        var copy = self
        guard copy.relationships.indices.contains(index) else { return self }
        let target = index + delta
        guard copy.relationships.indices.contains(target) else { return self }
        copy.relationships.swapAt(index, target)
        return copy
    }

    // MARK: - 义务

    public func addObligation(_ text: String = "") -> WorldEditorState {
        var copy = self
        guard copy.obligations.count < Self.maxObligations else { return self }
        copy.obligations.append(text)
        return copy
    }

    public func updateObligation(_ index: Int, _ text: String) -> WorldEditorState {
        var copy = self
        guard copy.obligations.indices.contains(index) else { return self }
        copy.obligations[index] = text
        return copy
    }

    public func removeObligation(_ index: Int) -> WorldEditorState {
        var copy = self
        guard copy.obligations.indices.contains(index) else { return self }
        copy.obligations.remove(at: index)
        return copy
    }

    // MARK: - 其他背景

    public func withNotes(_ text: String) -> WorldEditorState {
        var copy = self
        copy.notes = String(text.prefix(WorldPreset.maxNotesChars))
        return copy
    }

    public var notesCount: Int { notes.count }
    public var notesLimit: Int { WorldPreset.maxNotesChars }
    public var notesTooLong: Bool { notes.count > WorldPreset.maxNotesChars }
    /// 超长时被截掉了多少个字（界面提示"超出 N 字已截断"）。
    public var notesTruncatedBy: Int { max(notes.count - WorldPreset.maxNotesChars, 0) }

    // MARK: - 跨午夜

    /// 这一行是不是跨过了午夜（`start > end` 视为第二天）。
    ///
    /// 规范 §2.1 明确允许（`22:00-02:00` 是「到次日凌晨两点」），界面上显示「次日」。
    public static func isCrossMidnight(start: String, end: String) -> Bool {
        guard let from = WorldScheduleItem.parseMinutes(start),
              let to = WorldScheduleItem.parseMinutes(end) else { return false }
        return from > to
    }

    /// 结束后落在第二天几点（跨午夜时给界面显示「次日 02:00」）。
    public static func nextDayEndText(end: String) -> String {
        let trimmed = end.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "" : "次日 " + trimmed
    }

    // MARK: - 校验

    /// 保存前的结构校验：每条都指到"第几行"。
    ///
    /// 只校验用户真的写错的东西：空行（还没填）算错，是因为空日程/空关系没有意义，
    /// 保存下来只会渲染出一行空白；要么填完，要么删掉。
    public func issues() -> [WorldEditorIssue] {
        var result: [WorldEditorIssue] = []
        for (index, entry) in schedule.enumerated() {
            let day = entry.day.trimmingCharacters(in: .whitespacesAndNewlines)
            if !day.isEmpty, !Self.dayOptions.contains(day) {
                result.append(WorldEditorIssue(field: .scheduleDay, index: index))
            }
            if WorldScheduleItem.parseMinutes(entry.start) == nil {
                result.append(WorldEditorIssue(field: .scheduleStart, index: index))
            }
            if WorldScheduleItem.parseMinutes(entry.end) == nil {
                result.append(WorldEditorIssue(field: .scheduleEnd, index: index))
            }
            if entry.activity.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                result.append(WorldEditorIssue(field: .scheduleActivity, index: index))
            }
        }
        for (index, person) in relationships.enumerated() {
            if person.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                result.append(WorldEditorIssue(field: .relationshipName, index: index))
            }
            if person.relation.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                result.append(WorldEditorIssue(field: .relationshipRelation, index: index))
            }
            if let intimacy = person.intimacy, !(1...5).contains(intimacy) {
                result.append(WorldEditorIssue(field: .relationshipIntimacy, index: index))
            }
        }
        for (index, obligation) in obligations.enumerated()
        where obligation.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            result.append(WorldEditorIssue(field: .obligation, index: index))
        }
        if notesTooLong {
            result.append(WorldEditorIssue(field: .notes, index: 0))
        }
        return result
    }

    /// 能保存吗：没有校验项（空世界也算能保存——那就是"不要世界"）。
    public var canSave: Bool { issues().isEmpty }

    /// AI 补全只填**空白项**（规范 §2.3：用户改过的字段不被覆盖）。
    ///
    /// 判断"空白"按结构而不是按字符串：日程/关系/义务是"列表空"，其余是"trim 后为空"。
    public func mergingBlanks(from incoming: WorldPreset) -> WorldEditorState {
        var merged = self
        if merged.worldName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            merged.worldName = incoming.worldName
        }
        if merged.era.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            merged.era = incoming.era
        }
        if merged.description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            merged.description = incoming.description
        }
        if merged.schedule.isEmpty { merged.schedule = incoming.schedule }
        if merged.relationships.isEmpty { merged.relationships = incoming.relationships }
        if merged.obligations.isEmpty { merged.obligations = incoming.obligations }
        if merged.notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            merged.notes = incoming.notes
        }
        return merged
    }

}

// MARK: - 示例（空状态用）

/// 空状态里的示例世界：内容来自 `spec/persona-guidance/cases/world-preset-linshen.json`
/// 的 `expect`（实测样本），两端用的是同一份"长什么样"。
///
/// 为什么把样本抄进源码：App 里没有 `spec/` 目录，而"空状态给示例"是规范 §2.3 的硬要求。
/// 抄一份的代价是可能漂——所以 `WorldPresetTests` 有一条测试把这里和 spec 文件逐字段比对。
public enum WorldPresetSample {

    /// 空状态卡片上的那句引导（界面用本地化文案，这里只给数据）。
    public static let linshen = WorldPreset(
        worldName: "临港新城·云筑建筑事务所",
        era: "当代，中国沿海新一线城市，2026 年",
        description: "林深所在的城市正经历大规模住宅开发，事务所竞争激烈、甲方频繁改图。"
            + "他带领六人小组负责一个高层住宅项目，正处于方案深化到施工图的关键阶段，加班是常态。"
            + "他租住在事务所附近的老小区，母亲住在同城另一端，父亲早年去世，母子关系亲近但见面不多。",
        schedule: [
            WorldScheduleItem(day: "工作日", start: "09:00", end: "12:00",
                              activity: "主持项目组晨会，分配楼栋深化任务"),
            WorldScheduleItem(day: "工作日", start: "13:00", end: "18:30",
                              activity: "画图、审图，与结构、机电对接"),
            WorldScheduleItem(day: "工作日", start: "19:30", end: "22:30",
                              activity: "赶项目节点，改图并准备次日汇报"),
            WorldScheduleItem(day: "周三", start: "20:00", end: "20:30", activity: "接母亲电话"),
            WorldScheduleItem(day: "周末", start: "10:00", end: "16:00",
                              activity: "视进度加班或补觉")
        ],
        relationships: [
            WorldRelationship(name: "母亲", relation: "母亲",
                              note: "每周三晚八点准时打电话，总问他有没有好好吃饭"),
            WorldRelationship(name: "老周", relation: "事务所合伙人",
                              note: "把控节点，催图直接但认可他的能力")
        ],
        obligations: [
            "按甲方节点提交方案深化图与施工图",
            "协调建筑、结构、机电各专业",
            "每周三晚接母亲电话"
        ]
    )
}
