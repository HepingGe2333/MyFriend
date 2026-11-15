import Foundation
import XCTest
@testable import MyFriendKit

/// `ServerChatSync` 的纯映射：`ServerStateSnapshot` / `ServerMessagePage` → 首页/聊天页状态。
final class ServerChatSyncTests: XCTestCase {

    // MARK: - 首页

    func testHomeStateMapsPersonasSessionsMoodPresenceAndPreview() {
        let snapshot = ServerStateSnapshot(
            serverTime: 1000,
            personas: [
                ServerPersona(id: "p2", name: "阿岚", isDefault: false),
                ServerPersona(id: "p1", name: "林深", isDefault: true)
            ],
            sessions: [
                ServerSession(
                    id: "s1", personaId: "p1", title: "夜里散步",
                    createdAt: 10, updatedAt: 20, lastMessageAt: 40,
                    lastBlockIndex: 3, unread: 2, messageCount: 401
                ),
                ServerSession(
                    id: "s2", personaId: "p2", title: "空会话",
                    createdAt: 5, updatedAt: 5, lastMessageAt: nil,
                    lastBlockIndex: -1, unread: 0, messageCount: 0
                )
            ],
            messages: [
                ServerMessage(
                    id: "m1", sessionId: "s1", blockIndex: 3,
                    role: "USER", content: "旧的一句", createdAt: 30
                ),
                ServerMessage(
                    id: "m2", sessionId: "s1", blockIndex: 3,
                    role: "ASSISTANT", content: "最新\n一句", createdAt: 40
                )
            ],
            mood: ServerMood(personaId: "p1", label: "平静", intensity: 3, updatedAt: 40),
            presence: ServerPresence(personaId: "p1", kind: "IDLE", detail: "在听", updatedAt: 40),
            serverCursor: 999,
            hasMoreMessages: true
        )

        let home = ServerChatSync.homeState(from: snapshot)

        XCTAssertEqual(home.serverTime, 1000)
        XCTAssertEqual(home.serverCursor, 999)
        XCTAssertTrue(home.hasMoreMessages)
        XCTAssertEqual(home.personas.map(\.id), ["p2", "p1"], "人格顺序原样保留")
        XCTAssertEqual(home.defaultPersonaId, "p1")
        XCTAssertEqual(home.mood?.label, "平静")
        XCTAssertEqual(home.presence?.detail, "在听")

        XCTAssertEqual(home.sessions.count, 2)
        XCTAssertEqual(home.sessions[0].id, "s1")
        XCTAssertEqual(home.sessions[0].title, "夜里散步")
        XCTAssertEqual(home.sessions[0].unread, 2)
        XCTAssertEqual(home.sessions[0].lastMessagePreview, "最新 一句", "换行要变空格")
        XCTAssertEqual(home.sessions[0].lastMessageAt, 40)
        XCTAssertEqual(home.sessions[0].lastBlockIndex, 3)
        XCTAssertEqual(home.sessions[0].messageCount, 401)

        XCTAssertEqual(home.sessions[1].id, "s2")
        XCTAssertNil(home.sessions[1].lastMessagePreview, "空会话没有预览")
        XCTAssertNil(home.sessions[1].lastMessageAt)
        XCTAssertNil(home.session(id: "nope"))
        XCTAssertEqual(home.session(id: "s1")?.title, "夜里散步")
    }

    func testHomeStateSortsByRecentActivityNotSnapshotOrder() {
        let snapshot = ServerStateSnapshot(
            sessions: [
                ServerSession(id: "old", personaId: "p1", createdAt: 1, lastMessageAt: 10),
                ServerSession(id: "empty", personaId: "p1", createdAt: 999, lastMessageAt: nil),
                ServerSession(id: "new", personaId: "p1", createdAt: 2, lastMessageAt: 100),
                ServerSession(id: "tie-a", personaId: "p1", createdAt: 5, lastMessageAt: 50),
                ServerSession(id: "tie-b", personaId: "p1", createdAt: 7, lastMessageAt: 50)
            ]
        )
        let home = ServerChatSync.homeState(from: snapshot)
        XCTAssertEqual(
            home.sessions.map(\.id),
            ["new", "tie-b", "tie-a", "old", "empty"],
            "按最近说话排序；同时刻创建更晚的在前；空会话沉底"
        )
    }

    func testHomeStateUsesLatestMessageTimeWhenSessionRowHasNone() {
        let snapshot = ServerStateSnapshot(
            sessions: [
                ServerSession(id: "s1", personaId: "p1", createdAt: 1, lastMessageAt: nil, lastBlockIndex: 0)
            ],
            messages: [
                ServerMessage(id: "m1", sessionId: "s1", blockIndex: 0,
                              role: "USER", content: "嗨", createdAt: 123)
            ]
        )
        let home = ServerChatSync.homeState(from: snapshot)
        XCTAssertEqual(home.sessions.first?.lastMessageAt, 123)
        XCTAssertEqual(home.sessions.first?.lastMessagePreview, "嗨")
    }

    func testHomeStateEmptySessionHasNoPreview() {
        let snapshot = ServerStateSnapshot(
            sessions: [
                ServerSession(id: "s1", personaId: "p1", title: "", lastBlockIndex: -1)
            ]
        )
        let home = ServerChatSync.homeState(from: snapshot)
        XCTAssertNil(home.sessions.first?.lastMessagePreview)
        XCTAssertEqual(home.sessions.first?.lastBlockIndex, -1)
        XCTAssertNil(home.defaultPersonaId, "没有人员时默认人格为空")
    }

