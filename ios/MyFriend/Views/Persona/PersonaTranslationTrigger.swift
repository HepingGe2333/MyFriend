import Foundation

/// 「进聊天 / 生成主动消息前」异步翻译的触发点（`spec/persona-translation/README.md` §2）。
///
/// 为什么触发放在视图层、而不是 `ChatService` 里：
/// - 规范要求**不阻塞首屏**：进聊天时 fire-and-forget 一次，拼提示词时只读已有缓存；
///   Kit 里那句 `PersonaTranslation.applyingCached` 是同步的，所以第一轮用原文、
///   翻译落地后的下一轮就用译文，聊天永远不会等一次翻译；
/// - 单测里的 `ChatService` 也不该因为"顺手翻译"多发一次网络请求。
///
/// 同一进程里的去重由共享的 `PersonaTranslationService` 负责（key = personaId + lang + sourceHash），
/// 所以反复进出同一个会话不会反复请求；缓存命中时零网络。
@MainActor
enum PersonaTranslationTrigger {

    /// 给某个人格踢一次翻译。没有配置 / 关掉开关 / 已经缓存 → 什么都不做。
    static func kick(_ env: AppEnvironment, personaId: String?) {
        guard let personaId,
              env.currentSettings.autoTranslatePersona,
              let resolved = env.resolvedChatConfig() else { return }
        let service = PersonaTranslationService.shared(
            stores: env.stores,
            settings: env.settings
        )
        service.kickTranslation(
            personaId: personaId,
            lang: PersonaTranslationLanguage.current(),
            config: CompletionConfig(entity: resolved.config, apiKey: resolved.apiKey),
            model: resolved.model
        )
    }

    /// 会话 → 人格 → 触发（聊天页用）。
    static func kick(_ env: AppEnvironment, sessionId: String) {
        guard let session = (try? env.stores.sessions.byId(sessionId)) ?? nil else { return }
        kick(env, personaId: session.personaId)
    }
}
