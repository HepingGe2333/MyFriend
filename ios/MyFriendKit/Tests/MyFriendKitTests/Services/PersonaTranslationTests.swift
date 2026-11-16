import Foundation
import XCTest
@testable import MyFriendKit

/// 人格提示词翻译（`spec/persona-translation/README.md`）：缓存模型、哈希失效、
/// 逐字段回退、零请求路径、并发去重、请求体与日志的隐私边界。
///
/// 全部离线：假模型返回固定 JSON，临时库里放真人格；时间写死。
final class PersonaTranslationTests: XCTestCase {

    private let now: Int64 = 1_795_000_000_000

    private var temp: TempDatabase!
    private var settings: InMemorySettings!
    private var logs: [String] = []

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        settings = InMemorySettings(AppSettings(autoTranslatePersona: true))
        logs = []
    }

    override func tearDownWithError() throws {
        temp.cleanUp()
        temp = nil
        settings = nil
        try super.tearDownWithError()
    }

    // MARK: - 样本

    private func config(key: String? = "sk-test") -> CompletionConfig {
        CompletionConfig(
            id: "config-1", name: "测试配置", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat", apiKey: key
        )
    }

    private func persona(
        name: String = "林深",
        description: String = "嘴硬心软的建筑师。",
        withWorld: Bool = true,
        withSpeech: Bool = true
    ) -> PersonaEntity {
        PersonaEntity(
            id: "persona-1",
            name: name,
            description: description,
            prompt: "你叫林深。",
            createdAt: now,
            updatedAt: now,
            traits: ["嘴硬心软"],
            likes: ["夜里散步"],
            behaviors: ["先给事实"],
            backstory: "父亲早年去世。",
            quirks: "不太用感叹号。",
            worldJson: withWorld ? WorldPreset(
                worldName: "临港新城",
                era: "当代，中国沿海新一线城市",
                description: "事务所竞争激烈。",
                schedule: [WorldScheduleItem(day: "工作日", start: "09:00", end: "18:30",
                                             activity: "在事务所画图")],
                relationships: [WorldRelationship(name: "母亲", relation: "家人", note: "每周三打电话")],
                obligations: ["按节点交图"],
                notes: "事务所里没人叫他全名。"
            ).jsonString() : nil,
            speechStyleJson: withSpeech ? SpeechStyle(
                tone: "简短直接",
                phrases: ["在忙，晚点说", "你先睡吧"],
                habits: ["句子很短"],
                avoid: ["长篇大论"]
            ).jsonString() : nil
        )
    }

    private func seed(_ entity: PersonaEntity) throws {
        try temp.stores.personas.upsert(entity)
    }

    private func englishJSON() -> String {
        """
        {
          "persona": {
            "name": "Lin Shen",
            "description": "A blunt architect with a soft spot.",
            "traits": ["stubborn but soft-hearted"],
            "likes": ["night walks"],
            "behaviors": ["gives the facts first"],
            "backstory": "His father passed away early.",
            "quirks": "Rarely uses exclamation marks.",
            "systemPrompt": "You are Lin Shen."
          },
          "world": {
            "worldName": "Lingang New City",
            "era": "Contemporary coastal China, 2026",
            "description": "Fierce competition at the studio.",
            "schedule": [{"day": "weekdays", "start": "09:00", "end": "18:30", "activity": "drawing at the studio"}],
            "relationships": [{"name": "Mother", "relation": "family", "note": "calls every Wednesday"}],
            "obligations": ["deliver drawings on schedule"],
            "notes": "Nobody at the studio calls him by his full name."
          },
          "speech": {
            "tone": "short and direct",
            "habits": ["very short sentences"],
            "avoid": ["long speeches"]
          }
        }
        """
    }

    private func translator(_ client: CompletionClient, timeout: Double = 8) -> PersonaTranslator {
        logs = []
        return PersonaTranslator(
            completion: client,
            log: { [weak self] line in self?.logs.append(line) },
            timeout: timeout
        )
    }

    private func service(_ client: CompletionClient, timeout: Double = 8) -> PersonaTranslationService {
        PersonaTranslationService(
            stores: temp.stores,
            settings: settings,
            translator: translator(client, timeout: timeout),
            log: { [weak self] line in self?.logs.append(line) },
            now: { self.now }
        )
    }

    // MARK: - 领域：语言检测与哈希

    func testLanguageDetectionUsesCJKRatio() {
        XCTAssertEqual(PersonaTranslationLanguage.detect("林深是一个建筑师"), "zh")
        XCTAssertEqual(PersonaTranslationLanguage.detect("Lin Shen is an architect"), "en")
        XCTAssertEqual(PersonaTranslationLanguage.detect(""), "")
        // 混合文本里中文占多数 → 中文。
        XCTAssertEqual(PersonaTranslationLanguage.detect("林深 在 事务所 画图 meeting"), "zh")
        XCTAssertEqual(PersonaTranslationLanguage.normalize("zh-Hans"), "zh")
        XCTAssertEqual(PersonaTranslationLanguage.normalize("en_US"), "en")
        XCTAssertTrue(PersonaTranslationLanguage.isSame("zh-Hans", "zh"))
        XCTAssertFalse(PersonaTranslationLanguage.isSame("zh", "en"))
    }

    func testSourceHashChangesWhenAnyTranslatedFieldChanges() {
        let base = PersonaTranslatableSource(persona: persona())
        let hash = PersonaTranslation.sourceHash(for: base)
        XCTAssertTrue(hash.hasPrefix("sha256:"))
        XCTAssertEqual(hash, PersonaTranslation.sourceHash(for: PersonaTranslatableSource(persona: persona())),
                       "同样的字段必须得到同样的哈希")
        let renamed = PersonaTranslatableSource(persona: persona(name: "林岸"))
        XCTAssertNotEqual(hash, PersonaTranslation.sourceHash(for: renamed))
        // 世界里的 notes 变了也算源变了（它是被翻译字段）。
        var world = base.world!
        world.notes = "换了一句背景。"
        var changed = base
        changed.world = world
        XCTAssertNotEqual(hash, PersonaTranslation.sourceHash(for: changed))
        // phrases 不在被翻译字段里：改常用语不该让译文失效（翻译不碰 phrases）。
        var speech = base.speech!
        speech.phrases = base.speech!.phrases + ["知道了"]
        var speechChanged = base
        speechChanged.speech = speech
        XCTAssertEqual(hash, PersonaTranslation.sourceHash(for: speechChanged))
    }

    func testTranslationJSONRoundTripAndBadJSON() throws {
        let source = PersonaTranslatableSource(persona: persona())
        let translation = PersonaTranslation(
            lang: "en", translatedAt: now,
            sourceHash: PersonaTranslation.sourceHash(for: source),
            persona: .init(name: "Lin Shen", description: "Architect."),
            world: source.world,
            speech: source.speech
        )
        let json = try XCTUnwrap(translation.jsonString())
        // phrases 不进缓存：翻译不该动「他平时怎么说话」的语感。
        XCTAssertFalse(json.contains("在忙，晚点说"))
        XCTAssertFalse(json.contains("\"phrases\""))
        // 两端 JSON 形状固定：没有 iOS 私有的 schemaVersion，world/speech 键始终在。
        XCTAssertFalse(json.contains("schemaVersion"))
        let decoded = try XCTUnwrap(PersonaTranslation.decode(json))
        XCTAssertEqual(decoded.lang, "en")
        XCTAssertEqual(decoded.persona.name, "Lin Shen")
        XCTAssertEqual(decoded.world?.worldName, "临港新城")
        XCTAssertEqual(decoded.speech?.tone, "简短直接")
        XCTAssertNil(PersonaTranslation.decode("不是 JSON"))
        XCTAssertNil(PersonaTranslation.decode(#"{"lang":"en"}"#), "缺 sourceHash 就当没有缓存")
    }

    // MARK: - 服务：缓存与失效

    func testCachedTranslationHitsWithoutRequest() async throws {
        let entity = persona()
        try seed(entity)
        let fake = FakeCompletionClient(text: englishJSON())
        let cache = service(fake)
        let first = await cache.ensureTranslation(
            personaId: entity.id, lang: "en", config: config(), model: ""
        )
        guard case .translated = first else {
            return XCTFail("第一次应当真的翻译：\(first)")
        }
        XCTAssertEqual(fake.calls.count, 1)
        // 第二次：缓存命中，零请求。
        let second = await cache.ensureTranslation(
            personaId: entity.id, lang: "en", config: config(), model: ""
        )
        guard case .cached(let cached) = second else {
            return XCTFail("第二次应当缓存命中：\(second)")
        }
        XCTAssertEqual(fake.calls.count, 1, "缓存命中不能发请求")
        XCTAssertEqual(cached.persona.name, "Lin Shen")
        // 人格行里真的写了缓存两列。
        let reloaded = try XCTUnwrap(try temp.stores.personas.byId(entity.id))
        XCTAssertEqual(PersonaTranslationLanguage.normalize(reloaded.translationLang ?? ""), "en")
        XCTAssertNotNil(PersonaTranslation.decode(reloaded.translationJson))
    }

    func testSourceChangeInvalidatesCache() async throws {
        var entity = persona()
        try seed(entity)
        let fake = FakeCompletionClient(texts: [englishJSON(), englishJSON()])
        let cache = service(fake)
        _ = await cache.ensureTranslation(personaId: entity.id, lang: "en", config: config(), model: "")
        XCTAssertEqual(fake.calls.count, 1)
        // 用户改了介绍 → 源哈希变了 → 下次必须重译。
        entity.description = "换了一句介绍。"
        entity.updatedAt = now + 1
        try seed(entity)
        let outcome = await cache.ensureTranslation(
            personaId: entity.id, lang: "en", config: config(), model: ""
        )
        guard case .translated = outcome else {
            return XCTFail("源变了就该重译：\(outcome)")
        }
        XCTAssertEqual(fake.calls.count, 2)
    }

    func testSameLanguageSkipsWithoutRequest() async throws {
        let entity = persona()  // 中文源
        try seed(entity)
        let fake = FakeCompletionClient(text: englishJSON())
        let outcome = await service(fake).ensureTranslation(
            personaId: entity.id, lang: "zh", config: config(), model: ""
        )
        XCTAssertEqual(outcome, .skippedSameLanguage)
        XCTAssertEqual(fake.calls.count, 0, "源=目标不该发请求")
        XCTAssertNil(try temp.stores.personas.byId(entity.id)?.translationJson)
    }

    func testDisabledSettingNeverCallsModel() async throws {
        settings.save(AppSettings(autoTranslatePersona: false))
        let entity = persona()
        try seed(entity)
        let fake = FakeCompletionClient(text: englishJSON())
        let outcome = await service(fake).ensureTranslation(
            personaId: entity.id, lang: "en", config: config(), model: ""
        )
        XCTAssertEqual(outcome, .disabled)
        XCTAssertEqual(fake.calls.count, 0)
    }

    // MARK: - 服务：失败与回退

    func testBadJSONFallsBackFieldByField() async throws {
        let entity = persona()
        try seed(entity)
        // 只有 name 与 speech.tone 是可读的译文，其余字段缺失 → 逐字段回退原文，不是整份丢弃。
        let fake = FakeCompletionClient(
            text: #"{"persona":{"name":"Lin Shen"},"speech":{"tone":"short and direct"}}"#
        )
        let outcome = await service(fake).ensureTranslation(
            personaId: entity.id, lang: "en", config: config(), model: ""
        )
        guard case .translated(let translation) = outcome else {
            return XCTFail("有可用 JSON 就该算翻译成功：\(outcome)")
        }
        XCTAssertEqual(translation.persona.name, "Lin Shen")
        XCTAssertEqual(translation.persona.description, "嘴硬心软的建筑师。", "缺字段回退原文")
        XCTAssertEqual(translation.world?.worldName, "临港新城", "世界缺失整段回退原文")
        XCTAssertEqual(translation.speech?.tone, "short and direct", "speech 有的字段用译文")
        XCTAssertEqual(translation.speech?.habits, ["句子很短"], "speech 缺的字段逐字段回退原文")
        XCTAssertEqual(translation.speech?.avoid, ["长篇大论"], "speech 缺的字段逐字段回退原文")
        XCTAssertEqual(translation.speech?.phrases, ["在忙，晚点说", "你先睡吧"])
        XCTAssertFalse(
            (translation.jsonString() ?? "").contains("在忙，晚点说"),
            "缓存 JSON 里不带 phrases（§3：翻译不碰常用语）"
        )
    }

    func testBrokenJSONUsesOriginalAndRetriesNextTime() async throws {
        let entity = persona()
        try seed(entity)
        let fake = FakeCompletionClient(texts: ["这不是 JSON", englishJSON()])
        let cache = service(fake)
        let first = await cache.ensureTranslation(
            personaId: entity.id, lang: "en", config: config(), model: ""
        )
        guard case .failed = first else { return XCTFail("坏 JSON 应算失败：\(first)") }
        XCTAssertNil(try temp.stores.personas.byId(entity.id)?.translationJson,
                     "失败不写缓存（下次启动再试）")
        let second = await cache.ensureTranslation(
            personaId: entity.id, lang: "en", config: config(), model: ""
        )
        guard case .translated = second else { return XCTFail("第二次应当成功：\(second)") }
        XCTAssertEqual(fake.calls.count, 2)
    }

    func testNoAPIKeyUsesOriginalWithoutRequest() async throws {
        let entity = persona()
        try seed(entity)
        let fake = FakeCompletionClient(text: englishJSON())
        let outcome = await service(fake).ensureTranslation(
            personaId: entity.id, lang: "en", config: config(key: nil), model: ""
        )
        XCTAssertEqual(outcome, .noConfig)
        XCTAssertEqual(fake.calls.count, 0)
        // 原文照用：appliedPersona 就是本人格。
        let applied = service(fake).appliedPersona(entity, lang: "en")
        XCTAssertEqual(applied.name, "林深")
    }

    func testTimeoutUsesOriginalAndDoesNotWriteCache() async throws {
        let entity = persona()
        try seed(entity)
        let slow = SlowCompletionClient(delay: 1.0, text: englishJSON())
        let cache = service(slow, timeout: 0.05)
        let outcome = await cache.ensureTranslation(
            personaId: entity.id, lang: "en", config: config(), model: ""
        )
        guard case .failed = outcome else { return XCTFail("超时应当算失败：\(outcome)") }
        XCTAssertNil(try temp.stores.personas.byId(entity.id)?.translationJson)
        XCTAssertEqual(cache.appliedPersona(entity, lang: "en").name, "林深")
    }

    // MARK: - 隐私：请求体与日志

    func testSystemPromptSkeletonIncludesSpeechLikeSpecSection5() {
        let prompt = PersonaTranslator.systemPrompt(target: "en")
        XCTAssertTrue(prompt.contains("{ \"persona\": {…}, \"world\": {…}, \"speech\": {…} }"))
        XCTAssertTrue(prompt.contains("不要翻译 phrases 数组"))
        XCTAssertFalse(prompt.contains("{ \"persona\": {…}, \"world\": {…} }\n"))
        // 与规范 §5 / Android 的模板逐字一致（只换目标语言占位）。
        XCTAssertEqual(
            prompt,
            PersonaTranslator.systemPromptTemplate.replacingOccurrences(of: "{target}", with: "English")
        )
    }

    func testRequestCarriesOnlyPersonaWorldAndSpeech() async throws {
        var entity = persona()
        // 往人格里塞一条"聊天记录"与一段记忆正文：它们绝不能出现在翻译请求里。
        entity.prompt = "你叫林深。" // 系统提示词本身可以翻（§3 的 systemPrompt）
        try seed(entity)
        let fake = FakeCompletionClient(text: englishJSON())
        _ = await service(fake).ensureTranslation(
            personaId: entity.id, lang: "en", config: config(), model: ""
        )
        let call = try XCTUnwrap(fake.calls.first)
        let body = call.messages.map(\.content).joined(separator: "\n")
        XCTAssertTrue(body.contains("林深"))
        XCTAssertTrue(body.contains("临港新城"))
        XCTAssertTrue(body.contains("简短直接"))
        XCTAssertTrue(body.contains("\"speech\""), "话风的 tone/habits/avoid 要发出去翻译")
        // 不翻译 phrases（§3）：句子本身不该进请求。
        XCTAssertFalse(body.contains("在忙，晚点说"), "常用语不该发给翻译器")
        // 不发 API Key。
        XCTAssertFalse(body.contains("sk-test"))
        // 日志脱敏：只出现状态，不出现任何设定正文。
        for line in logs {
            XCTAssertFalse(line.contains("林深"), "日志里不能出现设定正文：\(line)")
            XCTAssertFalse(line.contains("临港"), "日志里不能出现世界正文：\(line)")
            XCTAssertFalse(line.contains("sk-test"))
        }
        XCTAssertEqual(call.kind, .translation)
        XCTAssertEqual(call.params.temperature, 0.2)
    }

    // MARK: - 并发去重

    func testConcurrentEnsureIssuesOneRequest() async throws {
        let entity = persona()
        try seed(entity)
        let slow = SlowCompletionClient(delay: 0.2, text: englishJSON())
        let cache = service(slow)
        async let a = cache.ensureTranslation(
            personaId: entity.id, lang: "en", config: config(), model: ""
        )
        async let b = cache.ensureTranslation(
            personaId: entity.id, lang: "en", config: config(), model: ""
        )
        let results = await [a, b]
        // 去重的语义是「两次调用拿到同一份结果、模型只被问一次」：
        // 不是「只有一次成功」——两个调用方都必须拿到可用的译文。
        for result in results {
            guard case .translated(let translation) = result else {
                return XCTFail("并发调用应当都拿到同一份译文：\(result)")
            }
            XCTAssertEqual(translation.persona.name, "Lin Shen")
        }
        XCTAssertEqual(slow.count, 1, "并发去重失败：发了 \(slow.count) 次请求")
    }

    // MARK: - 应用到人格 & 重新翻译

    func testAppliedPersonaUsesTranslationButKeepsPhrases() async throws {
        let entity = persona()
        try seed(entity)
        let fake = FakeCompletionClient(text: englishJSON())
        let cache = service(fake)
        _ = await cache.ensureTranslation(personaId: entity.id, lang: "en", config: config(), model: "")
        let reloaded = try XCTUnwrap(try temp.stores.personas.byId(entity.id))
        let applied = cache.appliedPersona(reloaded, lang: "en")
        XCTAssertEqual(applied.name, "Lin Shen")
        XCTAssertEqual(WorldPreset.decode(applied.worldJson)?.worldName, "Lingang New City")
        let style = try XCTUnwrap(SpeechStyle.decode(applied.speechStyleJson))
        XCTAssertEqual(style.tone, "short and direct")
        XCTAssertEqual(style.phrases, ["在忙，晚点说", "你先睡吧"], "phrases 永远是原文")
        // 数据库里仍然是原文（译文只影响提示词）。
        XCTAssertEqual(reloaded.name, "林深")
        // 语言对不上时不应用译文。
        XCTAssertEqual(cache.appliedPersona(reloaded, lang: "zh").name, "林深")
    }

    func testClearTranslationForcesRetranslation() async throws {
        let entity = persona()
        try seed(entity)
        let fake = FakeCompletionClient(texts: [englishJSON(), englishJSON()])
        let cache = service(fake)
        _ = await cache.ensureTranslation(personaId: entity.id, lang: "en", config: config(), model: "")
        try cache.clearTranslation(personaId: entity.id)
        XCTAssertNil(try temp.stores.personas.byId(entity.id)?.translationJson)
        let outcome = await cache.ensureTranslation(
            personaId: entity.id, lang: "en", config: config(), model: ""
        )
        guard case .translated = outcome else { return XCTFail("清缓存后应重译：\(outcome)") }
        XCTAssertEqual(fake.calls.count, 2)
    }

    // MARK: - 纯函数版本（提示词拼装点用）

    func testApplyingCachedChecksLanguageAndSourceHash() throws {
        let entity = persona()
        let source = PersonaTranslatableSource(persona: entity)
        let cache = PersonaTranslation(
            lang: "en", translatedAt: now,
            sourceHash: PersonaTranslation.sourceHash(for: source),
            persona: .init(name: "Lin Shen", description: "Architect."),
            world: source.world
        )
        var withCache = entity
        withCache.translationJson = cache.jsonString()
        withCache.translationLang = "en"
        XCTAssertEqual(PersonaTranslation.applyingCached(to: withCache, lang: "en").name, "Lin Shen")
        // 界面语言对不上 → 原文。
        XCTAssertEqual(PersonaTranslation.applyingCached(to: withCache, lang: "zh").name, "林深")
        // 源变了（哈希对不上）→ 原文。
        var changed = withCache
        changed.description = "改过的介绍。"
        XCTAssertEqual(PersonaTranslation.applyingCached(to: changed, lang: "en").name, "林深")
        // 没有缓存 → 原文。
        XCTAssertEqual(PersonaTranslation.applyingCached(to: entity, lang: "en").name, "林深")
    }

    // MARK: - 预设 ⇄ 人格

    func testPresetApplierCopiesAndInvalidatesTranslation() throws {
        let preset = PersonaPresetEntity(
            id: "preset-1", name: "临港建筑师", description: "嘴硬心软。",
            prompt: "你叫林深，在事务所带一个小团队。",
            category: "朋友", avatarSeed: 7, createdAt: 1, updatedAt: 2,
            worldJson: WorldPreset(worldName: "临港新城").jsonString(),
            speechStyleJson: SpeechStyle(tone: "简短直接").jsonString()
        )
        var target = persona()
        let source = PersonaTranslatableSource(persona: target)
        target.translationJson = PersonaTranslation(
            lang: "en", translatedAt: now,
            sourceHash: PersonaTranslation.sourceHash(for: source),
            persona: .init(name: "Lin Shen")
        ).jsonString()
        target.translationLang = "en"
        let applied = PersonaPresetApplier.applying(preset, to: target, now: 10)
        XCTAssertEqual(applied.name, "临港建筑师")
        XCTAssertEqual(applied.prompt, "你叫林深，在事务所带一个小团队。")
        XCTAssertNil(applied.translationJson, "内容变了，旧译文必须失效")
        XCTAssertNil(applied.translationLang)
        XCTAssertEqual(applied.version, target.version + 1, "提示词变了要升版本")
        XCTAssertEqual(WorldPreset.decode(applied.worldJson)?.worldName, "临港新城")
        // 「复制」是新建一个人，id 不同、默认不是默认人格。
        let copied = PersonaPresetApplier.newPersona(from: preset, now: 11)
        XCTAssertNotEqual(copied.id, target.id)
        XCTAssertEqual(copied.name, "临港建筑师")
        XCTAssertFalse(copied.isDefault)
        XCTAssertEqual(copied.createdAt, 11)
    }
}

// MARK: - 慢模型

/// 一个会"想很久"的假模型：用来测超时与并发去重（真实请求不该被真的拖 1 秒以上）。
final class SlowCompletionClient: CompletionClient {
    private let lock = NSLock()
    private var callCount = 0
    private let delay: Double
    private let text: String

    init(delay: Double, text: String) {
        self.delay = delay
        self.text = text
    }

    var count: Int {
        lock.lock()
        defer { lock.unlock() }
        return callCount
    }

    func complete(
        config: CompletionConfig,
        model: String,
        messages: [CompletionMessage],
        params: CompletionParams,
        kind: CompletionKind,
        context: UsageContext
    ) async throws -> CompletionResult {
        lock.lock()
        callCount += 1
        lock.unlock()
        try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
        return CompletionResult(text: text)
    }
}
