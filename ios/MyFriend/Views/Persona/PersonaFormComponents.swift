import SwiftUI

// ---------------------------------------------------------------------------
// 人格 / 预设编辑页共用的小部件。
//
// 从 `PersonaEditorScreen` 搬出来：预设编辑页（`PresetEditorScreen`）也要用同一套
// 标签编辑器、字段标题与流传标签；复制一份的话，两份会在「上限、提示、样式」上慢慢分叉。
// ---------------------------------------------------------------------------

/// 一个可多选的标签编辑器：点建议词加上/去掉，也可以自己写一个。
///
/// 为什么不用系统 `Menu` / `Picker`：这些是「多个并列的小词」，用一排可点的胶囊
/// 比展开一个列表更直观；用户还能看到「我现在一共有几个」。
///
/// 0.12：`limit` 只是**建议值**。超出时计数变成橙色提示，但「加上」照样可用 ——
/// 编辑器不替用户决定哪些内容不配存下来（存储与哈希保留原文）。
struct TagEditor: View {
    let title: String
    let hint: String
    let suggestions: [String]
    let limit: Int
    @Binding var values: [String]
    let accent: Color

    @State private var draft = ""
    @FocusState private var draftFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(title)
                    .font(.system(.body, design: .serif))
                Spacer()
                Text("\(values.count)/\(limit)")
                    .font(.caption)
                    .foregroundStyle(values.count > limit ? .orange : .secondary)
            }
            Text(hint)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            if !values.isEmpty {
                FlowingTags(values: values, accent: accent) { tag in
                    values.removeAll { $0 == tag }
                }
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(availableSuggestions, id: \.self) { suggestion in
                        Button {
                            add(suggestion)
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "plus")
                                    .font(.system(size: 9, weight: .bold))
                                Text(suggestion)
                                    .font(.caption)
                            }
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(
                                Capsule().strokeBorder(Color.primary.opacity(0.12), lineWidth: 1)
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.vertical, 2)
            }

            HStack(spacing: 8) {
                TextField(L("自己写一个"), text: $draft)
                    .font(.footnote)
                    .focused($draftFocused)
                    .onSubmit { add(draft) }
                    .padding(.vertical, 7)
                    .padding(.horizontal, 10)
                    .background(
                        RoundedRectangle(cornerRadius: 9, style: .continuous)
                            .fill(Color.secondary.opacity(0.08))
                    )
                Button(L("加上")) { add(draft) }
                    .font(.footnote)
                    .tint(accent)
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(.vertical, 12)
    }

    private var availableSuggestions: [String] {
        suggestions.filter { !values.contains($0) }
    }

    private func add(_ raw: String) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !values.contains(trimmed) else {
            draft = ""
            return
        }
        values.append(trimmed)
        draft = ""
    }
}

/// 已选标签的换行排列（iOS 16 没有 FlowLayout，用固定列数的 LazyVGrid 近似）。
struct FlowingTags: View {
    let values: [String]
    let accent: Color
    let onRemove: (String) -> Void

    private let columns = [GridItem(.adaptive(minimum: 72), spacing: 8)]

    var body: some View {
        LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
            ForEach(values, id: \.self) { tag in
                Button {
                    onRemove(tag)
                } label: {
                    HStack(spacing: 4) {
                        Text(tag)
                            .font(.caption)
                        Image(systemName: "xmark")
                            .font(.system(size: 8, weight: .bold))
                    }
                    .foregroundStyle(accent)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(accent.opacity(0.12), in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(Lf("去掉 %@", tag)))
            }
        }
    }
}

/// 「主动程度 / 记性 / 唤醒阈值」共用的一行：标题 + 人话解释 + 滑杆 + 当前第几档。
struct LevelSliderRow: View {
    let title: String
    let hint: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    let step: Double
    let accent: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(title)
                    .font(.system(.body, design: .serif))
                Spacer()
                Text("\(Int(value.rounded()))")
                    .font(.footnote)
                    .foregroundStyle(accent)
            }
            Text(hint)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Slider(value: $value, in: range, step: step)
                .tint(accent)
        }
        .padding(.vertical, 12)
    }
}

/// 表单里的小标题 + 一句解释（人格编辑页用）。
struct FieldCaption: View {
    let title: String
    let hint: String

    init(_ title: String, hint: String) {
        self.title = title
        self.hint = hint
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.footnote.weight(.medium))
            Text(hint)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.bottom, 6)
    }
}
