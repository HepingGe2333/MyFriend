package com.yap.app.domain

/**
 * 三个抽屉：他是什么样的人 / 关于你 / 共同经历与约定。
 *
 * 这个枚举原本住在 `MemoryScreening.kt` 里。抽出来的原因只有一个：
 * `MemoryLifecycle` 需要它，而 `MemoryScreening` 依赖 kotlinx-serialization，
 * 导致「纯领域代码」没法被零依赖的 JVM 服务（`agentd`）直接复用。
 * 拆开之后，`agentd` 只依赖 `:agentcore`（`MemoryDrawer.kt` + `MemoryLifecycle.kt` 都在里面），
 * 不用把序列化库拖进去；Android 端行为不变（同包同类型）。
 *
 * 字符串原名（SELF / USER / EVENT）会出现在数据库列、备份文件和模型输出里，
 * 所以**不要**重命名这些枚举常量。
 */
enum class MemoryDrawer {
    SELF,
    USER,
    EVENT
}
