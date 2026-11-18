import Foundation
import XCTest
@testable import YapKit

/// 0.13 `listen(audioId?)` / `watch(videoId?)`：
/// 默认取最近一条对应媒体、结果里**真的**带多模态内容、没有媒体时明确说没有。
final class AudioVideoToolTests: XCTestCase {

    private var temp: TempDatabase!
    private var store: AudioVideoStore!
    private var frames: FakeVideoFrames!
    private var executor: ToolExecutor!
    private var mediaRoot: URL!

    private let personaId = "persona-1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        StubURLProtocol.reset()
        temp = try TempDatabase()
        mediaRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent("yap-av-tools-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: mediaRoot, withIntermediateDirectories: true)
        store = AudioVideoStore(
            audioDirectory: mediaRoot.appendingPathComponent("audio", isDirectory: true),
            videoDirectory: mediaRoot.appendingPathComponent("video", isDirectory: true)
        )
        frames = FakeVideoFrames()

        try temp.stores.personas.upsert(TestData.persona(id: personaId))
        try temp.stores.sessions.upsert(TestData.session(id: "session-1", personaId: personaId))
        try temp.stores.apiConfigs.upsert(ApiConfigEntity(
            id: "config-1", name: "测试配置", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat"
        ))
        let memory = MemoryRepository(
            stores: temp.stores, vectorIndex: MemoryVectorIndex(stores: temp.stores)
        )
        executor = ToolExecutor(
            stores: temp.stores,
            apiKeys: ApiKeyStore(backend: InMemoryKeychain()),
            memory: memory,
            recallService: RecallService(stores: temp.stores, memoryRepository: memory),
            mood: MoodRepository(stores: temp.stores, now: { self.now }),
            personaCards: PersonaCardRepository(stores: temp.stores),
            intents: AgentIntentSupport(
                stores: temp.stores, ticks: AgentTickStore(database: temp.stores.database)
            ),
            echoes: InMemoryMemoryEchoStore(),
            audioVideo: store,
            videoFrames: frames,
            webTools: WebToolsClient(session: StubURLProtocol.makeSession()),
            now: { self.now }
        )
    }

