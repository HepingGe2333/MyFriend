package com.myfriend.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltTestApplication
import java.io.File

/**
 * 仪器化测试的 runner。
 *
 * 两件事，都只在测试 APK 里成立，不影响发布包：
 *
 * 1. **换 Application**：`@HiltAndroidTest` 只认 Hilt 的 [HiltTestApplication]（官方约定），
 *    这样 UI 行为测试就能注入 App 真实的依赖图（真 Room、真 `ChatRepository`），
 *    不需要给生产代码加测试用的构造口子。除 Application 之外，本 runner 与被替换掉的
 *    `AndroidJUnitRunner` 行为一致，迁移 / 块维护那批测试照旧跑。
 * 2. **清理旧状态**：UI 断言对「会话里到底有几条消息」「人格是不是正好 3 个」很敏感，
 *    上一次跑剩下的库会让它们变成噪声。这里在 Application 创建之前删掉库文件与偏好目录
 *    （此时 SQLite 与 DataStore 都还没被打开，不存在「删正在用的文件」）。
 *
 * 还有一个**必须配套**的前提：目标 App 的 `BootReceiver` 是 `@AndroidEntryPoint` 接收器，
 * 覆盖安装后那条 `MY_PACKAGE_REPLACED` 会在 Hilt 组件创建之前把它唤起，进程直接崩。
 * 处理在 `app/src/debug/AndroidManifest.xml`：debug 构建不注册这个接收器
 * （release 不受影响）。测试进程里再把它停用一次既没必要也做不到——
 * 组件已不存在，`setComponentEnabledSetting` 只会抛 "Unknown component"。
 *
 * 清理只发生在**进程启动时**（一次 instrumentation 运行一次），
 * 所以各测试仍然各自播种自己的数据，不依赖执行顺序。
 */
class HiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
        clearInstalledState(context)
        val app = super.newApplication(cl, HiltTestApplication::class.java.name, context)
        // 测试进程里没有生产环境的 WorkManager 初始化（manifest 里被去掉了默认 initializer），
        // 「删除全部数据」会走到 ProactiveScheduler.cancelAll() → WorkManager.getInstance()。
        // 用一份最基本的配置初始化即可：这些测试只做取消排期，不需要真实的 WorkerFactory。
        runCatching {
            if (!WorkManager.isInitialized()) {
                WorkManager.initialize(app, Configuration.Builder().build())
            }
        }
        return app
    }

    private fun clearInstalledState(context: Context?) {
        val ctx = context ?: return
        runCatching {
            ctx.deleteDatabase(com.myfriend.app.data.local.MyFriendDatabase.NAME)
            // deleteDatabase 只删数据库文件本身；-wal / -shm 与其它遗留库一起清掉。
            File(ctx.filesDir.parentFile, "databases").deleteRecursively()
            // DataStore Preferences（主题 / 是否已播种 / 常驻后台…）：测试要拿到「新装」口径。
            File(ctx.filesDir, "datastore").deleteRecursively()
        }
    }
}
