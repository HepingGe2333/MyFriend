import Foundation
import XCTest
@testable import YapKit

/// 跨端「封条」协议（0.15）：`yap-canonical-json-sha256-v1`。
///
/// 这一组测试的重点只有一个：**iOS 算出来的哈希必须与 Android 逐字节一致**。
/// 两端的黄金值是先用 Android 的 `BackupChecksum`（`app/.../domain/BackupMetadata.kt`，
/// kotlinx-serialization 1.7.3 + JDK 17）对同一份文本算出来、再抄进这里的：
/// - `android-export-v7.json`（真实 v7 样本：中文、媒体 base64、`1.0` 这类浮点字面量都在）；
/// - 一段专门挑了「数字字面量 / 转义 / 非 ASCII / 键排序 / 重复键」的小 JSON。
///
/// 所以这两条断言一旦变红，就是两端协议漂了 —— 不是「iOS 自己前后不一致」。
final class BackupIntegrityTests: XCTestCase {

    /// Kotlin 侧对 `fixtures/backup/android-export-v7.json` 算出的 SHA-256。
    private static let androidV7GoldenChecksum = "ac6e73b5558181e269b8f5e71559dc3be39086a62609b0667ecf6770855977fb"

    /// 专门挑刺的小 JSON：数字原始字面量（`1.0` / `1E+5` / `-0`）、`\/`、`\"`、`\\`、
    /// 制表符、`\u0001`、emoji（代理对）、大小写/下划线/非 ASCII 键、重复键、null、true。
    private static let trickyJSON = #"{"b":1.0,"a":[1E+5,-0,0.5],"中":"中文 \/ \"quote\" \\ tab\t ctl\u0001 emoji😀","A":{"z":null,"A":true},"_":"x","é":"y","dup":1,"dup":2}"#

    /// Kotlin 的 `BackupChecksum.canonical` 对上面那份 JSON 的输出（键排序、无空白）。
    private static let trickyCanonical =
        #"{"A":{"A":true,"z":null},"_":"x","a":[1E+5,-0,0.5],"b":1.0,"dup":2,"é":"y","中":"中文 / \"quote\" \\ tab\t ctl\u0001 emoji😀"}"#

    /// Kotlin 侧对上面那份 canonical 文本的 SHA-256。
    private static let trickyCanonicalChecksum = "66d084f9e51aa9a1888ebbdeace19420f05e4b646fb9dde1f742efbc7bd576d0"

    private let fileManager = FileManager.default

    // MARK: - 规范化

    func testCanonicalJSONMatchesKotlinGolden() {
        let canonical = BackupIntegrity.canonicalJSONWithoutIntegrityKeys(Self.trickyJSON)
        XCTAssertEqual(canonical, Self.trickyCanonical)
        XCTAssertEqual(BackupIntegrity.checksum(ofJSONText: Self.trickyJSON),
                       Self.trickyCanonicalChecksum)
    }

    func testChecksumStripsIntegrityKeysThemselves() {
        // 已经封好的文件再算一次：结果必须完全相同（checksum 不封自己）。
        let sealed = Self.trickyJSON.dropLast() + #","checksumAlgorithm":"yap-canonical-json-sha256-v1","checksum":"deadbeef"}"#
        XCTAssertEqual(BackupIntegrity.checksum(ofJSONText: String(sealed)),
                       Self.trickyCanonicalChecksum)
    }

    func testCanonicalJSONIsInsensitiveToFormatting() {
        let compact = #"{"a":1,"b":[true,null,"x"]}"#
        let pretty = """
        {
          "b" : [ true , null , "x" ] ,
          "a" : 1
        }
        """
        XCTAssertEqual(
            BackupIntegrity.canonicalJSONWithoutIntegrityKeys(compact),
            BackupIntegrity.canonicalJSONWithoutIntegrityKeys(pretty)
        )
    }

