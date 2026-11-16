import SwiftUI

/// 手动记一件事。
///
/// 只收三样东西：正文、分类、重要度。抽屉（USER / SELF / EVENT）由分类推出来，
/// 不让人再做一次「这句是关于谁的」的选择——那是模型该判断的事，人手填反而容易填歪。
/// 落库走 `MemoryRepository.addDetailed`：倒排索引与向量索引会一起建好，写完立刻能被检索到。
struct MemoryEditorSheet: View {
    let personaId: String?
    let onSaved: () -> Void

    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    @State private var content = ""
    @State private var category: MemoryCategory = .fact
    @State private var importance = 3
    @State private var banner: MFAlert?

    private var accent: Color { Color(argb: env.currentSettings.accentColor) }
    private var canSave: Bool {
        !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .firstTextBaseline) {
                    Text(L("手动记一件事"))
                        .font(.system(.title3, design: .serif))
                    Spacer()
                    Button(L("取消")) { dismiss() }
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                MFCard {
                    Text(L("他应该记住什么"))
                        .font(.footnote.weight(.medium))
                    TextEditor(text: $content)
                        .font(.system(.body, design: .serif))
                        .scrollContentBackground(.hidden)
                        .frame(minHeight: 140)
                        .padding(8)
                        .background(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .fill(Color.secondary.opacity(0.06))
                        )
                        .padding(.top, 6)
                    Text(L("例如：对方喜欢在夜里散步，讨厌被追问。"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.top, 6)
                }

                MFCard {
                    Text(L("分类"))
                        .font(.footnote.weight(.medium))
                    MFChoiceBar(
                        options: MemoryCategory.allCases.map { ($0, MFMemoryText.categoryLabel($0)) },
                        selection: $category,
                        accent: accent
                    )
                    .padding(.top, 8)
                    Text(L("分类决定它进哪个抽屉：关于你、关于他自己，还是你们之间的经历。"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.top, 8)
                }

                MFCard {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("重要度"))
                                .font(.footnote.weight(.medium))
                            Text(L("越重要越不容易淡下去。"))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Stepper("", value: $importance, in: 1...5)
                            .labelsHidden()
                        HStack(spacing: 2) {
                            ForEach(1...5, id: \.self) { index in
                                Image(systemName: index <= importance ? "star.fill" : "star")
                                    .font(.system(size: 10))
                                    .foregroundStyle(accent.opacity(index <= importance ? 0.85 : 0.25))
                            }
                        }
                        .frame(minWidth: 76, alignment: .trailing)
                    }
                }

                Button(action: save) {
                    Text(L("记住"))
                        .font(.system(.body, design: .serif))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(canSave ? accent : Color.secondary.opacity(0.35))
                        )
                }
                .buttonStyle(.plain)
                .disabled(!canSave)

                if personaId == nil {
                    Text(L("还没有人格，先建一个再记。"))
                        .font(.caption)
                        .foregroundStyle(MFUI.danger)
                }
            }
            .padding(20)
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .presentationDetents([.large])
        .mfAlert($banner)
    }

    private func save() {
        guard let personaId else {
            banner = MFAlert(title: L("还没有人格"), message: L("先建一个人格，再往他的记忆里记东西。"))
            return
        }
        Task { @MainActor in
            do {
                // 服务端模式：服务端生成 id 后再写本地缓存；本地模式行为不变。
                _ = try await env.addMemory(
                    personaId: personaId,
                    content: content,
                    category: category,
                    importance: importance
                )
                onSaved()
                dismiss()
            } catch {
                banner = MFAlert(title: L("没能记住"), message: error.localizedDescription)
            }
        }
    }
}
