import Foundation
import XCTest
@testable import YapKit

/// 0.14.1 §8：延迟归服务端、客户端零本地延迟、离线同步不演打字动画。
final class ServerTypingPolicyTests: XCTestCase {

    func testServerModeNeverAddsLocalTypingDelay() {
        XCTAssertFalse(ServerTypingPolicy.appliesLocalTypingDelay(serverMode: true))
        XCTAssertTrue(ServerTypingPolicy.appliesLocalTypingDelay(serverMode: false))
        XCTAssertEqual(
            ServerTypingPolicy.extraDelaySeconds(serverMode: true, arrival: .liveEvent), 0
        )
        XCTAssertEqual(
            ServerTypingPolicy.extraDelaySeconds(serverMode: true, arrival: .offlineSync), 0
        )
        // 同一条消息在服务端模式下用打字延迟公式也算 0（公式本身仍由服务端用）。
        XCTAssertEqual(
            TypingDelay.seconds(charCount: 40, mood: MoodState(), serverAuthoritative: true), 0
        )
        XCTAssertGreaterThan(
            TypingDelay.seconds(charCount: 40, mood: MoodState(), serverAuthoritative: false), 0
        )
    }

    func testTypingIndicatorRequiresForegroundAndOpenSession() {
        XCTAssertTrue(ServerTypingPolicy.showsTypingIndicator(
            serverMode: true, isForeground: true, isSessionOpen: true, hasTypingEvent: true
        ))
        XCTAssertFalse(ServerTypingPolicy.showsTypingIndicator(
            serverMode: true, isForeground: false, isSessionOpen: true, hasTypingEvent: true
        ), "后台不看 typing")
        XCTAssertFalse(ServerTypingPolicy.showsTypingIndicator(
            serverMode: true, isForeground: true, isSessionOpen: false, hasTypingEvent: true
        ), "会话没开着不显示")
        XCTAssertFalse(ServerTypingPolicy.showsTypingIndicator(
            serverMode: true, isForeground: true, isSessionOpen: true, hasTypingEvent: false
        ), "没收到 typing 事件不显示")
        XCTAssertFalse(ServerTypingPolicy.showsTypingIndicator(
            serverMode: false, isForeground: true, isSessionOpen: true, hasTypingEvent: true
        ), "本地模式走 isGenerating，不看服务端 typing")
    }

    func testOfflineSyncedMessagesNeverAnimateTyping() {
        XCTAssertFalse(ServerTypingPolicy.showsTypingAnimation(for: .offlineSync))
        XCTAssertTrue(ServerTypingPolicy.showsTypingAnimation(for: .liveEvent))
    }

    func testLiveStateTypingExpiresIfStoppedEventIsLost() {
        var live = ServerLiveState()
        live.typing["s1"] = 1_000
        XCTAssertTrue(live.isTyping(sessionId: "s1", now: 1_500))
        XCTAssertFalse(
            live.isTyping(sessionId: "s1", now: 1_000 + ServerLiveState.typingMaxAgeMillis + 1),
            "typing.stopped 丢了也不能永远显示正在输入"
        )
        XCTAssertFalse(live.isTyping(sessionId: "s2", now: 1_500))
    }
}
