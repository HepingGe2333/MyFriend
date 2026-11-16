import Foundation
import XCTest
@testable import YapKit

/// 0.11.0 审计 P2-3：聊天页什么时候该轮询、什么时候该停。
///
/// 0.10.2 的两条 Task 只在离开页面时停：页面一直开着就每 4 秒查一次库，
/// 生成期间还照样醒来一次再 return。这里的规则是「可见 + 前台」才有轮询，
/// 流式输出期间消息轮询让位给流本身（事件驱动），发呆时降到 20 秒保底。
final class ChatPollingPolicyTests: XCTestCase {

    func testPulsesStopWhenPageHiddenOrAppBackgrounded() {
        // 页面被 push 走 / 返回上一页：两路都停，不留后台 Task。
        XCTAssertEqual(
            ChatPollingPolicy.plan(isVisible: false, sceneActive: true, isGenerating: false),
            .stopped
        )
        // 进后台同样停：`onChange(scenePhase)` 会停掉，回前台由 `enter()` 重读。
        XCTAssertEqual(
            ChatPollingPolicy.plan(isVisible: true, sceneActive: false, isGenerating: false),
            .stopped
        )
        // 生成中进后台也是停（这条最容易漏：原来 `isGenerating` 的 continue 会把 Task 留着）。
        XCTAssertEqual(
            ChatPollingPolicy.plan(isVisible: true, sceneActive: false, isGenerating: true),
            .stopped
        )
    }

    func testMessagePulseStopsWhileStreaming() {
        let plan = ChatPollingPolicy.plan(isVisible: true, sceneActive: true, isGenerating: true)
        XCTAssertNil(plan.messageIntervalNanos, "流式输出期间不查库：流本身就是事件源")
        XCTAssertEqual(
            plan.contextIntervalNanos, ChatPollingPolicy.contextIntervalNanos,
            "心情与「他此刻」仍然每 30 秒刷新"
        )
    }

    func testIdlePagePollsAtRelaxedIntervalInsteadOfFourSeconds() {
        let plan = ChatPollingPolicy.plan(isVisible: true, sceneActive: true, isGenerating: false)
        XCTAssertEqual(plan.messageIntervalNanos, 20 * 1_000_000_000)
        XCTAssertEqual(plan.contextIntervalNanos, 30 * 1_000_000_000)
        // 0.10.2 的 4 秒不该再出现在任何一条路径上。
        for visible in [true, false] {
            for active in [true, false] {
                for generating in [true, false] {
                    let plan = ChatPollingPolicy.plan(
                        isVisible: visible, sceneActive: active, isGenerating: generating
                    )
                    XCTAssertNotEqual(plan.messageIntervalNanos, 4 * 1_000_000_000)
                }
            }
        }
    }

    /// 从「可见」到「生成中」再回到「发呆」：每一拍都能拿到不同间隔，界面不需要自己算。
    func testPlanFollowsStateTransitions() {
        XCTAssertNil(ChatPollingPolicy.plan(isVisible: true, sceneActive: true, isGenerating: true)
            .messageIntervalNanos)
        XCTAssertEqual(
            ChatPollingPolicy.plan(isVisible: true, sceneActive: true, isGenerating: false)
                .messageIntervalNanos,
            ChatPollingPolicy.idleMessageIntervalNanos
        )
        XCTAssertEqual(
            ChatPollingPolicy.plan(isVisible: true, sceneActive: true, isGenerating: false)
                .contextIntervalNanos,
            ChatPollingPolicy.contextIntervalNanos
        )
    }
}
