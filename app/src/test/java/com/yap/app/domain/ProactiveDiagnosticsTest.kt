package com.yap.app.domain

import com.yap.app.data.prefs.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「为什么没主动」诊断词汇表的纯 JVM 测试。
 *
 * 这些断言刻意钉的是**排查语义**而不是文案：reason code 稳定、每个 code 都有一句中文说明、
 * 日志行格式固定、已知的 why / tick summary 都能映射到同一个 code。这样 logcat 里看到的
 * `reason=COOL_DOWN` 才能和代码里的判定一一对上，而不是每次改文案就失去意义。
 */
class ProactiveDiagnosticsTest {

    private val cjk = Regex("[\\u4e00-\\u9fff]")

    // ------------------------------------------------------------ 默认值

    @Test
    fun `常驻循环默认是开的`() {
        assertTrue("AppSettings 的默认值必须是 true", AppSettings().agentLoopEnabled)
        // 设置仓库读取时也必须把「没写过」当成 true：否则老用户/新装用户会被默认关掉。
        val repository = source("src/main/java/com/yap/app/data/prefs/SettingsRepository.kt")
        assertTrue(repository.contains("val agentLoop = booleanPreferencesKey(\"agent_loop\")"))
        assertTrue(repository.contains("agentLoopEnabled = prefs[Keys.agentLoop] ?: true"))
    }

    // ------------------------------------------------------------ 词汇表

    @Test
    fun `每个 reason 都有稳定的 code 和一句中文说明`() {
        val reasons = ProactiveDiagnostics.Reason.entries
        assertTrue("词表不该为空", reasons.isNotEmpty())
        assertEquals("code 不能重复", reasons.size, reasons.map { it.code }.toSet().size)
        reasons.forEach { reason ->
            assertEquals("code 就应该是枚举名（日志里可检索）", reason.name, reason.code)
            assertTrue("${reason.code} 缺中文说明", cjk.containsMatchIn(reason.explanation))
            assertTrue("${reason.code} 的说明太短", reason.explanation.length >= 4)
        }
    }

    @Test
    fun `需求点名的 reason code 一个都不能少`() {
        val codes = ProactiveDiagnostics.Reason.entries.map { it.code }.toSet()
        listOf(
            "LOOP_DISABLED", "NO_PERSONA", "SLEEPING", "BUSY", "FIRST_BEAT", "COOL_DOWN",
            "ROLL_MISSED", "TICK_GAP", "DAILY_CAP", "NO_CONFIG", "PARSE_FAILED", "ERROR", "UNKNOWN"
        ).forEach { required ->
            assertTrue("缺少 reason code：$required", required in codes)
        }
    }

    // ------------------------------------------------------------ 日志行

    @Test
    fun `日志行就是 logcat 能直接回答的那一行`() {
        assertEquals(
            "beat: fired=false acted=false reason=COOL_DOWN detail=刚聊完，先安静一会儿",
            ProactiveDiagnostics.logLine(
                fired = false,
                acted = false,
                reason = ProactiveDiagnostics.Reason.COOL_DOWN,
                detail = "刚聊完，先安静一会儿"
            )
        )
        assertEquals(
            "beat: fired=true acted=true reason=ACTED detail=发了一条消息",
            ProactiveDiagnostics.logLine(
                fired = true,
                acted = true,
                reason = ProactiveDiagnostics.Reason.ACTED,
                detail = "发了一条消息"
            )
        )
    }

    @Test
    fun `detail 会压成一行并在为空时用原因说明兜底`() {
        val withNewline = ProactiveDiagnostics.logLine(
            fired = false,
            acted = false,
            reason = ProactiveDiagnostics.Reason.ERROR,
            detail = "第一行\n第二行"
        )
        assertFalse("detail 不能带换行，否则一条日志会被拆散", withNewline.contains("\n"))
        assertTrue(withNewline.contains("第一行 第二行"))

        val fallback = ProactiveDiagnostics.logLine(
            fired = false,
            acted = false,
            reason = ProactiveDiagnostics.Reason.SLEEPING
        )
        assertTrue(
            "没有 detail 时必须能看懂：${ProactiveDiagnostics.Reason.SLEEPING.explanation}",
            fallback.endsWith(ProactiveDiagnostics.Reason.SLEEPING.explanation)
        )
    }

    @Test
    fun `过长的 detail 会截断`() {
        val line = ProactiveDiagnostics.logLine(
            fired = true,
            acted = true,
            reason = ProactiveDiagnostics.Reason.ACTED,
            detail = "长".repeat(500)
        )
        assertTrue("日志行不该被一段模型输出撑爆", line.length < 260)
    }

    // ------------------------------------------------------------ why 映射

