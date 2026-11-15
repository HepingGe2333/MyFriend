import PhotosUI
import SwiftUI

/// 人格编辑页（= 联系人编辑，1.9 §七）。
///
/// 1.9 把「他是谁」从一个巨大的提示词框拆成了分节填写：名字 / 一句话介绍 / 性格 /
/// 喜欢的事 / 行为方式 / 成长经历 / 个人设定，再接三个行为参数（主动程度、记性、
/// 抽记忆频率）与三条睡眠参数（会不会睡、睡的时间段、唤醒阈值）。
///
/// 为什么分节而不是继续写一段话：
/// - 用户填得动。一段 800 字的提示词对大多数人是一堵墙，「性格：慢热、嘴硬心软」是一次点击；
/// - 模型用得上。这些字段会被拼成 `## 你是谁` 下的小节（`PromptBuilder.personaSection`），
///   结构化的小节比一整段话更容易被遵守；
/// - 值可校验。1–5、3–12、1–20 这些范围由界面自己夹住，不用等备份导入时才发现写歪了。
///
/// 提示词全文仍然保留在最下面（可折叠的「高级」）：老用户的那一整段话不会丢。
struct PersonaEditorScreen: View {
    let personaId: String?

    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    // MARK: 表单状态

    @State private var name = ""
    @State private var summary = ""
    /// 头像：从相册选一张（压到 ≤512px 存沙盒），nil = 用名字首字。
    @State private var avatarUri: String?
    @State private var avatarSeed = 0
    /// 载入时的原头像：保存时用它判断「旧图要不要删」。
    @State private var originalAvatarUri: String?
    @State private var avatarPick: PhotosPickerItem?
    @State private var isImportingAvatar = false
    @State private var prompt = ""
    @State private var modelOverride = ""
    @State private var traits: [String] = []
    @State private var likes: [String] = []
    @State private var behaviors: [String] = []
    @State private var backstory = ""
    @State private var quirks = ""
    // 人设引导（0.12）：世界设定 / 话风。都是「可留空」的补充设定，提示词里空着就整节不出现。
    @State private var worldName = ""
    @State private var worldEra = ""
    @State private var worldDescription = ""
    @State private var worldScheduleText = ""
    @State private var worldRelationsText = ""
    @State private var worldObligationsText = ""
    /// 载入时的世界 JSON：用来判断「这次有没有真的动过」（不动就不写库，别把版本/时间戳白推一次）。
    @State private var initialWorldJson: String?
    @State private var speechTone = ""
    @State private var speechPhrases: [String] = []
    @State private var speechHabits: [String] = []
    @State private var speechAvoid: [String] = []
    @State private var speechSource: SpeechStyleSource = .model
    @State private var initialSpeechStyleJson: String?
    /// 用户粘贴的聊天记录：**只在这个页面的内存里**，不落库、不进日志、不进备份。
    @State private var transcript = ""
    @State private var worldBusy = false
    @State private var styleBusy = false
    /// 他此刻在做什么（只读：`set_activity` 由他自己写，用户不能改）。
    @State private var activityLine = ""
    @State private var proactiveLevel = PersonaFieldLimits.proactiveLevelDefault
    @State private var memoryRetention = PersonaFieldLimits.memoryRetentionDefault
    @State private var extractionEvery = PersonaFieldLimits.extractionEveryDefault
    @State private var sleepEnabled = true
    @State private var dndOnSleep = false
    @State private var wakeThreshold = PersonaFieldLimits.wakeThresholdDefault
    @State private var sleepTime = PersonaEditorScreen.time(hour: 23, minute: 30)
    @State private var wakeTime = PersonaEditorScreen.time(hour: 8, minute: 0)
    @State private var promptExpanded = false
    // 结构化人设卡：`card` 是编辑中的卡，`initialCard` 是载入时的那份——
    // 只有「真的动过 + 卡非空」才写库，空卡继续沿用手写提示词（与 Android 编辑页同一条规则）。
    @State private var card = PersonaCard()
    @State private var initialCard = PersonaCard()
    @State private var cardExpanded = false
    @State private var loaded = false
    @State private var banner: MFAlert?

