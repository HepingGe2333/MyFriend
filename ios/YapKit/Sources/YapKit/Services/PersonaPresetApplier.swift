import Foundation

// ---------------------------------------------------------------------------
// 预设 ⇄ 人格：把一份人格预设「应用」到已有人格，或「复制」成一份新人格。
//
// 规范：`spec/persona-guidance/README.md` §2.2 ——
// 「预设应用到人格时，`world` 复制到人格（人格可再改）；之后预设变化**不自动覆盖**已有人格」。
//
// 所以这里只有一次性拷贝，没有任何"跟随预设"的引用：应用之后两边互不影响。
// 头像**不复制**：预设头像与人格头像各自是沙盒里的文件，按内容哈希去重，
// 把 uri 拷过去会让「删预设」时的引用计数判断变得含糊；用户真想要那张图可以自己选。
// 翻译缓存也被清空：人格内容变了，旧译文立即失效（sourceHash 对不上），
// 清掉比留一份永远不用的 JSON 干净。
// ---------------------------------------------------------------------------

public enum PersonaPresetApplier {

    /// 把预设的字段应用到一份已有人格上（返回内存副本，调用方负责 upsert）。
    ///
    /// 版本号：预设的提示词与人设文本覆盖了旧内容 → 版本 +1（与编辑页"改了提示词才升版"同一口径）。
    public static func applying(
        _ preset: PersonaPresetEntity,
        to persona: PersonaEntity,
        now: Int64
    ) -> PersonaEntity {
        var updated = persona
        let promptChanged = updated.prompt != preset.prompt
        updated.name = preset.name
        updated.description = preset.description
        updated.prompt = preset.prompt
        updated.category = preset.category
        updated.worldJson = PromptGuidanceJSON.clean(preset.worldJson)
        updated.speechStyleJson = PromptGuidanceJSON.clean(preset.speechStyleJson)
        // 预设也可能带结构化人设卡：它的 JSON 不在预设列里，所以这里只拷用户能看到的文本字段。
        updated.translationJson = nil
        updated.translationLang = nil
        updated.updatedAt = now
        if promptChanged { updated.version = max(updated.version, 1) + 1 }
        return updated
    }

    /// 从预设新建一份人格（「复制到人格」的动作）。
    public static func newPersona(
        from preset: PersonaPresetEntity,
        id: String = UUID().uuidString,
        now: Int64
    ) -> PersonaEntity {
        PersonaEntity(
            id: id,
            name: preset.name,
            description: preset.description,
            avatarSeed: preset.avatarSeed,
            prompt: preset.prompt,
            category: preset.category,
            version: 1,
            isDefault: false,
            createdAt: now,
            updatedAt: now,
            worldJson: PromptGuidanceJSON.clean(preset.worldJson),
            speechStyleJson: PromptGuidanceJSON.clean(preset.speechStyleJson)
        )
    }
}
