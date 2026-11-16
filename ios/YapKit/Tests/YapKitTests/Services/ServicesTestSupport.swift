import Foundation
import XCTest
@testable import YapKit

// ---------------------------------------------------------------------------
// 服务层测试的共用底座。
//
// 三条原则：
// 1. **真库**：用临时文件 SQLite（不是 :memory:），因为补建分页、事务回滚、导入替换
//    这些行为只有在「关掉再打开还在」的库上才看得出差别；
// 2. **假模型**：CompletionClient 的假实现返回固定 JSON，测试不联网、不读环境变量；
// 3. **假时钟**：所有服务都接受 `now` 闭包，时间点写死，断言不跟真实时间赛跑。
// ---------------------------------------------------------------------------

/// 一个用完即弃的临时库。
final class TempDatabase {
    let directory: URL
    let databaseURL: URL
    let stores: DatabaseStores

    init() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-ios-services-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        databaseURL = directory.appendingPathComponent("db.sqlite")
        stores = try DatabaseStores.open(path: databaseURL.path)
    }

    func cleanUp() {
        stores.database.close()
        try? FileManager.default.removeItem(at: directory)
    }
}

/// 一次模型调用的记录：用来断言「该不该调、调了几次、喂了什么」。
struct FakeCompletionCall {
    var config: CompletionConfig
    var model: String
    var messages: [CompletionMessage]
    var params: CompletionParams
    var kind: CompletionKind
}

/// 假模型：按 queue 依次返回，队列空了就返回 fallback（默认「什么都没做」）。
final class FakeCompletionClient: CompletionClient {
    private let lock = NSLock()
    private var queue: [Result<CompletionResult, Error>]
    private(set) var calls: [FakeCompletionCall] = []
    /// 每次调用「算在谁头上」（1.9 的 `api_usage.sessionId` 靠它）。
    private(set) var contexts: [UsageContext] = []
    var fallback: Result<CompletionResult, Error>

    init(_ responses: [Result<CompletionResult, Error>] = [],
         fallback: Result<CompletionResult, Error> = .success(CompletionResult(text: #"{"thought":"","actions":[{"type":"nothing"}]}"#))) {
        self.queue = responses
        self.fallback = fallback
    }

    convenience init(texts: [String]) {
        self.init(texts.map { .success(CompletionResult(text: $0)) })
    }

    convenience init(text: String) {
        self.init(texts: [text])
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
        calls.append(
            FakeCompletionCall(config: config, model: model, messages: messages, params: params, kind: kind)
        )
        contexts.append(context)
        let next = queue.isEmpty ? fallback : queue.removeFirst()
        lock.unlock()
        switch next {
        case .success(let result): return result
        case .failure(let error): throw error
        }
    }

    var callCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return calls.count
    }
}

// MARK: - 样例数据

enum TestData {

    static let now: Int64 = 1_794_500_000_000   // 2026-11-14 前后，固定值

    /// 测试人格。
    ///
    /// `sleepEnabled` 默认「只有给了作息才睡」：1.9 里默认人格是「会睡觉」的，
    /// 而测试用的是写死的时钟（当地 00:13），那正好落在默认作息 23:30–08:00 里。
    /// 如果让每个 fixture 都默认睡着，所有和「回不回话」有关的测试都会变成
    /// 「取决于测试机时区」的假测试 —— 这里显式把睡眠交给用例自己决定。
    static func persona(
        id: String = "persona-1",
        name: String = "林岸",
        isDefault: Bool = true,
        quietSleepMinutes: Int? = nil,
        quietWakeMinutes: Int? = nil,
        sleepEnabled: Bool? = nil,
        mood: String? = nil,
        moodIntensity: Int? = nil
    ) -> PersonaEntity {
        PersonaEntity(
            id: id,
            name: name,
            description: "住在海边的人，说话慢",
            avatarSeed: 42,
            prompt: "你叫林岸，住在海边，说话慢一点。",
            category: "伴侣",
            version: 3,
            defaultApiConfigId: "config-1",
            isDefault: isDefault,
            createdAt: now - 30 * 86_400_000,
            updatedAt: now - 3_600_000,
            quietSleepMinutes: quietSleepMinutes,
            quietWakeMinutes: quietWakeMinutes,
            mood: mood,
            moodIntensity: moodIntensity,
            moodReason: mood == nil ? nil : "今天有点累",
            moodUpdatedAt: mood == nil ? nil : now - 600_000,
            sleepEnabled: sleepEnabled ?? (quietSleepMinutes != nil)
        )
    }

    static func session(
        id: String = "session-1",
        personaId: String = "persona-1",
        lastMessageAt: Int64? = now - 3 * 3_600_000,
        summary: String? = nil,
        summarizedUpTo: Int64? = nil
    ) -> ChatSessionEntity {
        ChatSessionEntity(
            id: id,
            personaId: personaId,
            apiConfigId: "config-1",
            model: "deepseek-chat",
            title: "下个月去哈尔滨",
            lastMessagePreview: "那我等你回来",
            lastMessageAt: lastMessageAt,
            createdAt: now - 10 * 86_400_000,
            updatedAt: lastMessageAt ?? now,
            lastPersonaVersion: 3,
            lastMemoryExtractedCount: 0,
            historySummary: summary,
            historySummarizedUpTo: summarizedUpTo,
            historySummaryAt: summarizedUpTo == nil ? nil : summarizedUpTo! + 1000
        )
    }

    static func message(
        id: String,
        sessionId: String = "session-1",
        personaId: String = "persona-1",
        role: MessageRole,
        content: String,
        createdAt: Int64
    ) -> MessageEntity {
        MessageEntity(
            id: id,
            sessionId: sessionId,
            personaId: personaId,
            role: role,
            content: content,
            createdAt: createdAt,
            updatedAt: createdAt,
            status: .complete
        )
    }

    static func memory(
        id: String,
        personaId: String = "persona-1",
        content: String,
        drawer: String = "USER",
        category: MemoryCategory = .fact,
        importance: Int = 4,
        pinned: Bool = false,
        status: String = "ACTIVE",
        strength: Double = 0.9,
        useCount: Int = 1,
        lastUsedAt: Int64? = nil,
        createdAt: Int64? = nil,
        tags: String? = nil,
        happenedAt: Int64? = nil
    ) -> MemoryEntity {
        MemoryEntity(
            id: id,
            personaId: personaId,
            content: content,
            category: category,
            importance: importance,
            pinned: pinned,
            createdAt: createdAt ?? now - 20 * 86_400_000,
            updatedAt: createdAt ?? now - 20 * 86_400_000,
            lastUsedAt: lastUsedAt ?? 0,
            useCount: useCount,
            drawer: drawer,
            status: status,
            strength: strength,
            tags: tags,
            happenedAt: happenedAt
        )
    }

    static func config() -> CompletionConfig {
        CompletionConfig(
            id: "config-1",
            name: "测试配置",
            provider: .openai,
            baseUrl: "https://api.example.com",
            model: "deepseek-chat",
            apiKey: "sk-test"
        )
    }
}

// MARK: - 断言辅助

extension XCTestCase {
    /// 断言闭包抛出的是「服务层的可读错误」，并返回那句话（给「失败也要有中文原因」用）。
    func failureMessage(_ body: () throws -> Void) -> String? {
        do {
            try body()
            return nil
        } catch {
            return error.localizedDescription
        }
    }
}
