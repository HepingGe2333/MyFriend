import Foundation

/// Debug 构建里的「内存快照」日志。
///
/// 为什么需要它：这一版做了图片与头像，最怕的是「看不见的内存变胖」。
/// Instruments 在这个环境里跑不起来，`simctl` 也拿不到 App 的内存数字，
/// 于是退一步：在 Debug 构建里自己读 `task_vm_info.phys_footprint`，
/// 在**启动后 30 秒**与**进入聊天 30 秒**各写一条到 `Documents/debug.log`。
///
/// 读的是 `phys_footprint` 而不是 `resident_size`：前者才是系统 Jetsam 真正看的数字
/// （含压缩、含脏页），也是 Xcode 内存仪表上显示的那个。
///
/// Release 包里整个文件不存在（下方 `#else` 是同名的空实现）。
#if DEBUG
import Darwin

enum DebugMemoryLog {

    /// 同一类快照每次启动只排一次（视图可能反复 `onAppear`）。
    private static var bootScheduled = false
    private static var chatScheduled = false
    private static let lock = NSLock()

    /// 启动后 30 秒：这时候首屏、数据库、心跳都已经跑起来了，是「空转」的基线。
    static func scheduleBootSnapshot() {
        lock.lock()
        let first = !bootScheduled
        bootScheduled = true
        lock.unlock()
        guard first else { return }

        snapshot("launch")
        DispatchQueue.main.asyncAfter(deadline: .now() + 30) {
            snapshot("launch+30s")
        }
    }

    /// 进入聊天后 30 秒：滚过一段历史、解过几张图之后再打一条。
    static func scheduleChatSnapshot() {
        lock.lock()
        let first = !chatScheduled
        chatScheduled = true
        lock.unlock()
        guard first else { return }

        snapshot("chat")
        DispatchQueue.main.asyncAfter(deadline: .now() + 30) {
            snapshot("chat+30s")
        }
    }

    /// 当前进程的物理占用（字节）。拿不到时返回 0，日志里会写 0.0 MB —— 不假装有数。
    static func footprintBytes() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(
            MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size
        )
        let result = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { rebound in
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), rebound, &count)
            }
        }
        return result == KERN_SUCCESS ? UInt64(info.phys_footprint) : 0
    }

    /// 缩略图缓存上限（启动时打一条，方便对照设备内存）。
    static func cacheLimitBytes() -> Int {
        ImageThumbnailCache.shared.totalCostLimit
    }

    static func logText(_ bytes: UInt64) -> String {
        String(format: "%.1f MB", Double(bytes) / 1024.0 / 1024.0)
    }

    static func snapshot(_ tag: String) {
        DebugSeeding.log(
            "[memory] \(tag) phys_footprint=\(logText(footprintBytes()))"
                + " cache_limit=\(logText(UInt64(cacheLimitBytes())))"
                + " attachments_on_disk=\(logText(UInt64(MediaStorage.shared.totalBytesOnDisk())))"
        )
    }
}
#else
enum DebugMemoryLog {
    static func scheduleBootSnapshot() {}
    static func scheduleChatSnapshot() {}
}
#endif
