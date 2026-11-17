import SwiftUI

/// 预设编辑页：一整套可复用的设定（名字 / 一句话介绍 / 提示词 / 世界 / 话风）。
///
/// 与人格编辑页的差别：这里改的是"底稿"，改完可以「应用」到某个人格，或「复制」成一份新人格
/// （`spec/persona-guidance/README.md` §2.2：应用是**一次性拷贝**，之后预设变化不自动覆盖人格）。
///
/// 世界设定必须是可视化编辑器（§2.3）：这一页直接把 `WorldPresetEditorView` 嵌进来，
/// 不是 JSON 文本框；AI 补全只填空白项，用户改过的字段不会被覆盖。
struct PresetEditorScreen: View {
    let presetId: String?

    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    @State private var name = ""
    @State private var summary = ""
    @State private var prompt = ""
    @State private var worldEditor = WorldEditorState()
    @State private var speechTone = ""
    @State private var speechPhrases: [String] = []
    @State private var speechHabits: [String] = []
    @State private var speechAvoid: [String] = []
    @State private var transcript = ""
    @State private var worldBusy = false
    @State private var styleBusy = false
    @State private var loaded = false
    @State private var banner: MFAlert?
    /// 「应用 / 复制」的目标人格（默认当前正在聊的那个人）。
    @State private var personas: [PersonaEntity] = []
    @State private var targetPersonaId = ""

