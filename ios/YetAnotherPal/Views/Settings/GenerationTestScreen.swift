import SwiftUI

/// 设置页 →「测试生成」（0.15）。
///
/// 这一页只回答三个问题：
/// 1. 这个 API 配置**能不能真的说一句话**（不是只请求 `/models`）；
/// 2. 大概多快（首字到结束的耗时）；
/// 3. 它会不会乱用工具（测试话明确要求不用工具，用了也能看见名字）。
///
/// 它**不写聊天库**：不建会话、不落消息、不抽记忆、不更新任何会话预览
///（网络层只发一次补全，见 `AppEnvironment.runGenerationTest`）。
struct GenerationTestScreen: View {

    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var running = false
    @State private var outcome: GenerationTestOutcome?

    private var accent: Color { Color(argb: env.currentSettings.accentColor) }
    private var resolved: AppEnvironment.ResolvedConfig? { env.resolvedChatConfig() }

    private var configName: String {
        let name = resolved?.config.name.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return name.isEmpty ? L("未命名配置") : name
    }

    private var modelName: String {
        let model = resolved?.model.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return model.isEmpty ? L("还没填模型") : model
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                introCard
                runCard
                if let outcome {
                    resultCard(outcome)
                }
            }
            .padding(.horizontal, MFTheme.gutter)
            .padding(.vertical, 16)
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .navigationTitle(L("测试生成"))
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - 说明

    private var introCard: some View {
        MFCard {
            VStack(alignment: .leading, spacing: 6) {
                Text(L("只看看这个 API 配置通不通、大概多快。"))
                    .font(.system(.body, design: .serif))
                Text(L("不会写进聊天，也不会建会话、动消息表；发一句固定测试话就结束。"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: - 配置与按钮

    private var runCard: some View {
        MFCard {
            MFValueRow(icon: "slider.horizontal.3", title: L("配置"), value: configName)
            MFRowDivider(leadingInset: 34)
            MFValueRow(icon: "cpu", title: L("模型"), value: modelName)
            MFRowDivider(leadingInset: 34)

            HStack(spacing: 10) {
                Button(action: run) {
                    HStack(spacing: 6) {
                        if running {
                            ProgressView().scaleEffect(0.7)
                        } else {
                            Image(systemName: "bolt.horizontal.circle")
                        }
                        Text(running ? L("测试中…") : L("开始测试"))
                    }
                    .font(.footnote.weight(.medium))
                }
                .tint(accent)
                .buttonStyle(.plain)
                .disabled(running || resolved == nil)

                Spacer(minLength: 0)
            }
            .padding(.vertical, 10)

            MFNote(text: L("测试会真的发一次补全，会消耗一点点额度，但不会留下任何聊天记录。"))
        }
    }

    // MARK: - 结果

    @ViewBuilder
    private func resultCard(_ outcome: GenerationTestOutcome) -> some View {
        MFCard {
            switch outcome {
            case .success(let report):
                VStack(alignment: .leading, spacing: 10) {
                    Text(L("测试通过"))
                        .font(.system(.body, design: .serif))
                        .foregroundStyle(accent)
                    resultRow(L("耗时"), report.elapsedText)
                    resultRow(L("字数"), report.charCountText)
                    resultRow(L("用到的工具"), report.toolsText)
                    resultRow(L("模型"), report.model.isEmpty ? L("未知") : report.model)
                    resultRow(L("配置"), configName)
                    if !report.preview.isEmpty {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(L("开头预览"))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text(report.preview)
                                .font(.footnote)
                                .foregroundStyle(.primary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.top, 2)
                    }
                }
            case .failure(let message):
                VStack(alignment: .leading, spacing: 6) {
                    Text(L("测试没通过"))
                        .font(.system(.body, design: .serif))
                        .foregroundStyle(MFUI.danger)
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(MFUI.danger)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private func resultRow(_ title: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(width: 52, alignment: .leading)
            Text(value)
                .font(.footnote)
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
    }

    // MARK: - 动作

    /// 跑一次测试。进行中禁用按钮（连点只会多发一次请求，没有意义）。
    private func run() {
        guard !running else { return }
        running = true
        outcome = nil
        Task { @MainActor in
            let result = await env.runGenerationTest()
            guard !Task.isCancelled else { return }
            running = false
            outcome = result
        }
    }
}
