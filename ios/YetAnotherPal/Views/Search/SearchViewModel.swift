import Foundation

/// 搜索页的状态机。
///
/// 与 `ChatViewModel` 同一套分工：视图只负责画，分页 / 防抖 / 出错都在这儿。
/// 它**不实现检索**——`MessageStore.search` 负责粗筛 + 精排 + 分页，
/// 这一层只做「什么时候查、查第几页、结果怎么攒」。
///
/// 为什么查询要防抖：中文输入法每敲一个字都会改 `query`，
/// 直接每个字符查一次库会让长会话下的输入发涩；220ms 足够让一串拼音落成一个词。
@MainActor
final class SearchViewModel: ObservableObject {

    /// 每页多少条（与 `spec/search` 的界面约定一致）。
    static let pageSize = 30

    // MARK: - 时间筛选

    /// 「全部 / 最近 7 天 / 最近 30 天」。
    enum TimeFilter: String, CaseIterable, Identifiable {
        case all
        case week
        case month

        var id: String { rawValue }

        var title: String {
            switch self {
            case .all: return L("全部时间")
            case .week: return L("最近 7 天")
            case .month: return L("最近 30 天")
            }
        }

        /// 传给 `MessageStore.search` 的下界（含边界）；`nil` = 不限时间。
        func since(now: Int64 = DomainClock.nowMillis) -> Int64? {
            switch self {
            case .all: return nil
            case .week: return now - 7 * 86_400_000
            case .month: return now - 30 * 86_400_000
            }
        }
    }

    // MARK: - 状态

    /// 搜索框里的字。视图直接双向绑定。
    @Published var query = ""
    @Published private(set) var personaFilter: String?
    @Published private(set) var timeFilter: TimeFilter = .all
    @Published private(set) var hits: [SearchHit] = []
    @Published private(set) var hasMore = false
    @Published private(set) var isLoading = false
    @Published private(set) var isLoadingMore = false
    /// 至少成功查过一次（用来区分「还没输入」与「查了但没有结果」）。
    @Published private(set) var searched = false
    @Published private(set) var errorText: String?
    /// 人格筛选的候选（首页那几个人，按更新时间排序）。
    @Published private(set) var personas: [PersonaEntity] = []

    // MARK: - 内部

    private var env: AppEnvironment?
    private var cursor: SearchCursor?
    private var debounceTask: Task<Void, Never>?
    private var searchTask: Task<Void, Never>?

    /// 打分用的「现在」：只在一次查询里取一次，避免筛选边界随毫秒漂。
    private var now: Int64 = 0

    // MARK: - 绑定

    func bind(env: AppEnvironment) {
        guard self.env == nil else { return }
        self.env = env
        personas = (try? env.stores.personas.allOrderedByUpdatedAt()) ?? []
    }

    var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    var hasActiveFilter: Bool { personaFilter != nil || timeFilter != .all }

    /// 筛选条件的中文标题（空 = 全部人格）。
    var personaFilterTitle: String {
        guard let personaFilter else { return L("全部人格") }
        return personas.first(where: { $0.id == personaFilter })?.name ?? L("全部人格")
    }

    // MARK: - 输入 / 筛选

