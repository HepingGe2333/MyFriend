import Foundation

// ---------------------------------------------------------------------------
// 媒体文件的引用查询（不新增表、不升 schema）。
//
// 背景：附件按内容哈希复用文件名（`chatImage-<sha256 前 16>-<uuid>.jpg` /
// `avatar-<sha256 前 16>-<uuid>.jpg`），所以**同一个文件可能被多条记录引用**：
// 两条消息选了同一张图、人格头像与预设头像相同、或者「我的头像」用了同一张图。
// 以前删消息/删人格/换头像时直接删文件，其它引用就变成「图片已不在本机」。
//
// 这里只回答一个问题：**这个文件现在还有没有引用？** 删库行之后再来问，
// 零引用才允许删文件（见 `MediaStorage.deleteIfUnreferenced`）。
//
// 两类引用的存储位置：
// - 聊天图：`messages.attachmentsJson`（JSON 文本里带绝对路径，跨会话全局查）；
// - 头像：`personas.avatarUri`、`persona_presets.avatarUri`、设置里的 `myAvatarUri`。
//
// 文件名是「内容哈希 + UUID」，几乎不可能撞前缀，但 LIKE 仍然按字面量匹配：
// `%` / `_` / `\` 都要转义，否则文件名里的一个 `_` 会变成通配符，把不相关的图算成引用。
// ---------------------------------------------------------------------------

/// 媒体引用查询。调用方拿到 `DatabaseStores` + 设置即可构造。
public struct MediaReferences {

    private let database: SQLiteDatabase
    private let settings: SettingsProviding?

    public init(database: SQLiteDatabase, settings: SettingsProviding? = nil) {
        self.database = database
        self.settings = settings
    }

    public init(stores: DatabaseStores, settings: SettingsProviding? = nil) {
        self.init(database: stores.database, settings: settings)
    }

    // MARK: - 文件名

    /// URI / 裸文件名 → 最后一段文件名（删除与引用查询都以它为准）。
    ///
    /// 数据库里可能存着旧容器 UUID 的绝对路径、`file://` URL 或裸文件名（历史数据），
    /// 但它们指向的都是附件目录里的同一个文件；比较文件名才不会因为容器路径变化而漏判。
    /// 返回 nil = 这个值不指向一个可删除的普通文件（空串、`.`、`..`、带反斜杠）。
    public static func fileName(from uriString: String?) -> String? {
        guard let raw = uriString?.trimmingCharacters(in: .whitespacesAndNewlines),
              !raw.isEmpty else { return nil }
        let decoded = raw.removingPercentEncoding ?? raw
        guard !decoded.contains("\\") else { return nil }
        let name = (decoded as NSString).lastPathComponent
        guard !name.isEmpty, name != ".", name != ".." else { return nil }
        return name
    }

    /// LIKE 模式的字面量转义（配合 `ESCAPE '\'`）。
    static func escapeLike(_ raw: String) -> String {
        var escaped = ""
        escaped.reserveCapacity(raw.count)
        for character in raw {
            if character == "\\" || character == "%" || character == "_" {
                escaped.append("\\")
            }
            escaped.append(character)
        }
        return escaped
    }

    // MARK: - 引用查询

    /// 聊天图引用数：`messages.attachmentsJson` 里是否出现这个文件名（全局、跨会话）。
    ///
    /// 这里用 LIKE 只做「候选行」筛选（JSON 是文本，没有可用的结构化索引）；
    /// 文件名本身带 UUID，`%name%` 误命中另一张图的可能性可以忽略。
    public func chatImageReferenceCount(fileName: String) throws -> Int {
        guard !fileName.isEmpty else { return 0 }
        let pattern = "%\(Self.escapeLike(fileName))%"
        return Int(try database.scalarInt64(
            #"SELECT COUNT(*) FROM messages WHERE attachmentsJson LIKE ? ESCAPE '\'"#,
            [.text(pattern)]
        ))
    }

    /// 头像引用数：人格、预设与「我的头像」。
    public func avatarReferenceCount(fileName: String, uriString: String? = nil) throws -> Int {
        guard !fileName.isEmpty else { return 0 }
        let escaped = Self.escapeLike(fileName)
        let suffixPattern = "%/\(escaped)"
        let sql = #"""
        SELECT
          (SELECT COUNT(*) FROM personas
             WHERE avatarUri = ?1 OR avatarUri = ?2 OR avatarUri LIKE ?3 ESCAPE '\')
        + (SELECT COUNT(*) FROM persona_presets
             WHERE avatarUri = ?1 OR avatarUri = ?2 OR avatarUri LIKE ?3 ESCAPE '\')
        """#
        // `?1` / `?2` / `?3` 是命名序号，不是「同值复用」：这里按出现顺序绑定三份即可。
        let rawUri = uriString?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        // SQLite 的 `?NNN` 需要每个序号绑定一次；重复使用同一序号时只绑一次。
        let count = try database.scalarInt64(
            sql,
            [.text(rawUri), .text(fileName), .text(suffixPattern)]
        )
        var total = Int(count)
        if let myAvatar = settings?.load().myAvatarUri,
           let myName = Self.fileName(from: myAvatar),
           myName == fileName {
            total += 1
        }
        return total
    }

    /// 这个 URI / 文件名还有没有引用。
    ///
    /// **无法判断时返回 true（保守）**：宁可留下一个孤儿文件，也不能删掉还有引用的图。
    public func isReferenced(fileName: String, uriString: String? = nil) throws -> Bool {
        if try chatImageReferenceCount(fileName: fileName) > 0 { return true }
        if try avatarReferenceCount(fileName: fileName, uriString: uriString) > 0 { return true }
        return false
    }

    /// 便捷入口：传数据库里存的那条 URI（或裸文件名）。
    ///
    /// 文件名解不出来时返回 true：调用方只会因此**不删**文件。
    public func isReferenced(uriString: String?) -> Bool {
        guard let name = Self.fileName(from: uriString) else { return true }
        return (try? isReferenced(fileName: name, uriString: uriString)) ?? true
    }
}
