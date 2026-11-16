import Foundation
@testable import YapKit

/// 服务端传输层测试的共用样例与取值助手。
enum ServerTestSupport {

    static let defaultBaseURL = URL(string: "https://server.example.com")!

    static func client(
        token: String? = "secret-token",
        baseURL: URL = defaultBaseURL
    ) -> ServerAPIClient {
        ServerAPIClient(
            config: ServerConfig(baseURL: baseURL, token: token),
            session: ServerURLProtocolStub.makeSession()
        )
    }

    /// 请求的 query 转成字典（同名参数只保留最后一个；本接口没有重复参数）。
    static func queryItems(_ request: URLRequest) -> [String: String] {
        guard let url = request.url,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let items = components.queryItems else {
            return [:]
        }
        var result: [String: String] = [:]
        for item in items {
            result[item.name] = item.value ?? ""
        }
        return result
    }

    /// 一段完整的 SSE 帧（与 `agentd` 的 `SseEvent.frame()` 同形）。
    static func sseFrame(_ event: String, _ data: String) -> String {
        "event: \(event)\ndata: \(data)\n\n"
    }

    // MARK: - 固定报文

    static let stateJSON = """
    {
      "serverTime": 1700000000000,
      "personas": [
        {
          "id": "p1",
          "name": "林深",
          "nameEn": "Lin Shen",
          "description": "慢热的人",
          "avatarUrl": "/v1/media/avatar1",
          "isDefault": true,
          "createdAt": 100,
          "updatedAt": 200
        }
      ],
      "sessions": [
        {
          "id": "s1",
          "personaId": "p1",
          "title": "夜里散步",
          "createdAt": 300,
          "updatedAt": 400,
          "lastMessageAt": 500,
          "lastBlockIndex": 2,
          "unread": 1,
          "messageCount": 201
        }
      ],
      "messages": [
        {
          "id": "m1",
          "sessionId": "s1",
          "blockIndex": 2,
          "role": "USER",
          "content": "你好",
          "createdAt": 500,
          "readAt": null,
          "attachments": ["media-1"],
          "source": "CHAT",
          "clientMessageId": "client-1",
          "replyTo": null,
          "promptTokens": null,
          "completionTokens": null
        }
      ],
      "mood": { "personaId": "p1", "label": "平静", "intensity": 3, "updatedAt": 450 },
      "presence": { "personaId": "p1", "kind": "IDLE", "detail": "在听", "updatedAt": 460 },
      "serverCursor": 12345,
      "hasMoreMessages": true
    }
    """

    static let sessionsJSON = """
    {
      "serverTime": 1700000000000,
      "serverCursor": 77,
      "sessions": [
        {
          "id": "s1",
          "personaId": "p1",
          "title": "第一个会话",
          "createdAt": 1,
          "updatedAt": 2,
          "lastMessageAt": 3,
          "lastBlockIndex": 0,
          "unread": 2,
          "messageCount": 5
        }
      ]
    }
    """

    static let messagePageJSON = """
    {
      "sessionId": "s1",
      "blockIndex": 2,
      "hasMoreOlder": true,
      "hasMoreNewer": false,
      "messages": [
        {
          "id": "m1",
          "sessionId": "s1",
          "blockIndex": 2,
          "role": "ASSISTANT",
          "content": "在的",
          "createdAt": 500,
          "readAt": null,
          "attachments": [],
          "source": "CHAT",
          "clientMessageId": null,
          "replyTo": "m0",
          "promptTokens": 12,
          "completionTokens": 34
        }
      ]
    }
    """

    static let emptyMessagePageJSON = """
    {
      "sessionId": "s1",
      "blockIndex": null,
      "hasMoreOlder": false,
      "hasMoreNewer": false,
      "messages": []
    }
    """

    static let eventsJSON = """
    {
      "serverTime": 1700000000000,
      "cursor": 9,
      "events": [
        {
          "cursor": 8,
          "type": "message.created",
          "at": 1000,
          "sessionId": "s1",
          "personaId": "p1",
          "payload": { "message": { "id": "m1" } }
        },
        {
          "cursor": 9,
          "type": "mood.changed",
          "at": 1001,
          "sessionId": null,
          "personaId": "p1",
          "payload": { "mood": { "label": "平静" } }
        }
      ]
    }
    """
}