    func testPreviewTruncatesAndFlattens() {
        let long = String(repeating: "字", count: 150) + "\n第二行"
        let preview = ServerChatSync.previewText(long)
        XCTAssertEqual(preview?.count, ServerChatSync.previewLimit)
        XCTAssertFalse(preview?.contains("\n") ?? true)
        XCTAssertNil(ServerChatSync.previewText("   \n\t  "))
        XCTAssertNil(ServerChatSync.previewText(""))
        XCTAssertEqual(ServerChatSync.previewText("短句"), "短句")
        XCTAssertEqual(ServerChatSync.previewText("abcdef", limit: 3), "abc")
        XCTAssertNil(ServerChatSync.previewText("abc", limit: 0))
    }

    // MARK: - 聊天页：从快照

    func testChatStateFromSnapshotFiltersSessionSortsAndUsesTruncationFlag() {
        let snapshot = ServerStateSnapshot(
            sessions: [
                ServerSession(id: "s1", personaId: "p1", lastBlockIndex: 4),
                ServerSession(id: "s2", personaId: "p1", lastBlockIndex: 0)
            ],
            messages: [
                ServerMessage(id: "b", sessionId: "s1", blockIndex: 4, role: "ASSISTANT",
                              content: "后", createdAt: 20),
                ServerMessage(id: "x", sessionId: "s2", blockIndex: 0, role: "USER",
                              content: "别人", createdAt: 15),
                ServerMessage(id: "a", sessionId: "s1", blockIndex: 4, role: "USER",
                              content: "前", createdAt: 10),
                // 同一毫秒按 id 的 UTF-16 序：a < a2
                ServerMessage(id: "a2", sessionId: "s1", blockIndex: 4, role: "USER",
                              content: "同刻", createdAt: 10)
            ],
            hasMoreMessages: true
        )

        let chat = ServerChatSync.chatState(from: snapshot, sessionId: "s1")

        XCTAssertEqual(chat.sessionId, "s1")
        XCTAssertEqual(chat.blockIndex, 4)
        XCTAssertEqual(chat.messages.map(\.id), ["a", "a2", "b"])
        XCTAssertTrue(chat.hasMoreOlder, "首次同步被截断时保守地允许往上翻")
        XCTAssertFalse(chat.hasMoreNewer, "最新块后面没有更多")
        XCTAssertEqual(ServerChatSync.messages(from: snapshot, sessionId: "s2").map(\.id), ["x"])
    }

    func testChatStateFromSnapshotEmptySession() {
        let snapshot = ServerStateSnapshot(
            sessions: [ServerSession(id: "s1", personaId: "p1", lastBlockIndex: -1)],
            messages: []
        )
        let chat = ServerChatSync.chatState(from: snapshot, sessionId: "s1")
        XCTAssertNil(chat.blockIndex)
        XCTAssertTrue(chat.messages.isEmpty)
        XCTAssertFalse(chat.hasMoreOlder)
    }

    func testChatStateFromSnapshotFallsBackToMessageBlockWhenSessionMissing() {
        let snapshot = ServerStateSnapshot(
            messages: [
                ServerMessage(id: "m1", sessionId: "s9", blockIndex: 7, role: "USER",
                              content: "hi", createdAt: 1)
            ]
        )
        let chat = ServerChatSync.chatState(from: snapshot, sessionId: "s9")
        XCTAssertEqual(chat.blockIndex, 7)
        XCTAssertEqual(chat.messages.count, 1)
    }

    // MARK: - 聊天页：从分页响应

    func testChatStateFromPageCarriesExactFlagsAndSorts() {
        let page = ServerMessagePage(
            sessionId: "s1",
            blockIndex: 2,
            hasMoreOlder: true,
            hasMoreNewer: true,
            messages: [
                ServerMessage(id: "m2", sessionId: "s1", blockIndex: 2, role: "ASSISTANT",
                              content: "后", createdAt: 20),
                ServerMessage(id: "m1", sessionId: "s1", blockIndex: 2, role: "USER",
                              content: "前", createdAt: 10)
            ]
        )
        let chat = ServerChatSync.chatState(from: page)
        XCTAssertEqual(chat.blockIndex, 2)
        XCTAssertEqual(chat.messages.map(\.id), ["m1", "m2"])
        XCTAssertTrue(chat.hasMoreOlder)
        XCTAssertTrue(chat.hasMoreNewer)
    }

    func testChatStateFromEmptyPageHasNilBlock() {
        let page = ServerMessagePage(
            sessionId: "s1", blockIndex: nil,
            hasMoreOlder: false, hasMoreNewer: false, messages: []
        )
        let chat = ServerChatSync.chatState(from: page)
        XCTAssertNil(chat.blockIndex)
        XCTAssertTrue(chat.messages.isEmpty)
    }

    func testChatStateFromPageFallsBackToLastMessageBlock() {
        let page = ServerMessagePage(
            sessionId: "s1",
            blockIndex: nil,
            messages: [
                ServerMessage(id: "m1", sessionId: "s1", blockIndex: 9, role: "USER",
                              content: "hi", createdAt: 1)
            ]
        )
        XCTAssertEqual(ServerChatSync.chatState(from: page).blockIndex, 9)
    }
}
