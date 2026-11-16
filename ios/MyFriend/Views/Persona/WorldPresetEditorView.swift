import SwiftUI

/// 世界设定的**可视化编辑器**（`spec/persona-guidance/README.md` §2.3）。
///
/// 规范明确说「不是 JSON 文本框」，所以这里是一条条能点能改的行：
/// - 日程：day 下拉 + 起止时间选择器 + 做什么；可增删、上下移；跨午夜显示「次日」；
/// - 人际关系：称呼 + 关系下拉（+ 自定义）+ 一句细节 + 可选亲密度；
/// - 义务：一行一条，可增删；
/// - 其他背景（notes）：多行文本 + 1200 字提示；
/// - 空状态给一份示例（`WorldPresetSample.linshen`，来自 spec 的实测样本）。
///
/// 状态与校验全在 Kit 的 `WorldEditorState`（`swift test` 能逐条驱动），
/// 这个文件只负责画和把点击转成 `state.xxx()`。
struct WorldPresetEditorView: View {
    @Binding var state: WorldEditorState
    let accent: Color
    /// 空状态是否显示示例（预设编辑页 / 人格编辑页都显示）。
    var showsSample = true

    @Environment(\.colorScheme) private var scheme
    /// 用户写超过 1200 字时提示一次（内容按上限截断，不悄悄丢也不无限涨）。
    @State private var notesOverflow = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            introFields
            scheduleSection
            relationshipSection
            obligationsSection
            notesSection
            if showsSample, state.isEmpty {
                sampleCard
            }
        }
    }

    // MARK: - 世界名 / 时代 / 处境

    private var introFields: some View {
        VStack(alignment: .leading, spacing: 10) {
            FieldCaption(L("世界 / 城市"), hint: L("一句话，例如「临港新城·云筑建筑事务所」。"))
            TextField(L("比如：临港新城·云筑建筑事务所"), text: $state.worldName)
                .textFieldStyle(.plain)
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)

            FieldCaption(L("时代与地点"), hint: L("例如「当代，中国沿海新一线城市，2026 年」。"))
            TextField(L("比如：当代，中国沿海新一线城市"), text: $state.era)
                .textFieldStyle(.plain)
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)

            FieldCaption(L("世界与他的处境"), hint: L("100–200 字：世界长什么样、他在里面是什么处境。"))
            multiline($state.description, height: 92,
                      placeholder: L("比如：事务所竞争激烈、甲方频繁改图，他带组赶一个高层住宅项目。"))
        }
    }

    // MARK: - 日程

    private var scheduleSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader(
                L("日程"),
                hint: L("他每天大概在做什么。可以加好几行，也能上下调整顺序。"),
                count: Lf("%d / %d 行", state.schedule.count, WorldPreset.maxScheduleItems)
            )
            if state.schedule.isEmpty {
                emptyHint(L("还没有日程。加一行，或者直接用下面的示例。"))
            }
            ForEach(state.schedule.indices, id: \.self) { index in
                scheduleRow(index)
            }
            addButton(
                title: L("加一行日程"),
                disabled: state.schedule.count >= WorldPreset.maxScheduleItems
            ) {
                state = state.addSchedule()
            }
        }
    }

    private func scheduleRow(_ index: Int) -> some View {
        let item = state.schedule[index]
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Menu {
                    ForEach(WorldEditorState.dayOptions, id: \.self) { option in
                        Button(option) {
                            state = state.updateSchedule(index) { row in
                                var next = row
                                next.day = option
                                return next
                            }
                        }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Text(item.day.isEmpty ? L("每天") : item.day)
                            .font(.footnote.weight(.medium))
                        Image(systemName: "chevron.down")
                            .font(.system(size: 9, weight: .semibold))
                    }
                    .foregroundStyle(accent)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(accent.opacity(0.12), in: Capsule())
                }

                Spacer(minLength: 4)

                Button {
                    state = state.moveSchedule(index, delta: -1)
                } label: {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 12, weight: .semibold))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .disabled(index == 0)
                .accessibilityLabel(Text(L("上移这一行")))

                Button {
                    state = state.moveSchedule(index, delta: 1)
                } label: {
                    Image(systemName: "arrow.down")
                        .font(.system(size: 12, weight: .semibold))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .disabled(index >= state.schedule.count - 1)
                .accessibilityLabel(Text(L("下移这一行")))

                Button {
                    state = state.removeSchedule(index)
                } label: {
                    Image(systemName: "minus.circle")
                        .font(.system(size: 15))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .accessibilityLabel(Text(L("删掉这行日程")))
            }

            HStack(spacing: 6) {
                DatePicker("", selection: timeBinding(index, end: false),
                           displayedComponents: .hourAndMinute)
                    .labelsHidden()
                Text("–")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                DatePicker("", selection: timeBinding(index, end: true),
                           displayedComponents: .hourAndMinute)
                    .labelsHidden()
                if WorldEditorState.isCrossMidnight(start: item.start, end: item.end) {
                    Text(L("次日"))
                        .font(.caption2)
                        .foregroundStyle(accent)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(accent.opacity(0.12), in: Capsule())
                }
                Spacer(minLength: 0)
            }

            TextField(L("在做什么，例如「在事务所画图」"), text: activityBinding(index))
                .textFieldStyle(.plain)
                .font(.footnote)
                .padding(.vertical, 7)
                .padding(.horizontal, 10)
                .background(fieldBackground)
        }
        .padding(10)
        .background(rowBackground)
    }

    // MARK: - 人际关系

    private var relationshipSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader(
                L("人际关系"),
                hint: L("他身边的人：谁会找他、他跟谁亲近。"),
                count: Lf("%d / %d 人", state.relationships.count, WorldPreset.maxRelationships)
            )
            if state.relationships.isEmpty {
                emptyHint(L("还没有写人。比如「母亲：每周三晚打电话」。"))
            }
            ForEach(state.relationships.indices, id: \.self) { index in
                relationshipRow(index)
            }
            addButton(
                title: L("加一个人"),
                disabled: state.relationships.count >= WorldPreset.maxRelationships
            ) {
                state = state.addRelationship()
            }
        }
    }

    private func relationshipRow(_ index: Int) -> some View {
        let person = state.relationships[index]
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                TextField(L("称呼，例如「母亲」"), text: relationshipText(index, \.name))
                    .textFieldStyle(.plain)
                    .font(.footnote)
                    .padding(.vertical, 7)
                    .padding(.horizontal, 10)
                    .background(fieldBackground)
                Spacer(minLength: 0)
                Button {
                    state = state.moveRelationship(index, delta: -1)
                } label: {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 12, weight: .semibold))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .disabled(index == 0)
                .accessibilityLabel(Text(L("上移这一行")))

                Button {
                    state = state.moveRelationship(index, delta: 1)
                } label: {
                    Image(systemName: "arrow.down")
                        .font(.system(size: 12, weight: .semibold))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .disabled(index >= state.relationships.count - 1)
                .accessibilityLabel(Text(L("下移这一行")))

                Button {
                    state = state.removeRelationship(index)
                } label: {
                    Image(systemName: "minus.circle")
                        .font(.system(size: 15))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .accessibilityLabel(Text(L("删掉这一行")))
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(WorldEditorState.relationOptions, id: \.self) { option in
                        Button {
                            state = state.updateRelationship(index) { row in
                                var next = row
                                next.relation = option
                                return next
                            }
                        } label: {
                            Text(option)
                                .font(.caption)
                                .foregroundStyle(person.relation == option ? AnyShapeStyle(accent) : AnyShapeStyle(Color.secondary))
                                .padding(.horizontal, 9)
                                .padding(.vertical, 4)
                                .background(
                                    Capsule().strokeBorder(
                                        person.relation == option
                                            ? accent.opacity(0.5)
                                            : Color.primary.opacity(0.12),
                                        lineWidth: 1
                                    )
                                )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.vertical, 1)
            }

            TextField(L("关系，也可以自己写（例如「事务所合伙人」）"),
                      text: relationshipText(index, \.relation))
                .textFieldStyle(.plain)
                .font(.footnote)
                .padding(.vertical, 7)
                .padding(.horizontal, 10)
                .background(fieldBackground)

            TextField(L("一句细节，例如「每周三晚打电话」"),
                      text: relationshipText(index, \.note))
                .textFieldStyle(.plain)
                .font(.footnote)
                .padding(.vertical, 7)
                .padding(.horizontal, 10)
                .background(fieldBackground)

            HStack(spacing: 8) {
                Toggle(isOn: intimacyEnabledBinding(index)) {
                    Text(L("亲密度"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .toggleStyle(.switch)
                .tint(accent)
                if let intimacy = person.intimacy {
                    Stepper(value: intimacyBinding(index), in: 1...5) {
                        Text(Lf("%d / 5", intimacy))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 0)
            }
        }
        .padding(10)
        .background(rowBackground)
    }

    // MARK: - 义务

    private var obligationsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader(
                L("要承担的事"),
                hint: L("他必须做的事与压力，一行一条。"),
                count: Lf("%d / %d 条", state.obligations.count, WorldEditorState.maxObligations)
            )
            ForEach(state.obligations.indices, id: \.self) { index in
                HStack(spacing: 8) {
                    TextField(L("例如「按节点交图」"), text: obligationBinding(index))
                        .textFieldStyle(.plain)
                        .font(.footnote)
                        .padding(.vertical, 7)
                        .padding(.horizontal, 10)
                        .background(fieldBackground)
                    Button {
                        state = state.removeObligation(index)
                    } label: {
                        Image(systemName: "minus.circle")
                            .font(.system(size: 15))
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(L("删掉这一条")))
                }
            }
            addButton(
                title: L("加一条"),
                disabled: state.obligations.count >= WorldEditorState.maxObligations
            ) {
                state = state.addObligation()
            }
        }
    }

    // MARK: - 其他背景（notes）

    private var notesSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                FieldCaption(
                    L("其他背景"),
                    hint: Lf("结构化字段写不下的都可以放这里：城市气质、行业黑话、家庭旧事、忌讳……最多 %d 字。",
                            WorldPreset.maxNotesChars)
                )
                Spacer(minLength: 6)
                Text(Lf("%d / %d 字", state.notesCount, state.notesLimit))
                    .font(.caption2)
                    .foregroundStyle(state.notesTooLong ? .orange : .secondary)
            }
            multiline(notesBinding, height: 110,
                      placeholder: L("比如：事务所里没人叫他全名，都叫「深哥」。"))
            if notesOverflow {
                Text(Lf("已经到 %d 字上限，多出来的部分没有保存。", WorldPreset.maxNotesChars))
                    .font(.caption2)
                    .foregroundStyle(.orange)
            }
        }
    }

    /// notes 的绑定：写入时按 1200 字截断（与落库同一上限），并对"写超了"给一句提示。
    private var notesBinding: Binding<String> {
        Binding(
            get: { state.notes },
            set: { value in
                if value.count > WorldPreset.maxNotesChars { notesOverflow = true }
                state = state.withNotes(value)
            }
        )
    }

    // MARK: - 空状态示例

    private var sampleCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("还没有世界设定"))
                .font(.footnote.weight(.medium))
            Text(L("世界是「他在哪儿生活、平时怎么过」。没有它也能聊，但有了之后他会更像一个过日子的人。"))
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Text(L("示例：临港新城·云筑建筑事务所"))
                .font(.caption)
                .foregroundStyle(accent)
            Text(WorldPresetSample.linshen.description)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(3)
                .fixedSize(horizontal: false, vertical: true)
            Button(L("用示例填一下")) {
                state = WorldEditorState(preset: WorldPresetSample.linshen)
            }
            .font(.footnote)
            .tint(accent)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(accent.opacity(0.06), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    // MARK: - 小部件

    private var fieldBackground: some View {
        RoundedRectangle(cornerRadius: 9, style: .continuous)
            .fill(Color.secondary.opacity(0.08))
    }

    private var rowBackground: some View {
        RoundedRectangle(cornerRadius: 10, style: .continuous)
            .fill(Color.secondary.opacity(0.05))
    }

    private func sectionHeader(_ title: String, hint: String, count: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(alignment: .firstTextBaseline) {
                Text(title)
                    .font(.system(.body, design: .serif))
                Spacer()
                Text(count)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Text(hint)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 6)
    }

    private func emptyHint(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(.secondary)
    }

    private func addButton(title: String, disabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: "plus")
                    .font(.system(size: 10, weight: .bold))
                Text(title)
                    .font(.footnote)
            }
            .foregroundStyle(disabled ? AnyShapeStyle(Color.secondary) : AnyShapeStyle(accent))
        }
        .buttonStyle(.plain)
        .disabled(disabled)
    }

    /// 多行输入框（带占位符）。
    private func multiline(_ text: Binding<String>, height: CGFloat, placeholder: String) -> some View {
        ZStack(alignment: .topLeading) {
            if text.wrappedValue.isEmpty {
                Text(placeholder)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 14)
            }
            TextEditor(text: text)
                .font(.footnote)
                .scrollContentBackground(.hidden)
                .frame(minHeight: height)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
        }
        .background(fieldBackground)
    }

    // MARK: - 绑定

    /// 「HH:mm」↔ `Date`：`DatePicker` 只认 `Date`，世界 JSON 里存的是文本。
    private func timeBinding(_ index: Int, end: Bool) -> Binding<Date> {
        Binding(
            get: {
                guard state.schedule.indices.contains(index) else { return Date() }
                let text = end ? state.schedule[index].end : state.schedule[index].start
                return Self.date(from: text)
            },
            set: { date in
                let text = Self.clockText(date)
                state = state.updateSchedule(index) { row in
                    var next = row
                    if end { next.end = text } else { next.start = text }
                    return next
                }
            }
        )
    }

    private func activityBinding(_ index: Int) -> Binding<String> {
        Binding(
            get: { state.schedule.indices.contains(index) ? state.schedule[index].activity : "" },
            set: { value in
                state = state.updateSchedule(index) { row in
                    var next = row
                    next.activity = value
                    return next
                }
            }
        )
    }

    private func relationshipText(
        _ index: Int,
        _ keyPath: WritableKeyPath<WorldRelationship, String>
    ) -> Binding<String> {
        Binding(
            get: {
                state.relationships.indices.contains(index)
                    ? state.relationships[index][keyPath: keyPath] : ""
            },
            set: { value in
                state = state.updateRelationship(index) { row in
                    var next = row
                    next[keyPath: keyPath] = value
                    return next
                }
            }
        )
    }

    private func intimacyEnabledBinding(_ index: Int) -> Binding<Bool> {
        Binding(
            get: {
                guard state.relationships.indices.contains(index) else { return false }
                return state.relationships[index].intimacy != nil
            },
            set: { enabled in
                state = state.updateRelationship(index) { row in
                    var next = row
                    next.intimacy = enabled ? (next.intimacy ?? 3) : nil
                    return next
                }
            }
        )
    }

    private func intimacyBinding(_ index: Int) -> Binding<Int> {
        Binding(
            get: {
                guard state.relationships.indices.contains(index) else { return 3 }
                return state.relationships[index].intimacy ?? 3
            },
            set: { value in
                state = state.updateRelationship(index) { row in
                    var next = row
                    next.intimacy = WorldRelationship.clampedIntimacy(value) ?? row.intimacy
                    return next
                }
            }
        )
    }

    private func obligationBinding(_ index: Int) -> Binding<String> {
        Binding(
            get: { state.obligations.indices.contains(index) ? state.obligations[index] : "" },
            set: { value in state = state.updateObligation(index, value) }
        )
    }

    static func date(from text: String) -> Date {
        let pieces = text.split(separator: ":")
        let hour = pieces.count == 2 ? Int(pieces[0]) ?? 9 : 9
        let minute = pieces.count == 2 ? Int(pieces[1]) ?? 0 : 0
        return Calendar.current.date(
            bySettingHour: min(max(hour, 0), 23),
            minute: min(max(minute, 0), 59),
            second: 0,
            of: Date()
        ) ?? Date()
    }

    static func clockText(_ date: Date) -> String {
        let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", parts.hour ?? 0, parts.minute ?? 0)
    }
}
