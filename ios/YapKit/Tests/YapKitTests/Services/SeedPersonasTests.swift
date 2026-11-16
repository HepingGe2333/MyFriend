import Foundation
import XCTest
@testable import YapKit

/// 种子人格的默认值（0.12 续 7）。
///
/// 这是「新装第一晚发消息没人理」的源头之一：种子人格过去没有显式设 `sleepEnabled`，
/// 于是取了实体默认的 `true` —— 一到 23:30 就只标已读、连着发到第 6 条才醒。
/// 现在种子显式给 `false`（睡眠是可选功能，用户在人格编辑里打开才算数），
/// 唤醒阈值也变成 3：真打开了睡眠，夜里发三条就能把他叫起来。
final class SeedPersonasTests: XCTestCase {

    private func build(_ language: SeedLanguage = .chinese) -> [PersonaEntity] {
        SeedPersonas.build(now: 1_794_500_000_000, language: language)
    }

    func testSeedPersonasDoNotSleepByDefault() {
        for language in [SeedLanguage.chinese, .english] {
            let personas = build(language)
            XCTAssertEqual(personas.count, 3)
            for persona in personas {
                XCTAssertFalse(persona.sleepEnabled, "种子人格「\(persona.name)」不该默认开着睡眠")
                XCTAssertNil(persona.quietSleepMinutes, "种子人格没设作息")
                XCTAssertNil(persona.quietWakeMinutes, "种子人格没设作息")
            }
        }
    }

    func testSeedPersonasUseNewWakeThreshold() {
        for persona in build() {
            XCTAssertEqual(persona.wakeThreshold, 3, "打开睡眠后发 3 条就该叫醒他")
            XCTAssertEqual(persona.wakeThreshold, PersonaFieldLimits.wakeThresholdDefault)
        }
    }

    /// 播种出来的「不睡」要真的走到判定里：任意时刻都会回。
    func testSeededPersonaRepliesAtAnyHour() {
        let persona = build()[0]
        for minute in [0, 3 * 60, 12 * 60, 23 * 60 + 45] {
            XCTAssertFalse(SleepSchedule.isAsleep(persona, minuteOfDay: minute))
            XCTAssertEqual(SleepGate.decide(persona: persona, recentUserMessages: 1, now: 1_794_500_000_000), .reply)
        }
    }

    func testSeedPersonaOtherFieldsUnchanged() {
        let personas = build()
        XCTAssertEqual(personas.filter(\.isDefault).map(\.id), [SeedPersonas.Identifiers.first])
        XCTAssertEqual(
            personas.map(\.id),
            [
                SeedPersonas.Identifiers.first,
                SeedPersonas.Identifiers.second,
                SeedPersonas.Identifiers.third
            ]
        )
        for persona in personas {
            XCTAssertEqual(persona.version, 1)
            XCTAssertNil(persona.defaultApiConfigId)
        }
    }

    /// 新建人格的入口默认值（`PersonaEditorScreen` 的 `@State`）在 App 层，`swift test` 跑不到；
    /// 这里钉住它依赖的那个常量：阈值默认 3。
    func testPersonaFieldLimitsWakeThresholdDefaultIsThree() {
        XCTAssertEqual(PersonaFieldLimits.wakeThresholdDefault, 3)
        XCTAssertEqual(PersonaFieldLimits.clampWakeThreshold(nil), 3)
        XCTAssertEqual(PersonaFieldLimits.clampWakeThreshold(99), 20)
        XCTAssertEqual(PersonaFieldLimits.clampWakeThreshold(0), 1)
    }
}