    /// 搜索框内容变了：防抖之后再查。
    func queryChanged() {
        debounceTask?.cancel()
        let trimmed = trimmedQuery
        if trimmed.isEmpty {
            // 清空输入立刻回到「还没搜」的空态，不要等防抖（否则会闪一下上一批结果）。
            reset(resetQueryState: true)
            return
        }
        debounceTask = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: 220_000_000)
            } catch {
                return
            }
            guard let self, !Task.isCancelled else { return }
            self.search()
        }
    }

    func setPersonaFilter(_ personaId: String?) {
        guard personaFilter != personaId else { return }
        personaFilter = personaId
        search()
    }

    func setTimeFilter(_ filter: TimeFilter) {
        guard timeFilter != filter else { return }
        timeFilter = filter
        search()
    }

    // MARK: - 查询

    /// 重新查第一页（输入变化 / 筛选变化 / 重试都走这里）。
    func search() {
        debounceTask?.cancel()
        debounceTask = nil
        searchTask?.cancel()
        guard let env else { return }
        let trimmed = trimmedQuery
        guard !trimmed.isEmpty else {
            reset(resetQueryState: true)
            return
        }

        now = DomainClock.nowMillis
        let personaId = personaFilter
        let since = timeFilter.since(now: now)
        isLoading = true
        isLoadingMore = false
        errorText = nil
        // 先让「正在搜索…」这一帧有机会画出来：数据库查询是同步的，
        // 一个 yield 就足以让 SwiftUI 在结果回来前渲染 loading 态。
        searchTask = Task { [weak self] in
            await Task.yield()
            guard let self, !Task.isCancelled else { return }
            do {
                let page = try env.stores.messages.search(
                    query: trimmed,
                    personaId: personaId,
                    since: since,
                    limit: Self.pageSize
                )
                guard !Task.isCancelled else { return }
                self.hits = page.hits
                self.hasMore = page.hasMore
                self.cursor = page.nextCursor
                self.searched = true
                self.errorText = nil
                #if DEBUG
                DebugSeeding.log("[search] q=\(trimmed) persona=\(personaId ?? "-") since=\(since ?? 0) hits=\(page.hits.count) hasMore=\(page.hasMore)")
                #endif
            } catch {
                guard !Task.isCancelled else { return }
                self.reset(resetQueryState: false)
                self.errorText = L("搜索失败")
                #if DEBUG
                DebugSeeding.log("[search] 失败：\(error.localizedDescription)")
                #endif
            }
            self.isLoading = false
        }
    }

    /// 滚到底时取下一页（同一个查询 + 同一套筛选，游标接着上一页）。
    func loadMoreIfNeeded(currentHitId: String) {
        guard let env, hasMore, !isLoading, !isLoadingMore else { return }
        guard hits.last?.id == currentHitId, let cursor else { return }
        let trimmed = trimmedQuery
        guard !trimmed.isEmpty else { return }
        let personaId = personaFilter
        let since = timeFilter.since(now: now)
        isLoadingMore = true
        Task { [weak self] in
            await Task.yield()
            #if DEBUG
            // 分页验收：让「正在加载更多…」这一帧停久一点，好让 simctl 截到图。
            if DebugSeeding.searchSlowPaging {
                try? await Task.sleep(nanoseconds: 8_000_000_000)
            }
            #endif
            guard let self else { return }
            do {
                let page = try env.stores.messages.search(
                    query: trimmed,
                    personaId: personaId,
                    since: since,
                    limit: Self.pageSize,
                    cursor: cursor
                )
                // 按 id 去重：翻页途中又发了新消息时，游标可能让同一条出现在两页里。
                let existing = Set(self.hits.map(\.id))
                self.hits.append(contentsOf: page.hits.filter { !existing.contains($0.id) })
                self.hasMore = page.hasMore
                self.cursor = page.nextCursor
                #if DEBUG
                DebugSeeding.log("[search] 下一页 +\(page.hits.count) 累计=\(self.hits.count) hasMore=\(page.hasMore)")
                #endif
            } catch {
                self.errorText = L("搜索失败")
            }
            self.isLoadingMore = false
        }
    }

    func retry() { search() }

    /// 清掉结果（不清输入框，除非 `resetQueryState`）。
    private func reset(resetQueryState: Bool) {
        searchTask?.cancel()
        searchTask = nil
        if resetQueryState {
            debounceTask?.cancel()
            debounceTask = nil
            query = ""
        }
        hits = []
        hasMore = false
        cursor = nil
        searched = false
        isLoading = false
        isLoadingMore = false
        errorText = nil
    }
}