    private var isNew: Bool { personaId == nil }
    private var accent: Color { Color(argb: env.currentSettings.accentColor) }
    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var trimmedPrompt: String { prompt.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                identityCard
                worldCard
                speechCard
                characterCard
                storyCard
                personaCardCard
                behaviorCard
                sleepCard
                advancedCard
            }
            .padding(.horizontal, MFTheme.gutter)
            .padding(.vertical, 16)
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .navigationTitle(isNew ? L("新联系人") : L("编辑联系人"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(L("保存")) { save() }
                    .fontWeight(.medium)
                    .disabled(!canSave)
            }
        }
        .onAppear(perform: loadIfNeeded)
        // 选了新头像又取消 / 保存失败：落盘的那张新图没人引用，离开页面时删掉。
        // 保存成功的路径会把 `originalAvatarUri` 同步成新值，这里不会误删。
        .onDisappear(perform: discardUnsavedAvatar)
        .mfAlert($banner)
    }

    // MARK: - 名字 / 一句话介绍

    private var identityCard: some View {
        MFCard {
            avatarRow

            MFRowDivider(leadingInset: 0)

            FieldCaption(L("名字"), hint: L("他会怎么称呼自己。"))
            TextField(L("比如：林深"), text: $name)
                .textFieldStyle(.plain)
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)
                .padding(.bottom, 14)

            FieldCaption(L("一句话介绍"), hint: L("给别人看的一句话，也能帮他记住自己是谁。"))
            TextField(L("比如：一个温柔而安静的朋友"), text: $summary)
                .textFieldStyle(.plain)
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)
        }
    }

    // MARK: - 头像

    /// 头像这一行：预览 + 「从相册选一张 / 换一张 / 恢复默认」。
    ///
    /// 为什么不做成一整页的「头像设置」：换个头像就是一次选择，不该多一层页面。
    /// 压缩（长边 ≤ 512、JPEG）与落盘都在 `ImageImport` 里，这里只负责把 uri 记进表单。
    private var avatarRow: some View {
        HStack(alignment: .top, spacing: 14) {
            MFAvatar(
                personaName: trimmedName.isEmpty ? L("他") : trimmedName,
                avatarUri: avatarUri,
                avatarSeed: avatarSeed == 0 ? Self.avatarSeed(for: trimmedName.isEmpty ? L("他") : trimmedName) : avatarSeed,
                size: 62
            )
            .overlay(alignment: .bottomTrailing) {
                if isImportingAvatar {
                    ProgressView()
                        .scaleEffect(0.6)
                        .frame(width: 22, height: 22)
                        .background(.thinMaterial, in: Circle())
                }
            }

            VStack(alignment: .leading, spacing: 6) {
                Text(L("头像"))
                    .font(.system(.body, design: .serif))
                Text(avatarUri == nil
                     ? L("没选就先用名字的首字。")
                     : L("这张图只存在这台手机上，不跟着备份走。"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: 14) {
                    PhotosPicker(selection: $avatarPick, matching: .images, photoLibrary: .shared()) {
                        Text(avatarUri == nil ? L("从相册选一张") : L("换一张"))
                            .font(.caption.weight(.medium))
                            .foregroundStyle(accent)
                    }
                    .disabled(isImportingAvatar)
                    .onChange(of: avatarPick) { item in
                        importAvatar(item)
                    }

                    if avatarUri != nil {
                        Button(L("恢复默认")) { removeAvatar() }
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .buttonStyle(.plain)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 10)
    }

    private func importAvatar(_ item: PhotosPickerItem?) {
        guard let item else { return }
        avatarPick = nil
        isImportingAvatar = true
        Task { @MainActor in
            let outcome = await ImageImport.importOne(item, kind: .avatar)
            isImportingAvatar = false
            switch outcome {
            case .success(let attachment):
                avatarUri = attachment.uri
            case .failure(let message):
                banner = MFAlert(title: L("这张图用不了"), message: message)
            }
        }
    }

    /// 离开编辑页时，把「选了但没保存」的头像文件清掉（只删我们自己目录里、且已零引用的）。
    ///
    /// 这里也要查引用：选图按内容哈希去重，刚选的那张可能本来就属于另一条消息/人格。
    private func discardUnsavedAvatar() {
        guard avatarUri != originalAvatarUri else { return }
        MediaStorage.shared.deleteIfUnreferenced(
            avatarUri,
            references: MediaReferences(stores: env.stores, settings: env.settings)
        )
        avatarUri = originalAvatarUri
    }

    private func removeAvatar() {
        if avatarUri != nil, avatarUri != originalAvatarUri {
            // 刚选的新图还没落库，删之前仍要确认没有别的记录引用它。
            MediaStorage.shared.deleteIfUnreferenced(
                avatarUri,
                references: MediaReferences(stores: env.stores, settings: env.settings)
            )
        }
        avatarUri = nil
    }

    // MARK: - 性格 / 喜欢 / 习惯

    // MARK: - 世界设定（人设引导 §2）

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
                        .disabled(worldIsEmptyInput)
                }
            }
            MFNote(text: L("他在哪儿生活、平时怎么过。留空就只有人格，没有世界。"))

            FieldCaption(L("世界 / 城市"), hint: L("一句话，例如「临港新城·云筑建筑事务所」。"))
            TextField(L("比如：临港新城·云筑建筑事务所"), text: $worldName)
                .textFieldStyle(.plain)
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)
                .padding(.bottom, 14)

            FieldCaption(L("时代与地点"), hint: L("例如「当代，中国沿海新一线城市，2026 年」。"))
            TextField(L("比如：当代，中国沿海新一线城市"), text: $worldEra)
                .textFieldStyle(.plain)
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)
                .padding(.bottom, 14)

            FieldCaption(L("世界与他的处境"), hint: L("100–200 字：世界长什么样、他在里面是什么处境。"))
            multilineField($worldDescription, height: 92, placeholder: L("比如：事务所竞争激烈、甲方频繁改图，他带组赶一个高层住宅项目。"))
                .padding(.bottom, 14)

            FieldCaption(L("日程"), hint: L("一行一条，例如「工作日 09:00-18:30 在事务所画图」。"))
            multilineField($worldScheduleText, height: 76, placeholder: L("工作日 09:00-18:30 在事务所画图"))
                .padding(.bottom, 14)

            FieldCaption(L("关系"), hint: L("一行一条，例如「母亲（母亲）：每周三晚打电话」。"))
            multilineField($worldRelationsText, height: 60, placeholder: L("母亲（母亲）：每周三晚打电话"))
                .padding(.bottom, 14)

            FieldCaption(L("要承担的事"), hint: L("一行一条：他必须做的事与压力。"))
            multilineField($worldObligationsText, height: 56, placeholder: L("按节点交图"))

            MFNote(text: L("AI 只是帮你起个头：随时可以手填、改，也可以完全不用它。补全只用上面的介绍与「个人设定」，不会上传对话。"))
            if !worldIsCleared {
                Button(L("清空世界设定"), role: .destructive) { clearWorld() }
                    .font(.footnote)
                    .padding(.top, 8)
            }
        }
    }

    // MARK: - 话风（人设引导 §3）

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
            multilineField($transcript, height: 96, placeholder: L("林深：在忙，晚点说\n林深：你先睡吧，不用等我"))
                .padding(.bottom, 6)
            HStack(spacing: 12) {
                Button(L("清空记录")) { transcript = "" }
                    .font(.footnote)
                    .disabled(transcript.isEmpty)
                Spacer()
                Text(Lf("%d 字", transcript.count))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.bottom, 14)

            if speechSource == .heuristic {
                MFNote(
                    text: L("这是本地粗略总结，可以手动修改。"),
                    icon: "exclamationmark.triangle"
                )
                .padding(.bottom, 8)
            }

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
            if !speechIsCleared {
                Button(L("清空话风"), role: .destructive) { clearSpeech() }
                    .font(.footnote)
                    .padding(.top, 8)
            }
        }
    }

    private var characterCard: some View {
        MFCard {
            TagEditor(
                title: L("性格"),
                hint: Lf("他是怎样的人。点一个加上，也可以自己写。最多 %d 个。", PersonaFieldLimits.maxTraits),
                suggestions: Self.traitSuggestions,
                limit: PersonaFieldLimits.maxTraits,
                values: $traits,
                accent: accent
            )

            MFRowDivider(leadingInset: 0)

            TagEditor(
                title: L("喜欢的事"),
                hint: Lf("他乐意做的事、爱聊的话题。最多 %d 个。", PersonaFieldLimits.maxLikes),
                suggestions: Self.likeSuggestions,
                limit: PersonaFieldLimits.maxLikes,
                values: $likes,
                accent: accent
            )

            MFRowDivider(leadingInset: 0)

            TagEditor(
                title: L("行为方式"),
                hint: Lf("他习惯怎么相处，比如「先听完再给建议」。最多 %d 个。", PersonaFieldLimits.maxBehaviors),
                suggestions: Self.behaviorSuggestions,
                limit: PersonaFieldLimits.maxBehaviors,
                values: $behaviors,
                accent: accent
            )
        }
    }

    // MARK: - 成长经历 / 个人设定

    private var storyCard: some View {
        MFCard {
            FieldCaption(L("成长经历"), hint: Lf("他过去发生过什么。≤ %d 字。", PersonaFieldLimits.backstoryMax))
            TextEditor(text: $backstory)
                .font(.system(.body, design: .serif))
                .scrollContentBackground(.hidden)
                .frame(minHeight: 110)
                .padding(8)
                .background(fieldBackground)
                .overlay(alignment: .bottomTrailing) {
                    Text("\(backstory.count)/\(PersonaFieldLimits.backstoryMax)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .padding(6)
                }
                .padding(.bottom, 14)

            FieldCaption(L("个人设定"), hint: Lf("称呼、口头禅、忌讳这些细节。≤ %d 字。", PersonaFieldLimits.quirksMax))
            TextEditor(text: $quirks)
                .font(.system(.body, design: .serif))
                .scrollContentBackground(.hidden)
                .frame(minHeight: 90)
                .padding(8)
                .background(fieldBackground)
        }
    }

    // MARK: - 人设卡（结构化）

    /// 结构化人设卡：身份 / 说话方式 / 性格 / 在意的事 / 边界 / 生活 / 你们之间。
    ///
    /// 为什么和上面的「性格 / 喜欢 / 行为方式」并存：上面那三组是 1.9 的**五段人设**（会拼成
    /// `## 你是谁` 的小节，永远追加在提示词后面）；人设卡更细，而且会**替掉提示词全文**——
    /// 系统提示里只留一条短策略，正文按预算进对话。两边都填时以卡为准，卡空着就继续用手写提示词。
    private var personaCardCard: some View {
        MFCard {
            Button {
                withAnimation(.easeInOut(duration: 0.16)) { cardExpanded.toggle() }
            } label: {
                HStack(alignment: .center, spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("人设卡"))
                            .font(.system(.body, design: .serif))
                        Text(card.isEmpty
                             ? L("可选：空着就继续用提示词全文")
                             : L("身份 · 说话方式 · 性格 · 在意的事 · 边界 · 生活 · 你们之间"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    Image(systemName: cardExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if cardExpanded {
                MFRowDivider(leadingInset: 0)
                cardEditor
            }
        }
    }

    private var cardEditor: some View {
        VStack(alignment: .leading, spacing: 14) {
            MFNote(text: L("填了卡，他的人格就按这些字段来：系统提示里只放一条短策略，正文按预算进对话。保存时提示词全文会被卡的镜像替换；卡空着就继续用你手写的那段。"))
                .padding(.top, 10)

            cardGroupLabel(L("身份"))
            cardIdentityFields

            cardGroupLabel(L("说话方式"))
            cardVoiceFields

            cardGroupLabel(L("性格标签"))
            cardTraitsEditor

            cardGroupLabel(L("在意的事"))
            TagEditor(
                title: L("他在意的事"),
                hint: Lf("他看重什么、会为什么认真。最多 %d 条。", PersonaCardLimits.maxListItems),
                suggestions: [],
                limit: PersonaCardLimits.maxListItems,
                values: $card.values,
                accent: accent
            )

            cardGroupLabel(L("边界"))
            TagEditor(
                title: L("他不会做的事"),
                hint: Lf("明确的边界，比如「不假装记得没说过的事」。最多 %d 条。", PersonaCardLimits.maxListItems),
                suggestions: [],
                limit: PersonaCardLimits.maxListItems,
                values: $card.boundaries,
                accent: accent
            )

            cardGroupLabel(L("生活"))
            cardLifeFields

            cardGroupLabel(L("你们之间"))
            cardField(
                L("他对你的认知与变化"),
                hint: Lf("他怎么理解你、这段关系里发生过什么。≤ %d 字。", PersonaCardLimits.bondMax),
                text: cardText(\.bond, limit: PersonaCardLimits.bondMax),
                minHeight: 110
            )
        }
        .padding(.top, 12)
    }

    // 小段拆开写：一个巨大的 `VStack` 会让 SwiftUI 的类型检查慢到离谱。
    private var cardIdentityFields: some View {
        VStack(alignment: .leading, spacing: 12) {
            cardField(
                L("名字"),
                hint: L("人设卡里他自己的名字。留空时用「身份 / 职业」当称呼。"),
                placeholder: L("比如：林岸"),
                text: cardText(\.identity.name, limit: PersonaCardLimits.nameMax)
            )
            cardField(
                L("年龄感"),
                placeholder: L("比如：二十多岁"),
                text: cardText(\.identity.age, limit: PersonaCardLimits.nameMax)
            )
            cardField(
                L("身份 / 职业"),
                placeholder: L("比如：开旧书店的人"),
                text: cardText(\.identity.role, limit: PersonaCardLimits.roleMax)
            )
            cardField(
                L("和你的关系"),
                placeholder: L("比如：认识很多年的朋友"),
                text: cardText(\.identity.relation, limit: PersonaCardLimits.nameMax)
            )
        }
    }

    private var cardVoiceFields: some View {
        VStack(alignment: .leading, spacing: 12) {
            cardField(
                L("语速"),
                placeholder: L("比如：慢，偶尔停下来想想"),
                text: cardText(\.voice.pace, limit: PersonaCardLimits.fieldMax)
            )
            cardField(
                L("句子长短"),
                placeholder: L("比如：短句，一次说一两句"),
                text: cardText(\.voice.sentence, limit: PersonaCardLimits.fieldMax)
            )
            TagEditor(
                title: L("口头禅"),
                hint: Lf("他常挂在嘴边的短句。最多 %d 条。", PersonaCardLimits.maxListItems),
                suggestions: [],
                limit: PersonaCardLimits.maxListItems,
                values: $card.voice.catchphrases,
                accent: accent
            )
            cardField(
                L("怎么称呼你"),
                placeholder: L("比如：喊你名字的后两个字"),
                text: cardText(\.voice.address, limit: PersonaCardLimits.nameMax)
            )
            cardField(
                L("语气小习惯"),
                placeholder: L("比如：少用感叹号，爱用省略号"),
                text: cardText(\.voice.quirks, limit: PersonaCardLimits.fieldMax)
            )
        }
    }

    private var cardLifeFields: some View {
        VStack(alignment: .leading, spacing: 12) {
            cardField(
                L("作息"),
                placeholder: L("比如：凌晨两点睡，中午起"),
                text: cardText(\.life.schedule, limit: PersonaCardLimits.fieldMax)
            )
            cardField(
                L("现在在"),
                placeholder: L("比如：把店里的旧书重新分类"),
                text: cardText(\.life.doing, limit: PersonaCardLimits.fieldMax)
            )
            cardField(
                L("最近在意"),
                placeholder: L("比如：你最近睡得好不好"),
                text: cardText(\.life.caresAbout, limit: PersonaCardLimits.fieldMax)
            )
        }
    }

    private var cardTraitsEditor: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(L("他有哪些性格，以及有多强（0–1）"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer(minLength: 4)
                Text("\(card.traits.count)/\(PersonaCardLimits.maxTraits)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            if card.traits.isEmpty {
                Text(L("还没有标签。"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            ForEach(card.traits.indices, id: \.self) { index in
                cardTraitRow(index)
            }
            if card.traits.count < PersonaCardLimits.maxTraits {
                Button {
                    card.traits.append(PersonaTrait())
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "plus")
                            .font(.system(size: 10, weight: .bold))
                        Text(L("添加一条"))
                    }
                    .font(.footnote)
                    .foregroundStyle(accent)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func cardTraitRow(_ index: Int) -> some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                TextField(L("比如：嘴硬心软"), text: cardTraitNameBinding(index))
                    .textFieldStyle(.plain)
                    .padding(.vertical, 8)
                    .padding(.horizontal, 10)
                    .background(fieldBackground)
                Button {
                    if card.traits.indices.contains(index) { card.traits.remove(at: index) }
                } label: {
                    Image(systemName: "minus.circle")
                        .font(.system(size: 15))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(L("删掉这个性格")))
            }
            HStack(spacing: 8) {
                Text(L("强度"))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Slider(value: cardTraitStrengthBinding(index), in: 0...1)
                    .tint(accent)
                Text(cardTraitStrengthText(index))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .frame(width: 26, alignment: .trailing)
            }
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 9, style: .continuous)
                .fill(Color.secondary.opacity(0.06))
        )
    }

    private func cardGroupLabel(_ title: String) -> some View {
        Text(title)
            .font(.caption.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(.top, 6)
    }

    /// 人设卡里某个文本字段的绑定：编辑时顺手把长度夹到上限（上限与 `PersonaCardLimits` 一致）。
    private func cardText(_ keyPath: WritableKeyPath<PersonaCard, String>, limit: Int) -> Binding<String> {
        Binding(
            get: { card[keyPath: keyPath] },
            set: { card[keyPath: keyPath] = String($0.prefix(limit)) }
        )
    }

    @ViewBuilder
    private func cardField(
        _ title: String,
        hint: String = "",
        placeholder: String = "",
        text: Binding<String>,
        limit: Int = PersonaCardLimits.fieldMax,
        minHeight: CGFloat = 0
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(title)
                    .font(.footnote.weight(.medium))
                Spacer(minLength: 4)
                Text("\(text.wrappedValue.count)/\(limit)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            if !hint.isEmpty {
                Text(hint)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if minHeight > 0 {
                TextEditor(text: text)
                    .font(.system(.body, design: .serif))
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: minHeight)
                    .padding(8)
                    .background(fieldBackground)
            } else {
                TextField(placeholder, text: text)
                    .textFieldStyle(.plain)
                    .padding(.vertical, 8)
                    .padding(.horizontal, 10)
                    .background(fieldBackground)
            }
        }
    }

    private func cardTraitNameBinding(_ index: Int) -> Binding<String> {
        Binding(
            get: { card.traits.indices.contains(index) ? card.traits[index].name : "" },
            set: { value in
                guard card.traits.indices.contains(index) else { return }
                card.traits[index].name = String(value.prefix(PersonaCardLimits.nameMax))
            }
        )
    }

    private func cardTraitStrengthBinding(_ index: Int) -> Binding<Double> {
        Binding(
            get: { card.traits.indices.contains(index) ? Double(card.traits[index].strength) : 0.5 },
            set: { value in
                guard card.traits.indices.contains(index) else { return }
                card.traits[index].strength = min(max(Float(value), 0), 1)
            }
        )
    }

    private func cardTraitStrengthText(_ index: Int) -> String {
        let strength = card.traits.indices.contains(index) ? card.traits[index].strength : 0.5
        return String(format: "%.1f", strength)
    }

    // MARK: - 行为参数

    private var behaviorCard: some View {
        MFCard {
            LevelSliderRow(
                title: L("主动程度"),
                hint: ProactiveLevel.description(proactiveLevel),
                value: Binding(
                    get: { Double(proactiveLevel) },
                    set: { proactiveLevel = Int($0.rounded()) }
                ),
                range: 1...5,
                step: 1,
                accent: accent
            )

            MFRowDivider(leadingInset: 0)

            LevelSliderRow(
                title: L("记性"),
                hint: Self.memoryRetentionText(memoryRetention),
                value: Binding(
                    get: { Double(memoryRetention) },
                    set: { memoryRetention = Int($0.rounded()) }
                ),
                range: 1...5,
                step: 1,
                accent: accent
            )

            MFRowDivider(leadingInset: 0)

            LevelSliderRow(
                title: L("多久记一次"),
                hint: Lcount("每聊 %d 条消息，他会在后台回头看一眼，把值得记的事记下来。", extractionEvery),
                value: Binding(
                    get: { Double(extractionEvery) },
                    set: { extractionEvery = Int($0.rounded()) }
                ),
                range: 3...12,
                step: 1,
                accent: accent
            )
        }
    }

    // MARK: - 睡眠

    private var sleepCard: some View {
        MFCard {
            // 当前活动只读：`set_activity` 是他自己的动作，界面只显示「他正在做什么」。
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(L("他正在做什么"))
                    .font(.system(.body, design: .serif))
                Spacer()
                Text(activityLine.isEmpty ? L("没在忙什么") : activityLine)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.trailing)
            }
            MFNote(text: L("这一栏由他自己更新（他会在对话里说自己接下来要忙什么），你不用填。"))
            MFRowDivider(leadingInset: 0)
            Toggle(isOn: $sleepEnabled) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(L("他会自己睡觉"))
                        .font(.system(.body, design: .serif))
                    Text(sleepEnabled
                         ? L("到点就去睡。睡着时你发消息他多半不回，除非连着叫醒他几次。")
                         : L("他不睡，任何时间都会回你。"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(accent)

            if sleepEnabled {
                MFRowDivider(leadingInset: 0)

                HStack {
                    Text(L("几点睡"))
                        .font(.system(.body, design: .serif))
                    Spacer()
                    DatePicker("", selection: $sleepTime, displayedComponents: .hourAndMinute)
                        .labelsHidden()
                }
                .padding(.vertical, 8)

                MFRowDivider(leadingInset: 0)

                HStack {
                    Text(L("几点醒"))
                        .font(.system(.body, design: .serif))
                    Spacer()
                    DatePicker("", selection: $wakeTime, displayedComponents: .hourAndMinute)
                        .labelsHidden()
                }
                .padding(.vertical, 8)

                MFRowDivider(leadingInset: 0)

                LevelSliderRow(
                    title: L("叫醒他要几条消息"),
                    hint: Lcount("睡着的时候，你连着发满 %d 条，他会被叫醒（并有点不好意思）。1–20。", wakeThreshold),
                    value: Binding(
                        get: { Double(wakeThreshold) },
                        set: { wakeThreshold = Int($0.rounded()) }
                    ),
                    range: 1...20,
                    step: 1,
                    accent: accent
                )

                // 免打扰是**他自己**的决定（§6.1）：模型可以在对话/独处时打开或关掉，
                // 用户不能直接改。所以这里只读地显示一句现状，不给开关。
                HStack(spacing: 8) {
                    Image(systemName: dndOnSleep ? "moon.zzz.fill" : "moon.zzz")
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                    Text(dndOnSleep
                         ? L("睡前免打扰：开着（他自己选的）。睡着时你发什么都叫不醒他，消息留到醒了再看。")
                         : L("睡前免打扰：关着（他自己选的）。睡着时连着发几条能把他叫醒。"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.vertical, 10)

                MFNote(text: Lf("现在：%@ 睡 → %@ 醒", clock(sleepMinutes), clock(wakeMinutes)))
            }
        }
    }

    // MARK: - 高级（提示词全文 + 模型）

    private var advancedCard: some View {
        MFCard {
            Button {
                withAnimation(.easeInOut(duration: 0.16)) { promptExpanded.toggle() }
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("提示词全文（高级）"))
                            .font(.system(.body, design: .serif))
                        Text(L("上面那些字段会被拼成小节，接在这一段后面。"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Image(systemName: promptExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if promptExpanded {
                MFRowDivider(leadingInset: 0)
                HStack(alignment: .firstTextBaseline) {
                    Text(L("他是谁、怎么说话、什么不做。原文会完整保存。"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text(Lcount("%d 字", prompt.count))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 8)
                .padding(.bottom, 8)

                TextEditor(text: $prompt)
                    .font(.system(.body, design: .serif))
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 200)
                    .padding(8)
                    .background(fieldBackground)

                MFRowDivider(leadingInset: 0)

                FieldCaption(L("模型覆盖（可留空）"), hint: L("留空就用 API 配置里的模型；填了只对这个人生效。"))
                TextField(modelHint, text: $modelOverride)
                    .textFieldStyle(.plain)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(.vertical, 8)
                    .padding(.horizontal, 10)
                    .background(fieldBackground)

                MFNote(text: L("改完之后版本号会 +1，方便你在备份里分辨是哪一版的他。"))
            }
        }
    }

    private var modelHint: String {
        if let model = env.activeApiConfig?.model, !model.isEmpty {
            return Lf("留空使用 %@", model)
        }
        return L("留空使用 API 配置中的模型")
    }

    // MARK: - 人设引导：编辑中的世界 / 话风 / 活动

    /// 多行输入框（世界描述、日程、聊天记录都用它）。
    private func multilineField(_ text: Binding<String>, height: CGFloat, placeholder: String) -> some View {
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

    /// 编辑中的世界预设（字段 → 结构；空的整块就是空预设）。
    private var editingWorld: WorldPreset {
        WorldPreset(
            worldName: worldName,
            era: worldEra,
            description: worldDescription,
            schedule: WorldPreset.parseScheduleLines(worldScheduleText),
            relationships: WorldPreset.parseRelationshipLines(worldRelationsText),
            obligations: WorldPreset.parseObligationLines(worldObligationsText)
        )
    }

    private var editingSpeechStyle: SpeechStyle {
        SpeechStyle(
            tone: speechTone,
            phrases: speechPhrases,
            habits: speechHabits,
            avoid: speechAvoid,
            source: speechSource
        )
    }

    /// 世界输入全空时不显示「清空」按钮，也不发补全请求。
    private var worldIsCleared: Bool {
        editingWorld.isEmpty
    }

    /// AI 补全要一句「角色简介」：优先用一句话介绍，其次用个人设定；
    /// 两者都空就没有可补全的输入（按钮置灰）。
    private var worldIsEmptyInput: Bool {
        let summaryText = summary.trimmingCharacters(in: .whitespacesAndNewlines)
        let quirksText = quirks.trimmingCharacters(in: .whitespacesAndNewlines)
        return summaryText.isEmpty && quirksText.isEmpty
    }

    private var speechIsCleared: Bool {
        editingSpeechStyle.isEmpty
    }

    /// 把世界结构写回编辑字段（载入与 AI 补全共用一条路径，避免两边长得不一样）。
    private func apply(world: WorldPreset?) {
        worldName = world?.worldName ?? ""
        worldEra = world?.era ?? ""
        worldDescription = world?.description ?? ""
        worldScheduleText = world.map { WorldPreset.scheduleLines($0.schedule) } ?? ""
        worldRelationsText = world.map { WorldPreset.relationshipLines($0.relationships) } ?? ""
        worldObligationsText = world.map { WorldPreset.obligationLines($0.obligations) } ?? ""
    }

    private func apply(style: SpeechStyle?) {
        speechTone = style?.tone ?? ""
        speechPhrases = style?.phrases ?? []
        speechHabits = style?.habits ?? []
        speechAvoid = style?.avoid ?? []
        speechSource = style?.source ?? .model
    }

    private func clearWorld() {
        apply(world: nil)
        initialWorldJson = nil
    }

    private func clearSpeech() {
        apply(style: nil)
        transcript = ""
        initialSpeechStyleJson = nil
    }

    /// 这次请求用哪份配置：与聊天同一个口径（`AppEnvironment.resolveConfig`）。
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
        let summaryText = summary.trimmingCharacters(in: .whitespacesAndNewlines)
        let quirksText = quirks.trimmingCharacters(in: .whitespacesAndNewlines)
        let brief = [summaryText, quirksText].filter { !$0.isEmpty }.joined(separator: "\n")
        worldBusy = true
        let expander = WorldPresetExpander.makeDefault()
        let result = await expander.expand(
            personaSummary: brief,
            config: resolved.config,
            model: resolved.model
        )
        worldBusy = false
        apply(world: result.preset)
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
        let summarizer = SpeechStyleSummarizer.makeDefault()
        let result = await summarizer.summarize(
            transcript: transcript,
            config: resolved.config,
            model: resolved.model
        )
        styleBusy = false
        apply(style: result.style)
        // 聊天记录用完即弃（§3.3）：总结完就把它从界面状态里清掉，没有第二处引用。
        transcript = ""
        if !result.usedModel {
            banner = MFAlert(
                title: L("这次是本地粗略总结"),
                message: L("没有用上模型，按字面规则大致总结了一版；常用语只有重复出现的句子，可以手动补。")
            )
        }
    }

    // MARK: - 载入与保存

    private func loadIfNeeded() {
        guard !loaded else { return }
        loaded = true
        guard let personaId, let persona = (try? env.stores.personas.byId(personaId)) ?? nil else {
            return
        }
        name = persona.name
        summary = persona.description
        avatarUri = persona.avatarUri
        originalAvatarUri = persona.avatarUri
        avatarSeed = persona.avatarSeed
        prompt = persona.prompt
        modelOverride = persona.modelOverride ?? ""
        traits = persona.traits
        likes = persona.likes
        behaviors = persona.behaviors
        backstory = persona.backstory ?? ""
        quirks = persona.quirks ?? ""
        proactiveLevel = persona.proactiveLevel
        memoryRetention = persona.memoryRetention
        extractionEvery = persona.extractionEvery
        sleepEnabled = persona.sleepEnabled
        dndOnSleep = persona.dndOnSleep
        wakeThreshold = persona.wakeThreshold
        sleepTime = Self.time(fromMinutes: SleepSchedule.sleepMinutes(persona))
        wakeTime = Self.time(fromMinutes: SleepSchedule.wakeMinutes(persona))
        // 人设引导：世界 / 话风 / 当前活动。坏 JSON 等于没有（宽容解析），界面不报错。
        initialWorldJson = persona.worldJson
        apply(world: WorldPreset.decode(persona.worldJson))
        initialSpeechStyleJson = persona.speechStyleJson
        apply(style: SpeechStyle.decode(persona.speechStyleJson))
        let now = DomainClock.nowMillis
        if let activity = PersonaActivity.decode(persona.activityJson, now: now) {
            activityLine = Lf("%@（到 %@ 结束）", activity.activity, activity.untilClockText())
        } else {
            activityLine = ""
        }
        // 人设卡：有卡读卡，没卡从旧提示词里临时拆一份（只读，不写库，与 Android 一致）。
        let loadedCard = env.personaCards.readCard(persona)
        card = loadedCard
        initialCard = loadedCard
        cardExpanded = !loadedCard.isEmpty
        // 提示词只有真写过才展开：默认收起，别让一屏全是字号很小的正文。
        promptExpanded = !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && isNew == false
    }

    /// 只要求名字：其余字段都可以空着（§三「老数据不填也能用」）。
    private var canSave: Bool { !trimmedName.isEmpty }

    private func save() {
        let now = DomainClock.nowMillis
        let existing: PersonaEntity? = personaId.flatMap { try? env.stores.personas.byId($0) } ?? nil
        var entity = existing ?? PersonaEntity(
            id: UUID().uuidString,
            name: trimmedName,
            createdAt: now,
            updatedAt: now
        )
        let promptChanged = entity.prompt != prompt
        entity.name = trimmedName
        entity.description = summary.trimmingCharacters(in: .whitespacesAndNewlines)
        entity.avatarUri = avatarUri
        entity.prompt = prompt
        entity.modelOverride = modelOverride.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? nil
            : modelOverride.trimmingCharacters(in: .whitespacesAndNewlines)
        entity.traits = PersonaTags.normalize(traits, limit: PersonaFieldLimits.maxTraits)
        entity.likes = PersonaTags.normalize(likes, limit: PersonaFieldLimits.maxLikes)
        entity.behaviors = PersonaTags.normalize(behaviors, limit: PersonaFieldLimits.maxBehaviors)
        entity.backstory = PersonaEntity.clean(backstory, limit: PersonaFieldLimits.backstoryMax)
        entity.quirks = PersonaEntity.clean(quirks, limit: PersonaFieldLimits.quirksMax)
        entity.proactiveLevel = PersonaFieldLimits.clampLevel(proactiveLevel)
        entity.memoryRetention = PersonaFieldLimits.clampLevel(memoryRetention)
        entity.extractionEvery = PersonaFieldLimits.clampExtractionEvery(extractionEvery)
        entity.sleepEnabled = sleepEnabled
        entity.dndOnSleep = dndOnSleep
        entity.wakeThreshold = PersonaFieldLimits.clampWakeThreshold(wakeThreshold)
        if sleepEnabled {
            entity.quietSleepMinutes = sleepMinutes
            entity.quietWakeMinutes = wakeMinutes
        }
        // 人设引导：世界与话风整组落库（空 = 清空），活动不动——那是他自己的状态。
        let worldJson = editingWorld.isEmpty ? nil : editingWorld.jsonString()
        let speechJson = editingSpeechStyle.isEmpty ? nil : editingSpeechStyle.jsonString()
        // 只有真的动过才写：`initialWorldJson` / `initialSpeechStyleJson` 是载入时的原文，
        // 没改过就原样留着（免得每次保存都把两段 JSON 重新序列化一遍、把 updatedAt 白推一次）。
        if PromptGuidanceJSON.clean(initialWorldJson) != worldJson {
            entity.worldJson = worldJson
        }
        if PromptGuidanceJSON.clean(initialSpeechStyleJson) != speechJson {
            entity.speechStyleJson = speechJson
        }
        entity.updatedAt = now
        if existing == nil {
            entity.version = max(entity.version, 1)
            entity.avatarSeed = Self.avatarSeed(for: trimmedName)
        } else if promptChanged {
            // 提示词变了才算新的一版；只改性格或作息不该把版本号顶上去。
            entity.version = max(entity.version, 1) + 1
        }

        do {
            try env.stores.personas.upsert(entity)
            // 结构化人设卡：只有「用户真的动过、且卡非空」才写库。
            // 空卡代表「我还是想用上面手写的那段提示词」，不能拿空镜像把它抹掉（Android 同一条规则）。
            if card != initialCard && !card.isEmpty {
                var finalCard = card
                // 卡里没填名字时用上面填的人格名——否则系统提示里的稳定策略会退化成通用兜底。
                if finalCard.identity.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    finalCard.identity.name = trimmedName
                }
                // 走 saveCardFields：它会在落库前按卡片上限收敛（去空项、夹强度、截长度），
                // 并把 prompt 重写成卡的镜像。卡没变化时它什么也不写。
                if case .failure(let error) = env.personaCards.saveCardFields(
                    personaId: entity.id,
                    identity: finalCard.identity,
                    voice: finalCard.voice,
                    traits: finalCard.traits,
                    values: finalCard.values,
                    boundaries: finalCard.boundaries,
                    life: finalCard.life,
                    bond: finalCard.bond,
                    reason: L("编辑人设卡")
                ) {
                    banner = MFAlert(
                        title: L("人设卡没写进去"),
                        message: Lf("人格已经保存了，但结构化人设卡写入失败：%@", error.localizedDescription)
                    )
                    return
                }
            }
            // 换了头像：新头像已经落库，再删旧文件；只删零引用的
            // （旧图可能同时被预设头像 / 我的头像 / 另一条消息共享）。
            if originalAvatarUri != avatarUri {
                MediaStorage.shared.deleteIfUnreferenced(
                    originalAvatarUri,
                    references: MediaReferences(stores: env.stores, settings: env.settings)
                )
                originalAvatarUri = avatarUri
            }
            // 一人一段（§二）：创建人格的同时就把他那一段会话建好，
            // 否则首页点进去还要再补一次，补失败就成了「点了没反应」。
            _ = env.session(forPersona: entity.id)
            if env.currentSettings.activePersonaId == nil {
                env.updateSettings { $0.activePersonaId = entity.id }
            }
            dismiss()
        } catch {
            banner = MFAlert(title: L("没能保存"), message: error.localizedDescription)
        }
    }

    // MARK: - 工具

    private var fieldBackground: some View {
        RoundedRectangle(cornerRadius: 9, style: .continuous)
            .fill(Color.secondary.opacity(0.08))
    }

    private var sleepMinutes: Int { Self.minutesOfDay(sleepTime) }
    private var wakeMinutes: Int { Self.minutesOfDay(wakeTime) }

    private func clock(_ minutes: Int) -> String {
        ServiceTime.clockText(minutes)
    }

    private static func memoryRetentionText(_ level: Int) -> String {
        switch min(max(level, 1), 5) {
        case 1: return L("忘得快：很久以前的事会慢慢淡掉。")
        case 2: return L("记性一般。")
        case 3: return L("正常记性：重要的事记得住。")
        case 4: return L("记性好，很久以前提过的事都还在。")
        default: return L("什么都记得，包括你随口说过的话。")
        }
    }

    private static let traitSuggestions = [
        L("慢热"), L("嘴硬心软"), L("话少"), L("爱开玩笑"), L("安静"), L("容易吃醋"),
        L("理性"), L("爱撒娇"), L("倔"), L("温柔"), L("怕冷"), L("好奇心重")
    ]
    private static let likeSuggestions = [
        L("夜里散步"), L("旧书"), L("下雨天"), L("咖啡"), L("猫"), L("听歌"), L("做饭"), L("看球")
    ]
    private static let behaviorSuggestions = [
        L("先听完再给建议"), L("不主动打断"), L("有话直说"), L("拐弯抹角"), L("先道歉再讲道理"), L("只在深夜说真心话")
    ]

    /// 头像底色的种子：与名字绑定、与进程无关（`hashValue` 每次启动都会变，不能用）。
    private static func avatarSeed(for name: String) -> Int {
        let sum = name.unicodeScalars.reduce(0) { $0 &+ Int($1.value) }
        return sum == 0 ? 1 : sum
    }

    private static func minutesOfDay(_ date: Date) -> Int {
        let components = Calendar.current.dateComponents([.hour, .minute], from: date)
        return (components.hour ?? 0) * 60 + (components.minute ?? 0)
    }

    fileprivate static func time(hour: Int, minute: Int) -> Date {
        Calendar.current.date(
            bySettingHour: hour,
            minute: minute,
            second: 0,
            of: Date()
        ) ?? Date()
    }

    fileprivate static func time(fromMinutes minutes: Int) -> Date {
        let wrapped = ((minutes % 1440) + 1440) % 1440
        return time(hour: wrapped / 60, minute: wrapped % 60)
    }
}

// MARK: - 表单小部件

/// 一个可多选的标签编辑器：点建议词加上/去掉，也可以自己写一个。
///
/// 为什么不用系统 `Menu` / `Picker`：这些是「多个并列的小词」，用一排可点的胶囊
/// 比展开一个列表更直观；用户还能看到「我现在一共有几个」。
private struct TagEditor: View {
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
                    .foregroundStyle(.secondary)
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
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || values.count >= limit)
            }
        }
        .padding(.vertical, 12)
    }

    private var availableSuggestions: [String] {
        suggestions.filter { !values.contains($0) }
    }

    private func add(_ raw: String) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, values.count < limit, !values.contains(trimmed) else {
            draft = ""
            return
        }
        values.append(trimmed)
        draft = ""
    }
}

/// 已选标签的换行排列（iOS 16 没有 FlowLayout，用固定列数的 LazyVGrid 近似）。
private struct FlowingTags: View {
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
private struct LevelSliderRow: View {
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
private struct FieldCaption: View {
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