    @Test
    fun `心跳早退的每一类 why 都能映射到 code`() {
        val cases = mapOf(
            "读不到设置" to ProactiveDiagnostics.Reason.SETTINGS_UNREADABLE,
            "常驻循环已关闭" to ProactiveDiagnostics.Reason.LOOP_DISABLED,
            "还没有可以对话的人格" to ProactiveDiagnostics.Reason.NO_PERSONA,
            "他在睡觉" to ProactiveDiagnostics.Reason.SLEEPING,
            "他正忙着：在事务所赶方案" to ProactiveDiagnostics.Reason.BUSY,
            "刚聊完，先安静一会儿" to ProactiveDiagnostics.Reason.COOL_DOWN,
            "刚想过，再等等" to ProactiveDiagnostics.Reason.TICK_GAP,
            "他今天不想主动开口" to ProactiveDiagnostics.Reason.DAILY_CAP,
            "今天说够了（3/3）" to ProactiveDiagnostics.Reason.DAILY_CAP,
            "这一轮没想成" to ProactiveDiagnostics.Reason.ERROR,
            "想的时候出错了：timeout" to ProactiveDiagnostics.Reason.ERROR
        )
        cases.forEach { (why, expected) ->
            assertEquals("why=$why", expected, ProactiveDiagnostics.fromBeatWhy(why))
        }
    }

    @Test
    fun `空 why 不会被误解成掷骰没中`() {
        // 老接口里 FIRST_BEAT 与 ROLL_MISSED 都返回空串，光看 why 分不出来：
        // 新代码在每个 return 显式带 code，映射层只把空串当 UNKNOWN，不猜。
        assertEquals(ProactiveDiagnostics.Reason.UNKNOWN, ProactiveDiagnostics.fromBeatWhy(""))
        assertEquals(ProactiveDiagnostics.Reason.UNKNOWN, ProactiveDiagnostics.fromBeatWhy("   "))
        assertEquals(ProactiveDiagnostics.Reason.UNKNOWN, ProactiveDiagnostics.fromBeatWhy("没见过的原因"))
    }

    // ------------------------------------------------------------ tick summary 映射

    @Test
    fun `tick 的 summary 能说清想了但为什么没发生`() {
        val cases = mapOf(
            "常驻循环已关闭" to ProactiveDiagnostics.Reason.LOOP_DISABLED,
            "还没有可以对话的人格" to ProactiveDiagnostics.Reason.NO_PERSONA,
            "他在睡觉" to ProactiveDiagnostics.Reason.SLEEPING,
            "还没有可用的 API 配置" to ProactiveDiagnostics.Reason.NO_CONFIG,
            "这次没想清楚" to ProactiveDiagnostics.Reason.PARSE_FAILED,
            "上一次思考还没结束，这一拍跳过" to ProactiveDiagnostics.Reason.TICK_BUSY,
            "想的时候出错了：HTTP 500" to ProactiveDiagnostics.Reason.ERROR,
            "消息没发出去" to ProactiveDiagnostics.Reason.ERROR,
            "本想说话，忍住了（你们正聊着，不用他额外开口）" to ProactiveDiagnostics.Reason.HELD_BACK,
            "平静；什么都没做" to ProactiveDiagnostics.Reason.NO_ACTION
        )
        cases.forEach { (summary, expected) ->
            assertEquals("summary=$summary", expected, ProactiveDiagnostics.fromTickSummary(summary))
        }
        assertEquals(ProactiveDiagnostics.Reason.UNKNOWN, ProactiveDiagnostics.fromTickSummary(""))
        assertEquals(ProactiveDiagnostics.Reason.UNKNOWN, ProactiveDiagnostics.fromTickSummary("发了一条消息"))
    }

    @Test
    fun `掷中之后先看 tick 的事实再按 acted 兜底`() {
        // 没掷中：只看 why。
        assertEquals(
            ProactiveDiagnostics.Reason.COOL_DOWN,
            ProactiveDiagnostics.classify(fired = false, acted = false, why = "刚聊完，先安静一会儿")
        )
        // 掷中但没配置：summary 说事实，不能被 acted=false 吞成 NO_ACTION。
        assertEquals(
            ProactiveDiagnostics.Reason.NO_CONFIG,
            ProactiveDiagnostics.classify(
                fired = true, acted = false, why = "", tickSummary = "还没有可用的 API 配置"
            )
        )
        // 掷中但被分寸拦住：与「真的发出去」必须分开。
        assertEquals(
            ProactiveDiagnostics.Reason.HELD_BACK,
            ProactiveDiagnostics.classify(
                fired = true, acted = true, why = "", tickSummary = "本想说话，忍住了（刚说完没多久）"
            )
        )
        // 正常做了点事。
        assertEquals(
            ProactiveDiagnostics.Reason.ACTED,
            ProactiveDiagnostics.classify(fired = true, acted = true, why = "", tickSummary = "发了一条消息")
        )
        // 掷中、模型决定什么都不做。
        assertEquals(
            ProactiveDiagnostics.Reason.NO_ACTION,
            ProactiveDiagnostics.classify(fired = true, acted = false, why = "", tickSummary = "什么都没做")
        )
        // tick 抛异常（outcome == null）时 why 是固定那句话。
        assertEquals(
            ProactiveDiagnostics.Reason.ERROR,
            ProactiveDiagnostics.classify(fired = true, acted = false, why = "这一轮没想成")
        )
        // 没有任何线索时按 acted 落回。
        assertEquals(
            ProactiveDiagnostics.Reason.ACTED,
            ProactiveDiagnostics.classify(fired = true, acted = true, why = "", tickSummary = "")
        )
        assertEquals(
            ProactiveDiagnostics.Reason.NO_ACTION,
            ProactiveDiagnostics.classify(fired = true, acted = false, why = "", tickSummary = "")
        )
    }