    func testNumbersKeepTheirLiteralText() {
        XCTAssertEqual(
            BackupIntegrity.canonicalJSONWithoutIntegrityKeys(#"{"a":1.0,"b":1,"c":1e-5}"#),
            #"{"a":1.0,"b":1,"c":1e-5}"#
        )
    }

    func testEscapesFollowKotlinRules() {
        // 斜杠不转义、非 ASCII 原样、控制字符用小写 `\u00xx`、短转义用 `\t` 这类。
        XCTAssertEqual(
            BackupIntegrity.canonicalJSONWithoutIntegrityKeys(
                #"{"s":"a\/b\u0001\u001f\t\n\"\\"}"#
            ),
            #"{"s":"a/b\u0001\u001f\t\n\"\\"}"#
        )
    }

    func testInvalidJSONHasNoCanonicalForm() {
        XCTAssertNil(BackupIntegrity.canonicalJSONWithoutIntegrityKeys("{不是 JSON"))
        XCTAssertNil(BackupIntegrity.canonicalJSONWithoutIntegrityKeys("[1,2,3]"), "顶层必须是对象")
        XCTAssertNil(BackupIntegrity.canonicalJSONWithoutIntegrityKeys("{} {}"))
        XCTAssertNil(BackupIntegrity.checksum(ofJSONText: ""))
    }

    // MARK: - 真实 v7 样本（iOS ← Android 方向）

    private func androidFixtureText() throws -> String {
        let directory = try XCTUnwrap(RepoLayout.backupFixtureDirectory(), "找不到 fixtures/backup")
        let url = directory.appendingPathComponent("android-export-v7.json")
        return try String(contentsOf: url, encoding: .utf8)
    }

    func testAndroidFixtureCanonicalChecksumMatchesKotlinGolden() throws {
        let raw = try androidFixtureText()
        XCTAssertEqual(BackupIntegrity.checksum(ofJSONText: raw), Self.androidV7GoldenChecksum)
    }

    /// 把 Android 的金标封条写进样本（模拟「Android 0.15 导出的 v7 备份」），iOS 必须：
    /// 读得出来、不报损坏、预览显示「已校验」。
    func testAndroidFixtureWithKotlinChecksumImportsCleanly() throws {
        let raw = try androidFixtureText()
        let sealed = try Self.insertIntegrityFields(
            into: raw,
            algorithm: BackupIntegrity.algorithm,
            checksum: Self.androidV7GoldenChecksum
        )

        let result = BackupCodec.decode(sealed)
        let bundle = try XCTUnwrap(result.bundle, "Android 的 v7 备份不能被判成损坏：\(result.failureReason ?? "?")")
        XCTAssertEqual(bundle.checksum, Self.androidV7GoldenChecksum)
        XCTAssertEqual(bundle.checksumAlgorithm, BackupIntegrity.algorithm)

        let preview = BackupPreview.from(bundle)
        XCTAssertTrue(preview.checksumVerified)
        XCTAssertFalse(preview.checksumForeign)
        XCTAssertEqual(preview.integrityText(),
                       "已校验：SHA-256 " + String(Self.androidV7GoldenChecksum.prefix(8)))
    }

    // MARK: - 来自别的客户端的封条：跳过，不拦

    func testForeignAlgorithmChecksumIsSkippedNotRejected() throws {
        let raw = try androidFixtureText()
        // 故意写一个错得离谱的哈希：只要算法标记不是本机的，就不该拿它判损坏。
        let sealed = try Self.insertIntegrityFields(
            into: raw,
            algorithm: "some-other-client-v2",
            checksum: String(repeating: "a", count: 64)
        )
        let result = BackupCodec.decode(sealed)
        let bundle = try XCTUnwrap(result.bundle, "别的客户端的封条不能拒绝导入：\(result.failureReason ?? "?")")
        XCTAssertEqual(bundle.checksum, String(repeating: "a", count: 64))
        XCTAssertEqual(bundle.checksumAlgorithm, "some-other-client-v2")

        let preview = BackupPreview.from(bundle)
        XCTAssertTrue(preview.checksumForeign)
        XCTAssertFalse(preview.checksumVerified)
        XCTAssertEqual(preview.integrityText(), "校验和来自其他客户端，未在本机复核")
    }

    func testChecksumWithoutAlgorithmIsTreatedAsForeign() throws {
        let raw = try androidFixtureText()
        // 只有 checksum、没有算法标记（例如将来某个工具手写的）→ 跳过复核。
        let sealed = try Self.insertIntegrityFields(
            into: raw, algorithm: nil, checksum: String(repeating: "b", count: 64)
        )
        let bundle = try XCTUnwrap(BackupCodec.decode(sealed).bundle)
        XCTAssertTrue(BackupPreview.from(bundle).checksumForeign)
    }

    // MARK: - 本机算法的封条：严格复核

    private func sealedSample() throws -> (text: String, bundle: BackupBundle) {
        let bundle = BackupSamples.iosBundle()
        return (try BackupCodec.encodeSealed(bundle), bundle)
    }

    func testOwnChecksumMismatchRejectsTheFile() throws {
        let (text, _) = try sealedSample()
        XCTAssertTrue(BackupCodec.decode(text).isSuccess, "前提：自己封的文件先要能读")
        let tampered = text.replacingOccurrences(of: "沈迟", with: "沈迟他")
        XCTAssertNotEqual(tampered, text)
        let result = BackupCodec.decode(tampered)
        XCTAssertFalse(result.isSuccess, "本机算法标记 + 哈希对不上 = 损坏")
        XCTAssertTrue(result.failureReason?.contains("校验") ?? false, result.failureReason ?? "?")
    }

    func testOwnChecksumWithMalformedHashRejectsTheFile() throws {
        let (text, _) = try sealedSample()
        guard let range = text.range(of: #""checksum":"[0-9a-f]{64}""#, options: .regularExpression) else {
            return XCTFail("封好的文件里应当有 64 位 hex 的 checksum")
        }
        let tampered = text.replacingCharacters(in: range, with: #""checksum":"not-a-hash""#)
        XCTAssertFalse(BackupCodec.decode(tampered).isSuccess)
    }

    func testOwnChecksumIsVerifiedForSampleExport() throws {
        let (text, bundle) = try sealedSample()
        let decoded = try XCTUnwrap(BackupCodec.decode(text).bundle)
        XCTAssertEqual(decoded.checksumAlgorithm, BackupIntegrity.algorithm)
        let checksum = try XCTUnwrap(decoded.checksum)
        XCTAssertEqual(checksum.count, 64)
        XCTAssertTrue(checksum.allSatisfy(\.isHexDigit))
        // 内容原样回来（封条不影响业务数据）。
        XCTAssertEqual(decoded.personas, bundle.personas)
        XCTAssertEqual(decoded.messages, bundle.messages)
        XCTAssertEqual(try BackupCodec.encodeSealed(decoded), text, "解→编→解 逐字幂等")
    }

    // MARK: - 注入工具

    /// 在顶层对象末尾追加封条字段（样本是紧凑单行 JSON，直接插在最后一个 `}` 前）。
    private static func insertIntegrityFields(
        into raw: String,
        algorithm: String?,
        checksum: String
    ) throws -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasSuffix("}") else {
            throw NSError(domain: "BackupIntegrityTests", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "样本不是以 } 结尾"])
        }
        var fields: [String] = []
        if let algorithm {
            fields.append(#""checksumAlgorithm":"# + quote(algorithm))
        }
        fields.append(#""checksum":"# + quote(checksum))
        let body = String(trimmed.dropLast())
        return body + "," + fields.joined(separator: ",") + "}"
    }

    private static func quote(_ text: String) -> String {
        let data = try! JSONSerialization.data(withJSONObject: [text], options: [])
        let array = String(data: data, encoding: .utf8)!
        return String(array.dropFirst().dropLast())
    }
}
