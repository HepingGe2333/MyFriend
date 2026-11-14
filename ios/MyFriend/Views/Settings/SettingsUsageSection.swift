import SwiftUI

/// 设置页的「用量」。
///
/// 1.9 起这块只回答一个问题：**这个月用了多少 token**（§五）。
/// 没有额度、没有花费、没有输入框、没有任何「超了就怎样」的开关 ——
/// 用户不该在这里管账，也不该被一个拦截逻辑影响聊天。
///
/// 两种口径都要出数（§九）：
/// - 按用途：聊天 / 整理记忆 / 从聊天里记东西 … 各用了多少；
/// - 按会话（= 按人）：前 10 名，谁身上用得最多。
struct SettingsUsageSection: View {
    @EnvironmentObject private var env: AppEnvironment

    @State private var totals: UsageTotals = .empty
    /// sessionId → 人格名字（按人排行要显示名字，不要显示一串 uuid）。
    @State private var personaNames: [String: String] = [:]

    private var accent: Color { Color(argb: env.currentSettings.accentColor) }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: L("用量"))

            MFCard {
                VStack(alignment: .leading, spacing: 5) {
                    Text(headlineText)
                        .font(.system(.title3, design: .serif))
                    Text(detailText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.bottom, 2)

                if !totals.byKind.isEmpty {
                    MFRowDivider(leadingInset: 0)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(L("按用途"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.top, 8)
                        ForEach(totals.byKind, id: \.kind) { bucket in
                            HStack(spacing: 8) {
                                Text(bucket.label)
                                    .font(.footnote)
                                Spacer(minLength: 6)
                                Text(Lcount("%d 次调用", bucket.calls) + " · " + tokenText(bucket.totalTokens))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            .padding(.vertical, 5)
                        }
                    }
                    .padding(.bottom, 6)
                }

                if !totals.bySession.isEmpty {
                    MFRowDivider(leadingInset: 0)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(Lf("按人（前 %d 名）", UsageTotals.bySessionLimit))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.top, 8)
                        ForEach(totals.bySession, id: \.sessionId) { bucket in
                            HStack(spacing: 8) {
                                Text(nameOf(bucket))
                                    .font(.footnote)
                                    .lineLimit(1)
                                Spacer(minLength: 6)
                                Text(tokenText(bucket.totalTokens))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            .padding(.vertical, 5)
                        }
                    }
                    .padding(.bottom, 6)
                }

                MFNote(text: L("只记在本机，不上传。这里只有 token：这一版不再有额度，也不会因为用量拦下任何请求。"))
            }
        }
        .onAppear(perform: reload)
    }

    // MARK: - 文案

    private var headlineText: String {
        if totals.isEmpty { return L("他这个月还没用过") }
        return Lf("他这个月一共用了 %@ tokens", tokenText(totals.totalTokens))
    }

    private var detailText: String {
        if totals.isEmpty { return L("等他开口说话，这里会慢慢有数。") }
        let reads = tokenText(totals.promptTokens)
        let writes = tokenText(totals.completionTokens)
        return Lcount("一共 %d 次调用 · 读了 %@ · 写了 %@", totals.calls, reads, writes)
    }

    private func nameOf(_ bucket: SessionUsage) -> String {
        if let personaId = bucket.personaId, let name = personaNames[personaId], !name.isEmpty {
            return name
        }
        // 会话还在、人格名字还没读到（或者人格已经被删了）：说一句人话，别露出 uuid。
        return bucket.personaId == nil ? L("已经删掉的人") : L("一个人")
    }

    private func tokenText(_ value: Int64) -> String {
        // 不按「万」这种中文单位换算：K/M 在中英两边都读得通，也不用为语言换除数。
        if value >= 1_000_000 { return String(format: "%.1fM", Double(value) / 1_000_000) }
        if value >= 10_000 { return String(format: "%.1fK", Double(value) / 1_000) }
        return "\(value)"
    }

    // MARK: - 读

    private func reload() {
        totals = env.usageRecorder.monthTotals()
        let personas = (try? env.stores.personas.all()) ?? []
        personaNames = Dictionary(uniqueKeysWithValues: personas.map { ($0.id, $0.name) })
    }
}
