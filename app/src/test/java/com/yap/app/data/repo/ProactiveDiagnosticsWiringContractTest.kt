package com.yap.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 主动链路的**接线契约**（0.13 真机反馈「他不主动找我」）。
 *
 * 纯逻辑（reason code / 日志行 / 三态开关）在 `ProactiveDiagnosticsTest`；这里只钉
 * 「该打的日志真的打了、该修的闸门真的修了」——这类问题最典型的形态是：
 * 纯函数全绿，但 `beat()` 早退时根本没走到它。
 *
 * 这里不做 Android 运行时验证（本工程没有 Robolectric）：靠源码契约拦住
 * 1) 心跳每个 return 都经过 `stop()`（统一记一条 debug 日志）；
 * 2) 首次起点写盘后同步更新内存快照（修掉 FIRST_BEAT 连打）；
 * 3) 读设置失败不再被当成「关闭」；
 * 4) 前台服务/WorkManager 排期失败至少留一条日志；
 * 5) 曲线常量没被顺手改掉（不改变既有频率语义）。
 */
class ProactiveDiagnosticsWiringContractTest {

    private val heartbeat = source("data/repo/HeartbeatController.kt")
    private val controller = source("service/AgentLoopController.kt")
    private val service = source("service/AgentLoopService.kt")
    private val proactiveWorker = source("data/repo/ProactiveWorker.kt")
    private val tickWorker = source("data/repo/AgentTickWorker.kt")

    // ------------------------------------------------------------------ 心跳日志

    @Test
    fun `心跳每个 return 都经过 stop 并打 debug 日志`() {
        assertTrue("要 import BuildConfig（debug 守卫）", heartbeat.contains("import com.yap.app.BuildConfig"))
        assertTrue("要 import android.util.Log", heartbeat.contains("import android.util.Log"))
        assertTrue(
            "tag 必须是 Heartbeat：adb logcat -s Heartbeat",
            heartbeat.contains("private const val HEARTBEAT_LOG_TAG = \"Heartbeat\"")
        )
        assertTrue("日志必须只在 debug 构建里打", heartbeat.contains("if (BuildConfig.DEBUG)"))
        assertTrue("日志内容走统一的 logLine", heartbeat.contains("ProactiveDiagnostics.logLine("))
        assertTrue("日志真的写进 logcat", heartbeat.contains("Log.d("))
        assertEquals(
            "只有统一出口 stop() 里可以 return Beat(...)，早退一律走 stop()",
            1,
            heartbeat.lines().count { it.trimStart().startsWith("return Beat(") }
        )
        assertTrue(
            "统一出口自己返回 Beat",
            heartbeat.contains("return Beat(fired = fired, acted = acted, summary = summary, why = why)")
        )
        assertTrue(
            "至少十道闸门都要带原因",
            Regex("return stop\\(").findAll(heartbeat).count() >= 11
        )
    }

    @Test
    fun `每一类早退都带自己的 reason code`() {
        listOf(
            "Reason.SETTINGS_UNREADABLE",
            "Reason.LOOP_DISABLED",
            "Reason.NO_PERSONA",
            "Reason.SLEEPING",
            "Reason.BUSY",
            "Reason.FIRST_BEAT",
            "Reason.COOL_DOWN",
            "Reason.ROLL_MISSED",
            "Reason.TICK_GAP",
            "Reason.DAILY_CAP"
        ).forEach { reason ->
            assertTrue("心跳缺少原因：$reason", heartbeat.contains(reason))
        }
        assertTrue(
            "掷中之后要按 tick 的事实分类（没配置/被忍住/什么都没做/真的做了）",
            heartbeat.contains("ProactiveDiagnostics.classify(")
        )
    }

    @Test
    fun `日志行真的带 fired acted reason detail 四个字段`() {
        val diagnostics = source("domain/ProactiveDiagnostics.kt")
        assertTrue(diagnostics.contains("beat: fired=\$fired acted=\$acted reason=\${reason.code} detail=\$text"))
    }

    // ------------------------------------------------------------------ FIRST_BEAT 修复

    @Test
    fun `首次起点写盘后同时更新内存快照`() {
        val firstBeat = between(
            heartbeat,
            "if (snapshot.resetAt <= 0L) {",
            "val minutes = Heartbeat.minutesBetween"
        )
        assertTrue(
            "第一次跑起来要先把起点写进设置",
            firstBeat.contains("settingsRepository.setHeartbeatState(now, settings.heartbeatLastFiredAt)")
        )
        assertTrue(
            "写盘后必须更新 cached，否则 60 秒 TTL 内会重复走 FIRST_BEAT",
            firstBeat.contains("cached = snapshot.copy(resetAt = now, at = now)")
        )
        assertTrue(
            "缓存更新要在 return 之前",
            firstBeat.indexOf("cached = snapshot.copy(resetAt = now") <
                firstBeat.indexOf("return stop(Reason.FIRST_BEAT")
        )
        assertTrue(
            "写盘失败要留进程内起点，否则快照过期后会再一次 FIRST_BEAT、永远不掷骰子",
            firstBeat.contains("if (!saved) unsavedResetAt = now")
        )
        assertTrue(
            "快照要能把进程内起点算进去（持久化的值优先，它缺席时兜底）",
            heartbeat.contains("unsavedResetAt,")
        )
        assertTrue(
            "掷中之后写盘失败也要接上进程内起点",
            heartbeat.contains("if (!savedReset) unsavedResetAt = finishedAt")
        )
    }