    // ------------------------------------------------------------ 读失败不要猜

    @Test
    fun `读设置失败时保持现状而不是当成关闭`() {
        assertEquals(ProactiveDiagnostics.LoopSwitch.RUN, ProactiveDiagnostics.loopSwitch(true))
        assertEquals(ProactiveDiagnostics.LoopSwitch.STOP, ProactiveDiagnostics.loopSwitch(false))
        assertEquals("读不出来 ≠ 用户关掉了", ProactiveDiagnostics.LoopSwitch.KEEP, ProactiveDiagnostics.loopSwitch(null))
    }

    @Test
    fun `Worker 读设置失败时什么都不做而不是退回老行为`() {
        assertEquals(
            ProactiveDiagnostics.WorkerBranch.RUN_AGENT_LOOP,
            ProactiveDiagnostics.workerBranch(true)
        )
        assertEquals(
            ProactiveDiagnostics.WorkerBranch.RUN_LEGACY_RULES,
            ProactiveDiagnostics.workerBranch(false)
        )
        assertEquals(
            "读不出来时既不能猜开也不能猜关",
            ProactiveDiagnostics.WorkerBranch.SKIP,
            ProactiveDiagnostics.workerBranch(null)
        )
    }

    // ------------------------------------------------------------ 不会长期卡死

    @Test
    fun `忙碌状态到 until 就自动失效 不会永久卡住`() {
        val now = 1_700_000_000_000L
        val hour = 60 * 60_000L
        val busy = PersonaActivity(
            kind = ActivityKind.WORK,
            activity = "在事务所赶方案",
            until = now + hour,
            availability = ActivityAvailability.BUSY
        )
        assertEquals(ActivityAvailability.BUSY, busy.availabilityAt(now))
        assertEquals(
            "活动过期就回到 FREE，不需要定时任务清理",
            ActivityAvailability.FREE,
            busy.availabilityAt(now + hour + 1)
        )
        // 工具参数把活动夹在 15–480 分钟，最长也只是「忙到今晚」，不可能是永久状态。
        assertTrue(busy.until - now <= 8 * hour)
    }

    @Test
    fun `冷却期按墙钟走 十分钟后一定结束`() {
        val reset = 1_700_000_000_000L
        assertTrue(Heartbeat.minutesBetween(reset, reset + 9 * 60_000L) < Heartbeat.COOL_DOWN_MINUTES)
        assertTrue(
            "到第 10 分钟就不再是冷却",
            Heartbeat.minutesBetween(reset, reset + 10 * 60_000L) >= Heartbeat.COOL_DOWN_MINUTES
        )
        assertTrue(
            "过了十小时当然也早就结束",
            Heartbeat.minutesBetween(reset, reset + 600 * 60_000L) > Heartbeat.COOL_DOWN_MINUTES
        )
    }

    @Test
    fun `睡眠窗口到点会醒 不会永久停在睡着`() {
        val sleep = SleepPolicy.DEFAULT_SLEEP_MINUTES
        val wake = SleepPolicy.DEFAULT_WAKE_MINUTES
        val asleepMinute = 23 * 60 + 40
        assertTrue("23:40 在默认作息里确实睡着", SleepPolicy.inWindow(asleepMinute, sleep, wake))
        assertFalse("08:00 必须已经醒了", SleepPolicy.inWindow(wake, sleep, wake))
        assertTrue(
            "距离醒来一定有上限，下一拍不会永远落在 SLEEPING",
            SleepPolicy.minutesUntilWake(asleepMinute, sleep, wake) <= 9 * 60
        )
    }

    // ------------------------------------------------------------ 工具

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("../$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("找不到 $relative（工作目录=${File("").absolutePath}）")
    }
}