    override func tearDownWithError() throws {
        StubURLProtocol.reset()
        try? FileManager.default.removeItem(at: mediaRoot)
        temp?.cleanUp()
        temp = nil
        store = nil
        frames = nil
        executor = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private func run(_ name: String, _ arguments: [String: Any]) async -> ToolResult {
        let json = (try? JSONSerialization.data(withJSONObject: arguments))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        let invocation = ToolInvocation(id: "call-1", name: name, argumentsJson: json)
        let persona = try! temp.stores.personas.byId(personaId)!
        return await executor.run(invocation, persona: persona, sessionId: "session-1")
    }

    @discardableResult
    private func seedMessage(
        _ attachments: [MessageAttachment],
        content: String = "",
        at offset: Int64
    ) throws -> MessageEntity {
        let message = MessageEntity(
            id: "m-\(offset)", sessionId: "session-1", personaId: personaId, role: .user,
            content: content, createdAt: now + offset, updatedAt: now + offset,
            attachmentsJson: AttachmentsCodec.encode(attachments)
        )
        try temp.stores.messages.upsert(message)
        return message
    }

    private func makeAudio(_ durationMs: Int = 12_000) throws -> MessageAttachment {
        let data = Data((0..<1_024).map { UInt8($0 % 251) })
        return try store.saveAudio(data: data, durationMs: durationMs)
    }

    private func makeVideo(_ durationMs: Int = 12_000) throws -> MessageAttachment {
        let source = mediaRoot.appendingPathComponent("picker-\(UUID().uuidString).mp4")
        try Data(repeating: 7, count: 2_048).write(to: source)
        return try store.importVideo(at: source, durationMs: durationMs)
    }

    // MARK: - listen

    func testListenDefaultsToMostRecentAudioAndFeedsRealAudioPart() async throws {
        let older = try makeAudio(5_000)
        let newest = try makeAudio(12_000)
        try seedMessage([older], at: 1)
        try seedMessage([newest], at: 2)

        let result = await run("listen", [:])
        XCTAssertTrue(result.ok)
        XCTAssertEqual(result.traceTitle, "听了一遍")
        XCTAssertTrue(result.contentForModel.contains("我听了一遍"))
        XCTAssertFalse(result.contentForModel.contains("转写："), "不能伪造转写")
        let parts = try XCTUnwrap(result.mediaParts)
        XCTAssertEqual(parts.audio.count, 1)
        XCTAssertEqual(parts.audio.first?.format, "m4a")
        let decoded = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(parts.audio.first?.base64)))
        XCTAssertEqual(decoded, store.data(for: newest), "交给模型的必须是那段语音的原始字节")
        XCTAssertTrue(parts.note.isEmpty)
    }

    func testListenWithExplicitAudioIdPicksThatOne() async throws {
        let older = try makeAudio(5_000)
        let newest = try makeAudio(12_000)
        try seedMessage([older], at: 1)
        try seedMessage([newest], at: 2)

        let result = await run("listen", ["audioId": older.mediaRefId ?? ""])
        XCTAssertTrue(result.ok)
        let parts = try XCTUnwrap(result.mediaParts)
        let decoded = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(parts.audio.first?.base64)))
        XCTAssertEqual(decoded, store.data(for: older))
    }

    func testListenWithoutMediaFailsLoudly() async throws {
        let result = await run("listen", [:])
        XCTAssertFalse(result.ok)
        XCTAssertEqual(result.errorMessage, "这里还没有语音可以听")
        XCTAssertNil(result.mediaParts)
        XCTAssertTrue(result.contentForModel.contains("这次调用没成功"))
    }

    func testListenWithUnknownIdSaysNotFound() async throws {
        try seedMessage([try makeAudio()], at: 1)
        let result = await run("listen", ["audioId": "audio-nope"])
        XCTAssertFalse(result.ok)
        XCTAssertEqual(result.errorMessage, "没找到这段语音（id 可能不对）")
    }

    // MARK: - watch

    func testWatchExtractsFramesAndReportsCountAndDuration() async throws {
        let video = try makeVideo(12_000)
        try seedMessage([video], at: 1)
        frames.framesToReturn = [Data([0xFF, 0xD8, 0x01]), Data([0xFF, 0xD8, 0x02])]

        let result = await run("watch", [:])
        XCTAssertTrue(result.ok)
        XCTAssertEqual(result.traceTitle, "看了几帧")
        XCTAssertTrue(result.contentForModel.contains("我看了这段视频的 2 帧"))
        XCTAssertTrue(result.contentForModel.contains("视频：2 帧，时长 12.0 秒"))
        let parts = try XCTUnwrap(result.mediaParts)
        XCTAssertEqual(parts.images.count, 2)
        XCTAssertTrue(parts.images.allSatisfy { $0.hasPrefix("data:image/jpeg;base64,") })
        // 帧数/时长在回灌正文里（`contentForModel`），不再往 parts 里重复一份。
        XCTAssertTrue(parts.note.isEmpty)
        XCTAssertEqual(frames.calls.count, 1)
        XCTAssertEqual(frames.calls.first?.count, VideoFramePolicy.frameCount(durationSeconds: 12))
        XCTAssertEqual(frames.calls.first?.maxPixel, VideoFramePolicy.maxPixel)
    }

    func testWatchWithoutMediaFailsLoudly() async throws {
        let result = await run("watch", [:])
        XCTAssertFalse(result.ok)
        XCTAssertEqual(result.errorMessage, "这里还没有视频可以看")
        XCTAssertNil(result.mediaParts)
    }

    func testWatchWithUnknownIdSaysNotFound() async throws {
        try seedMessage([try makeVideo()], at: 1)
        let result = await run("watch", ["videoId": "video-nope"])
        XCTAssertFalse(result.ok)
        XCTAssertEqual(result.errorMessage, "没找到这段视频（id 可能不对）")
    }
}

/// 假抽帧器：不碰 AVFoundation（测试机上没有视频样片也能跑）。
private final class FakeVideoFrames: VideoFrameExtracting {
    var framesToReturn: [Data] = [Data([0xFF, 0xD8])]
    private(set) var calls: [(url: URL, count: Int, maxPixel: Int)] = []

    func frames(of url: URL, count: Int, maxPixel: Int) async -> [Data] {
        calls.append((url, count, maxPixel))
        return Array(framesToReturn.prefix(max(count, 0)))
    }

    func firstFrameJPEG(of url: URL, maxPixel: Int) async -> Data? {
        framesToReturn.first
    }
}