    // ------------------------------------------------------------------ 频率语义

    @Test
    fun `频率与冷却语义一个字都没动`() {
        assertTrue("心跳仍是 30 秒一拍", heartbeat.contains("const val BEAT_INTERVAL_MS = 30_000L"))
        assertTrue("低电量仍是 2 分钟一拍", heartbeat.contains("const val LOW_BATTERY_BEAT_MS = 2 * 60_000L"))
        assertTrue("低电量阈值仍是 20%", heartbeat.contains("const val LOW_BATTERY_PERCENT = 20"))
        assertTrue("两次思考下限仍是 5 分钟", heartbeat.contains("const val FIRST_TICK_GAP_MS = 5 * 60_000L"))
        assertTrue("冷却仍走 Heartbeat.COOL_DOWN_MINUTES", heartbeat.contains("Heartbeat.COOL_DOWN_MINUTES"))
        assertTrue("概率仍走 Heartbeat.probability", heartbeat.contains("Heartbeat.probability("))
        assertTrue("掷骰仍走 Heartbeat.shouldFire", heartbeat.contains("Heartbeat.shouldFire("))
        assertTrue("每日上限仍走 agentCore.proactiveCap", heartbeat.contains("agentCore.proactiveCap("))
    }

    // ------------------------------------------------------------------ 开关读取

    @Test
    fun `读设置失败不再被当成关闭`() {
        assertFalse(
            "不能再有 getOrDefault(false)：那会把一次读失败当成用户关掉循环",
            controller.contains(".getOrDefault(false)")
        )
        assertTrue("读失败要能区分出来", controller.contains(".getOrNull()"))
        assertTrue("读失败时保持现状", controller.contains("ProactiveDiagnostics.LoopSwitch.KEEP"))
        assertTrue("读失败要留日志", controller.contains("Log.w("))
    }

    @Test
    fun `后台兜底真的排上了且失败有日志`() {
        assertTrue(
            "15 分钟周期任务仍用唯一名字 + KEEP（不会重复排、也不会丢）",
            controller.contains("enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)")
        )
        assertTrue(controller.contains("PeriodicWorkRequestBuilder<AgentTickWorker>(15, TimeUnit.MINUTES)"))
        assertTrue(controller.contains("const val WORK_NAME = \"yap-agent-tick\""))
        assertTrue("排期失败必须可见", controller.contains("Log.w("))
        assertTrue("排期成功在 debug 下留一行", controller.contains("Log.d("))
    }

    @Test
    fun `前台服务启动失败会留下后台限制线索`() {
        val start = service.substringAfter("fun start(context: Context)")
        assertTrue(start.contains("ContextCompat.startForegroundService(context, intent)"))
        assertTrue("不能静默吞掉：Android 12+ 后台起前台服务会抛异常", start.contains("Log.w("))
        assertTrue(service.contains("private const val LOG_TAG = \"AgentLoopService\""))
    }

    @Test
    fun `ProactiveWorker 读设置失败时不猜开关状态`() {
        assertFalse(
            "getOrDefault(false) 会退回老行为（绕过模型直接按规则发消息）",
            proactiveWorker.contains(".getOrDefault(false)")
        )
        assertTrue(proactiveWorker.contains("ProactiveDiagnostics.workerBranch("))
        assertTrue("读失败要留日志", proactiveWorker.contains("Log.w("))
        assertTrue("取消要继续向上抛，不能被当成读失败", proactiveWorker.contains("CancellationException"))
    }

    @Test
    fun `WorkManager 兜底运行时有 debug 痕迹`() {
        assertTrue(tickWorker.contains("BuildConfig.DEBUG"))
        assertTrue(tickWorker.contains("Log.d("))
        assertTrue(tickWorker.contains("\"AgentTickWorker\""))
    }

    // ------------------------------------------------------------------ 后台限制说明

    @Test
    fun `设置页把后台限制说明写清楚了`() {
        val zh = source("res/values/strings.xml")
        assertTrue("必须有「常驻后台」开关", zh.contains("<string name=\"settings_agent_loop\">常驻后台</string>"))
        assertTrue("开关说明要讲清后台行为", zh.contains("settings_agent_loop_desc") && zh.contains("在后台继续想事情"))
        assertTrue("要有后台运行限制一行", zh.contains("<string name=\"settings_battery_limit\">后台运行限制</string>"))
        assertTrue("要说明系统会冻结后台应用", zh.contains("settings_battery_limit_desc") && zh.contains("冻结"))
        assertTrue("要提醒自启动 / 后台活动", zh.contains("settings_background_note") && zh.contains("自启动"))
        val screen = source("ui/screens/settings/SettingsScreen.kt")
        assertTrue(screen.contains("R.string.settings_battery_limit"))
        assertTrue(screen.contains("R.string.settings_background_note"))
    }

    // ------------------------------------------------------------------ 工具

    private fun source(relative: String): String {
        val root = projectRoot()
        val file = when {
            relative.startsWith("src/") -> File(root, relative)
            relative.startsWith("res/") -> File(root, "src/main/$relative")
            else -> File(root, "src/main/java/com/yap/app/$relative")
        }
        assertTrue("找不到源文件：${file.path}", file.isFile)
        return file.readText()
    }

    private fun projectRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到工程根目录：${File(".").absolutePath}")
    }

    private fun between(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        require(start >= 0) { "找不到 $startMarker" }
        val end = text.indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "找不到 $endMarker" }
        return text.substring(start, end)
    }
}
