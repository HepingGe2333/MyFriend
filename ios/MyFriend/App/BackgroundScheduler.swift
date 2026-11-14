import BackgroundTasks
import Foundation

// 后台调度的注册与「补算」。
//
// ## 与 Android 的差别（不假装一样）
//
// Android 用前台服务每 30 秒拍一次，系统允许它常驻；iOS 不允许。所以这边只有两种机会：
// 1. `BGAppRefreshTask`：系统择机给一小段时间（通常几十分钟到几小时一次）；
// 2. `BGProcessingTask`：更少，但可以要求充电 / 联网，用来做「每日整理」。
//
// 因此 iOS 这边**不承诺实时**：系统给机会就跑一拍，不给就等 App 自己回到前台时补算
//（`BackgroundCatchUp.plan` 决定补什么）。判据在 Kit 里（有单测），这里只负责注册、提交请求、
// 调 `AppEnvironment` 的活，以及把所有失败吞成一行日志。
//
// ## 失败兜底
//
// - Info.plist 里的 `BGTaskSchedulerPermittedIdentifiers` 与这里两个常量必须一致（已一致）；
// - 模拟器上系统不会执行这两种任务，注册也拿不到机会：直接跳过注册，前台功能不受影响；
// - 任何 `submit` 失败都只记日志（额度用完、被系统取消、标识符不认……都不是用户的问题）。
@MainActor
final class BackgroundScheduler {

    static let shared = BackgroundScheduler()

    /// 必须与 `Config/Info.plist` 的 `BGTaskSchedulerPermittedIdentifiers` 逐字一致。
    static let refreshIdentifier = "com.myfriend.app.ios.refresh"
    static let processingIdentifier = "com.myfriend.app.ios.processing"

    private weak var env: AppEnvironment?
    private var registered = false

    private init() {}

    /// 装配层在根视图起来之后调：拿到 `AppEnvironment` 并排下一次后台时间。
    func attach(env: AppEnvironment) {
        self.env = env
        scheduleNext()
    }

    /// App 启动时调一次（必须在启动完成前）。重复调用会被忽略（重复注册同一个标识符会崩）。
    func register() {
        guard !registered else { return }
        if isSimulator {
            // 模拟器：注册可以过，但系统永远不给时间片；跳过以免误导排查。
            DebugSeeding.log("[bg] 模拟器上不注册 BGTask（系统不会执行），只靠前台补算")
            return
        }
        registered = true
        let refresh = BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.refreshIdentifier,
            using: nil
        ) { task in
            // 系统在后台线程回调：把活挪回主线程（AppEnvironment 是 @MainActor）。
            Task { @MainActor in
                await Self.shared.handleRefresh(task)
            }
        }
        let processing = BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.processingIdentifier,
            using: nil
        ) { task in
            Task { @MainActor in
                await Self.shared.handleProcessing(task)
            }
        }
        DebugSeeding.log("[bg] 注册后台任务 refresh=\(refresh) processing=\(processing)")
    }

    // MARK: - 提交下一次

    /// 排下一次「心跳」时间片。系统可能不给，给了也可能很晚——这都正常。
    func scheduleNext() {
        guard !isSimulator else { return }
        scheduleRefresh()
        scheduleProcessing()
    }

    private func scheduleRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: Self.refreshIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: BackgroundCatchUp.refreshEarliestSeconds)
        submit(request, label: "refresh")
    }

    private func scheduleProcessing() {
        let request = BGProcessingTaskRequest(identifier: Self.processingIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: BackgroundCatchUp.processingEarliestSeconds)
        // 每日整理只碰本机数据库、只在配了 API 时才调一次模型：不强制充电，也不强制联网
        //（联网失败会在 `MemoryConsolidator` 里变成 `.failed`，不写整理留痕）。
        request.requiresExternalPower = false
        request.requiresNetworkConnectivity = false
        submit(request, label: "processing")
    }

    private func submit(_ request: BGTaskRequest, label: String) {
        do {
            try BGTaskScheduler.shared.submit(request)
            DebugSeeding.log("[bg] 已提交 \(label) 请求")
        } catch {
            // 后台刷新额度用完（iOS 有配额）、模拟器、或系统拒绝：都不是用户的问题。
            DebugSeeding.log("[bg] 提交 \(label) 失败：\(error.localizedDescription)")
        }
    }

    // MARK: - 系统给的时间片

    /// 心跳时间片：补一次「想一下」，然后排下一次。
    ///
    /// 完成上报的语义按 0.10.2 审计 P1-4 修正：
    /// - **无事可做也报 success**（心跳绝大多数拍本来就什么都不做，报失败会被系统削减调度）；
    /// - 时间片到期时 `expirationHandler` 自己报一次失败并保证只报一次，
    ///   不让进程因为「没上报完成」被杀。
    private func handleRefresh(_ task: BGTask) async {
        scheduleRefresh()
        guard let env else {
            DebugSeeding.log("[bg] refresh 没有 AppEnvironment，直接上报失败")
            task.setTaskCompleted(success: false)
            return
        }
        let reporter = BackgroundTaskCompletionReporter { success in
            task.setTaskCompleted(success: success)
        }
        // 系统收回时间片时取消这次活；`Task.cancel()` 是线程安全的，正合适。
        let work = Task { await env.runBackgroundTick() }
        task.expirationHandler = {
            work.cancel()
            // 到期就是「这次没跑完」：立刻上报，避免整个时间片都没上报而被系统杀掉进程。
            _ = reporter.expire()
        }
        let acted = await work.value
        if reporter.complete() {
            DebugSeeding.log("[bg] refresh 跑完了（这一次\(acted ? "做了点事" : "无事可做")，仍按成功上报）")
        }
    }

    /// 每日整理时间片：记忆整理 + 清掉坏掉的旧摘要 + 顺手补一次心跳。
    private func handleProcessing(_ task: BGTask) async {
        scheduleProcessing()
        guard let env else {
            DebugSeeding.log("[bg] processing 没有 AppEnvironment，直接上报失败")
            task.setTaskCompleted(success: false)
            return
        }
        let reporter = BackgroundTaskCompletionReporter { success in
            task.setTaskCompleted(success: success)
        }
        let work = Task { await env.runDailyConsolidation() }
        task.expirationHandler = {
            work.cancel()
            _ = reporter.expire()
        }
        let consolidated = await work.value
        if work.isCancelled {
            // 到期已经被 `expirationHandler` 报过了（它可能报得比这里早），这里不再补活、不再上报。
            return
        }
        _ = env.repairHistorySummariesIfNeeded()
        _ = await env.runBackgroundTick()
        if reporter.complete() {
            DebugSeeding.log("[bg] processing 跑完了（这一次\(consolidated ? "整理过" : "无事可做")，仍按成功上报）")
        }
    }

    private var isSimulator: Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }
}
