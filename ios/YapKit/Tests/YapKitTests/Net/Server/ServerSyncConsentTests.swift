import XCTest
@testable import YapKit

/// 切换云端时的同步同意（0.13）：状态机 + 按 baseURL 落库 + 取消不落记录。
///
/// 全部用真 SQLite（临时文件）：`server_sync_consent:<url>` 落在 `server_sync_state`，
/// 「换地址重新问」只有在真库上才验得出来（内存字典会把规范化写错的地方掩盖掉）。
final class ServerSyncConsentTests: XCTestCase {

    private var temp: TempDatabase!

    private let baseURLText = "https://server.example.com"

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
    }

    override func tearDownWithError() throws {
        temp?.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    private func store() -> ServerSyncConsentStore {
        ServerSyncConsentStore(database: temp.stores.database, now: { 1_800_000_000_000 })
    }

    // MARK: - 首次拨开关

    func testFirstActivationPromptsAndCancelKeepsOff() throws {
        let consent = store()
        // 第一次：没有记录 → 必须弹窗。
        let plan = ServerSyncConsentGate.plan(
            baseURLText: baseURLText,
            stored: consent.decision(forBaseURLText: baseURLText)
        )
        XCTAssertEqual(plan, .prompt(url: baseURLText))

        // 用户点「取消」：不切换、不落记录（下次还会问）。
        let resolution = ServerSyncConsentGate.resolve(.cancel, baseURLText: baseURLText)
        XCTAssertEqual(resolution, .keepOff)
        XCTAssertNil(consent.decision(forBaseURLText: baseURLText), "取消不能留下「已同意」")
    }

    func testAgreeRecordsFullAndSecondActivationDoesNotPrompt() throws {
        let consent = store()
        let first = ServerSyncConsentGate.resolve(.agreeAndSync, baseURLText: baseURLText)
        guard case .proceed(let url, let decision) = first else {
            return XCTFail("同意应当 proceed，实际 \(first)")
        }
        XCTAssertEqual(url, baseURLText)
        XCTAssertEqual(decision, .full)
        XCTAssertTrue(decision.uploadsHistory)
        try consent.record(decision, forBaseURLText: url)

        // 同一台服务端再拨一次：不再弹窗，直接按 FULL 同步。
        let plan = ServerSyncConsentGate.plan(
            baseURLText: baseURLText,
            stored: consent.decision(forBaseURLText: baseURLText)
        )
        XCTAssertEqual(plan, .proceed(url: baseURLText, decision: .full))
    }

    func testNewOnlyIsRememberedAndDoesNotUploadHistory() throws {
        let consent = store()
        let resolution = ServerSyncConsentGate.resolve(.newOnly, baseURLText: baseURLText)
        guard case .proceed(let url, let decision) = resolution else {
            return XCTFail("应当 proceed，实际 \(resolution)")
        }
        XCTAssertEqual(decision, .newOnly)
        XCTAssertFalse(decision.uploadsHistory, "NEW_ONLY 不能上传历史")
        try consent.record(decision, forBaseURLText: url)
        XCTAssertEqual(consent.decision(forBaseURLText: baseURLText), .newOnly)
    }

    // MARK: - 换地址重问

    func testChangingBaseURLAsksAgain() throws {
        let consent = store()
        try consent.record(.full, forBaseURLText: baseURLText)
        XCTAssertEqual(consent.decision(forBaseURLText: baseURLText), .full)

        let other = "https://other.example.com"
        XCTAssertNil(consent.decision(forBaseURLText: other), "换一台服务端必须重新问")
        let plan = ServerSyncConsentGate.plan(
            baseURLText: other,
            stored: consent.decision(forBaseURLText: other)
        )
        XCTAssertEqual(plan, .prompt(url: other))
        XCTAssertNotNil(consent.decision(forBaseURLText: baseURLText), "旧服务端的记录不受影响")
    }

    /// 同一台服务端的不同写法（末尾斜杠 / 空白）共用同一条记录。
    func testTrailingSlashSharesTheSameConsentKey() throws {
        let consent = store()
        try consent.record(.full, forBaseURLText: baseURLText + "/")
        XCTAssertEqual(consent.decision(forBaseURLText: baseURLText), .full)
        XCTAssertEqual(consent.decision(forBaseURLText: "  " + baseURLText + "  "), .full)
        XCTAssertEqual(consent.decision(forBaseURLText: baseURLText + "/"), .full)
    }

    /// 地址不合法（空 / 非 https 域名格式）时不弹窗、也不落记录。
    func testInvalidAddressNeverPromptsNorRecords() throws {
        let consent = store()
        XCTAssertEqual(
            ServerSyncConsentGate.plan(baseURLText: "", stored: nil),
            .keepOff(url: nil)
        )
        XCTAssertEqual(
            ServerSyncConsentGate.plan(baseURLText: "   ", stored: nil),
            .keepOff(url: nil)
        )
        XCTAssertEqual(
            ServerSyncConsentGate.plan(baseURLText: "not a url", stored: nil),
            .keepOff(url: nil)
        )
        XCTAssertEqual(ServerSyncConsentGate.resolve(.agreeAndSync, baseURLText: ""), .keepOff)
        try consent.record(.full, forBaseURLText: "not a url")
        XCTAssertNil(consent.decision(forBaseURLText: "not a url"))
    }

    /// 冷启动（升级上来的老用户开着服务端、没有记录）：按 NEW_ONLY 跑，不擅自上传历史。
    func testColdStartWithoutRecordFallsBackToNewOnly() {
        XCTAssertEqual(ServerSyncConsentGate.activationConsent(stored: nil), .newOnly)
        XCTAssertEqual(ServerSyncConsentGate.activationConsent(stored: .full), .full)
        XCTAssertEqual(ServerSyncConsentGate.activationConsent(stored: .newOnly), .newOnly)
    }

    func testClearForgetsTheRecord() throws {
        let consent = store()
        try consent.record(.newOnly, forBaseURLText: baseURLText)
        XCTAssertNotNil(consent.decision(forBaseURLText: baseURLText))
        try consent.clear(forBaseURLText: baseURLText)
        XCTAssertNil(consent.decision(forBaseURLText: baseURLText))
    }
}