    private var isNew: Bool { presetId == nil }
    private var accent: Color { Color(argb: env.currentSettings.accentColor) }
    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    /// 预设也可以什么都不填（0.13 真机反馈第一批）：名字空 → 存成「未命名」，
    /// 提示词空 → 应用/复制时由名字 + 一句话介绍生成。预设本来就是「底稿」，允许粗糙。
    private var canSave: Bool { true }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                identityCard
                worldCard
                speechCard
                applyCard
            }
            .padding(.horizontal, MFTheme.gutter)
            .padding(.vertical, 16)
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .navigationTitle(isNew ? L("新建预设") : L("编辑预设"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(L("保存")) { save() }
                    .fontWeight(.medium)
                    .disabled(!canSave)
            }
        }
        .onAppear(perform: loadIfNeeded)
        .mfAlert($banner)
    }

    // MARK: - 名字 / 介绍 / 提示词

    private var identityCard: some View {
        MFCard {
            FieldCaption(L("预设名字"), hint: L("只是给你自己看的，例如「临港建筑师」。留空也行。"))
            TextField(L("比如：临港建筑师"), text: $name)
                .textFieldStyle(.plain)
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)
                .padding(.bottom, 14)

            FieldCaption(L("一句话介绍"), hint: L("给别人看的一句话，也会成为人格的介绍。可以留空。"))
            TextField(L("比如：一个嘴硬心软的建筑师"), text: $summary)
                .textFieldStyle(.plain)
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)
                .padding(.bottom, 14)

            FieldCaption(L("提示词（他是谁）"), hint: L("可以留空：会用名字和一句话介绍生成一段；两样都空就用默认人设。人设卡等其他字段沿用目标人格的。"))
            TextEditor(text: $prompt)
                .font(.system(.body, design: .serif))
                .scrollContentBackground(.hidden)
                .frame(minHeight: 150)
                .padding(8)
                .background(fieldBackground)
        }
    }

    // MARK: - 世界

    private var worldCard: some View {
        MFCard {
            HStack(alignment: .firstTextBaseline) {
                Text(L("世界设定"))
                    .font(.system(.body, design: .serif))
                Spacer()
                if worldBusy {
                    ProgressView().controlSize(.mini)
                } else {
                    Button(L("AI 补全")) { Task { await expandWorld() } }
                        .font(.footnote)
                        .tint(accent)
                        .disabled(!canExpandWorld)
                }
            }
            MFNote(text: L("他在哪儿生活、平时怎么过。AI 补全只填空白项，你写过的不会被覆盖。"))

            WorldPresetEditorView(state: $worldEditor, accent: accent)
                .padding(.top, 10)

            if !worldEditor.isEmpty {
                Button(L("清空世界设定"), role: .destructive) {
                    worldEditor = WorldEditorState()
                }
                .font(.footnote)
                .padding(.top, 10)
            }
        }
    }

    // MARK: - 话风

    private var speechCard: some View {
        MFCard {
            HStack(alignment: .firstTextBaseline) {
                Text(L("话风"))
                    .font(.system(.body, design: .serif))
                Spacer()
                if styleBusy {
                    ProgressView().controlSize(.mini)
                } else {
                    Button(L("总结话风")) { Task { await summarizeStyle() } }
                        .font(.footnote)
                        .tint(accent)
                        .disabled(transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            MFNote(text: L("他怎么说话：句子长短、口头禅、不说什么。留空就用默认的短句口语。"))

            FieldCaption(
                L("粘贴一段聊天记录"),
                hint: L("支持「昵称：内容」或多行纯文本；只用来总结风格，不会存下来、也不会进备份。")
            )
            ZStack(alignment: .topLeading) {
                if transcript.isEmpty {
                    Text(L("林深：在忙，晚点说\n林深：你先睡吧，不用等我"))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 14)
                }
                TextEditor(text: $transcript)
                    .font(.footnote)
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 90)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
            }
            .background(fieldBackground)
            .padding(.bottom, 10)

            FieldCaption(L("说话风格"), hint: L("一句话概括，例如「简短直接，偏冷淡务实」。"))
            TextField(L("比如：简短直接，偏冷淡务实"), text: $speechTone)
                .textFieldStyle(.plain)
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)
                .padding(.bottom, 8)

            TagEditor(
                title: L("常用语"),
                hint: L("最多 12 条；提示词里只引用前 6 条，一次回复最多自然带 1 处。"),
                suggestions: [],
                limit: SpeechStyle.maxPhrases,
                values: $speechPhrases,
                accent: accent
            )
            MFRowDivider(leadingInset: 0)
            TagEditor(
                title: L("语言习惯"),
                hint: L("句子长短、标点、语气词、表情这些。"),
                suggestions: [],
                limit: SpeechStyle.maxHabits,
                values: $speechHabits,
                accent: accent
            )
            MFRowDivider(leadingInset: 0)
            TagEditor(
                title: L("不要出现"),
                hint: L("他绝不会说的话的样子。"),
                suggestions: [],
                limit: SpeechStyle.maxAvoid,
                values: $speechAvoid,
                accent: accent
            )

            MFNote(text: L("聊天记录只在这一次总结里用：不落库、不进日志、不进备份。"))
        }
    }

    // MARK: - 应用 / 复制

    private var applyCard: some View {
        MFCard {
            Text(L("用到人身上"))
                .font(.system(.body, design: .serif))
            MFNote(text: L("应用会覆盖那个人现在的人设、世界与话风（之后两边互不影响）；复制会新建一个人。"))

            if personas.isEmpty {
                Text(L("还没有可以应用的人格。"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.vertical, 6)
            } else {
                Picker(selection: $targetPersonaId) {
                    ForEach(personas, id: \.id) { persona in
                        Text(persona.name).tag(persona.id)
                    }
                } label: {
                    Text(L("目标人格"))
                        .font(.footnote)
                }
                .pickerStyle(.menu)
                .tint(accent)

                HStack(spacing: 16) {
                    Button(L("应用到这个人")) { applyToPersona() }
                        .font(.footnote.weight(.medium))
                        .tint(accent)
                        .disabled(targetPersonaId.isEmpty)
                    Button(L("复制成新人格")) { copyToNewPersona() }
                        .font(.footnote)
                        .tint(accent)
                }
                .padding(.top, 6)
            }
        }
    }

    // MARK: - 载入 / 保存 / 应用

    private func loadIfNeeded() {
        guard !loaded else { return }
        loaded = true
        personas = (try? env.stores.personas.allOrderedByUpdatedAt()) ?? []
        targetPersonaId = env.currentSettings.activePersonaId
            .flatMap { id in personas.contains(where: { $0.id == id }) ? id : nil }
            ?? personas.first?.id
            ?? ""
        guard let presetId,
              let preset = (try? env.stores.presets.byId(presetId)) ?? nil else { return }
        name = preset.name
        summary = preset.description
        prompt = preset.prompt
        worldEditor = WorldEditorState(preset: WorldPreset.decode(preset.worldJson))
        let style = SpeechStyle.decode(preset.speechStyleJson)
        speechTone = style?.tone ?? ""
        speechPhrases = style?.phrases ?? []
        speechHabits = style?.habits ?? []
        speechAvoid = style?.avoid ?? []
    }

    /// 表单 → 实体（保存与应用共用一份，避免两处拼出不一样的 JSON）。
    private func buildEntity(existing: PersonaPresetEntity?, now: Int64) -> PersonaPresetEntity {
        let worldPreset = worldEditor.preset
        let style = SpeechStyle(
            tone: speechTone,
            phrases: speechPhrases,
            habits: speechHabits,
            avoid: speechAvoid,
            source: .manual
        )
        let resolvedDescription = summary.trimmingCharacters(in: .whitespacesAndNewlines)
        return PersonaPresetEntity(
            id: existing?.id ?? UUID().uuidString,
            name: PersonaDraft.resolvedName(trimmedName, fallback: L("未命名")),
            description: resolvedDescription,
            prompt: PersonaDraft.resolvedPrompt(
                prompt, name: trimmedName, description: resolvedDescription
            ),
            category: existing?.category ?? "",
            avatarUri: existing?.avatarUri,
            avatarSeed: existing?.avatarSeed
                ?? Self.avatarSeed(for: PersonaDraft.resolvedName(trimmedName, fallback: L("未命名"))),
            createdAt: existing?.createdAt ?? now,
            updatedAt: now,
            worldJson: worldPreset.isEmpty ? nil : worldPreset.jsonString(),
            speechStyleJson: style.isEmpty ? nil : style.jsonString()
        )
    }

    private func existingPreset() -> PersonaPresetEntity? {
        guard let presetId else { return nil }
        return (try? env.stores.presets.byId(presetId)) ?? nil
    }

    private func save() {
        Task { @MainActor in
            let now = DomainClock.nowMillis
            do {
                _ = try await env.savePreset(
                    buildEntity(existing: existingPreset(), now: now), isNew: existingPreset() == nil
                )
                dismiss()
            } catch {
                banner = MFAlert(title: L("没能保存"), message: error.localizedDescription)
            }
        }
    }

    private func applyToPersona() {
        guard let persona = personas.first(where: { $0.id == targetPersonaId }) else { return }
        Task { @MainActor in
            let now = DomainClock.nowMillis
            do {
                let preset = try await env.savePreset(
                    buildEntity(existing: existingPreset(), now: now), isNew: existingPreset() == nil
                )
                let updated = PersonaPresetApplier.applying(
                    preset, to: persona, now: now, fallbackName: L("未命名")
                )
                _ = try await env.savePersona(updated, isNew: false)
                _ = env.session(forPersona: updated.id)
                dismiss()
            } catch {
                banner = MFAlert(title: L("没能应用"), message: error.localizedDescription)
            }
        }
    }

    private func copyToNewPersona() {
        Task { @MainActor in
            let now = DomainClock.nowMillis
            do {
                let preset = try await env.savePreset(
                    buildEntity(existing: existingPreset(), now: now), isNew: existingPreset() == nil
                )
                let draft = PersonaPresetApplier.newPersona(
                    from: preset, now: now, fallbackName: L("未命名")
                )
                let created = try await env.savePersona(draft, isNew: true)
                _ = env.session(forPersona: created.id)
                if env.currentSettings.activePersonaId == nil {
                    env.updateSettings { $0.activePersonaId = created.id }
                }
                dismiss()
            } catch {
                banner = MFAlert(title: L("没能复制"), message: error.localizedDescription)
            }
        }
    }

    // MARK: - AI 补全 / 话风总结

    /// 补全用哪份简介：优先一句话介绍，其次提示词。
    private var worldBrief: String {
        let summaryText = summary.trimmingCharacters(in: .whitespacesAndNewlines)
        let promptText = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        return [summaryText, promptText].filter { !$0.isEmpty }.joined(separator: "\n")
    }

    private var canExpandWorld: Bool { !worldBrief.isEmpty }

    private func guidanceConfig() -> (config: CompletionConfig, model: String)? {
        guard let resolved = env.resolvedChatConfig() else { return nil }
        return (CompletionConfig(entity: resolved.config, apiKey: resolved.apiKey), resolved.model)
    }

    private func expandWorld() async {
        guard let resolved = guidanceConfig() else {
            banner = MFAlert(
                title: L("还没有可用的 API 配置"),
                message: L("先在设置里配一个模型，再来补全世界；下面的字段随时可以手填。")
            )
            return
        }
        worldBusy = true
        let result = await WorldPresetExpander.makeDefault().expand(
            personaSummary: worldBrief,
            config: resolved.config,
            model: resolved.model
        )
        worldBusy = false
        // 只填空白项：用户已经写下的名字/描述/日程不会被补全覆盖（§2.3）。
        worldEditor = worldEditor.mergingBlanks(from: result.preset)
        if !result.usedModel {
            banner = MFAlert(
                title: L("这次没补成"),
                message: L("已经先填了一份本地草稿（只有你自己的描述），可以直接改，或者稍后再试。")
            )
        }
    }

    private func summarizeStyle() async {
        guard let resolved = guidanceConfig() else {
            banner = MFAlert(
                title: L("还没有可用的 API 配置"),
                message: L("先在设置里配一个模型，再来总结话风；也可以手填风格与常用语。")
            )
            return
        }
        styleBusy = true
        let result = await SpeechStyleSummarizer.makeDefault().summarize(
            transcript: transcript,
            config: resolved.config,
            model: resolved.model
        )
        styleBusy = false
        speechTone = result.style.tone
        speechPhrases = result.style.phrases
        speechHabits = result.style.habits
        speechAvoid = result.style.avoid
        // 聊天记录用完即弃（§3.3）。
        transcript = ""
        if !result.usedModel {
            banner = MFAlert(
                title: L("这次是本地粗略总结"),
                message: L("没有用上模型，按字面规则大致总结了一版；常用语只有重复出现的句子，可以手动补。")
            )
        }
    }

    // MARK: - 工具

    private var fieldBackground: some View {
        RoundedRectangle(cornerRadius: 9, style: .continuous)
            .fill(Color.secondary.opacity(0.08))
    }

    private static func avatarSeed(for name: String) -> Int {
        let sum = name.unicodeScalars.reduce(0) { $0 &+ Int($1.value) }
        return sum == 0 ? 1 : sum
    }
}
