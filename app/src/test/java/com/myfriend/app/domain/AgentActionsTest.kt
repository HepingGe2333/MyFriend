package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * AgentActions 的纯领域测试：解析容错、动作归一化、时间窗口、提示词。
 *
 * 时间全部用固定的 Asia/Shanghai 时区与固定 now，保证在任何机器上结果一致。
 */
class AgentActionsTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val minute = 60_000L
    private val hour = 3_600_000L

    /** 2026-11-13 18:05，周五傍晚。 */
    private val now = at(2026, 11, 13, 18, 5)

    private fun at(year: Int, month: Int, day: Int, hourOfDay: Int, minuteOfHour: Int): Long =
        ZonedDateTime.of(year, month, day, hourOfDay, minuteOfHour, 0, 0, zone)
            .toInstant()
            .toEpochMilli()

    private fun local(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)

    private fun clock(ms: Long): String =
        local(ms).format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT))

    private fun window(text: String, from: Long = now, hours: Int = 2): AgentWindow =
        requireNotNull(AgentDecisionCodec.windowFor(text, from, zone, hours)) { "解析不出窗口：$text" }

    private fun assertWindow(win: AgentWindow, start: String, end: String) {
        assertEquals("窗口起点，实际 ${clock(win.earliestAt)}", start, clock(win.earliestAt))
        assertEquals("窗口终点，实际 ${clock(win.latestAt)}", end, clock(win.latestAt))
    }

    // -----------------------------------------------------------------------
    // 1. 解析：代码块、裸 JSON、前后废话
    // -----------------------------------------------------------------------

    @Test
    fun parsesPlainJsonWithThoughtAndActions() {
        val raw = """
            {
              "thought": "半夜了，他应该睡了，我不吵他。",
              "actions": [
                {"type": "nothing"},
                {"type": "message", "text": "刚看到一条新闻，突然想起你昨天说的那件事。"},
                {"type": "browse", "query": "本地天气 明天"}
              ]
            }
        """.trimIndent()

        val decision = AgentDecisionCodec.parse(raw, now, zone)

        assertNotNull("裸 JSON 应该能解析", decision)
        assertEquals("半夜了，他应该睡了，我不吵他。", decision!!.thought)
        assertEquals(3, decision.actions.size)
        assertTrue(decision.actions[0] is AgentAction.Nothing)
        assertTrue(decision.actions[1] is AgentAction.Message)
        assertEquals(
            "刚看到一条新闻，突然想起你昨天说的那件事。",
            (decision.actions[1] as AgentAction.Message).text
        )
        assertEquals("本地天气 明天", (decision.actions[2] as AgentAction.Browse).query)
    }

    @Test
    fun parsesJsonWrappedInCodeFenceAndProse() {
        val raw = """
            我刚刚醒来，想了一下：

            ```json
            {"thought": "今晚早点睡，明天再问。", "actions": [{"type": "nothing"}]}
            ```

            就先这样。
        """.trimIndent()

        val decision = AgentDecisionCodec.parse(raw, now, zone)

        assertNotNull(decision)
        assertEquals("今晚早点睡，明天再问。", decision!!.thought)
        assertEquals(listOf(AgentAction.Nothing), decision.actions)
    }

    @Test
    fun parsesFirstJsonObjectAndSkipsChineseBracesInProse() {
        val raw = "先想了 {这件事} 一下，然后决定：{\"thought\":\"问一句\",\"actions\":[{\"type\":\"message\",\"text\":\"在吗\"}]} 结束"

        val decision = AgentDecisionCodec.parse(raw, now, zone)

        assertEquals("问一句", decision?.thought)
        assertEquals(1, decision?.actions?.size)
        assertTrue(decision!!.actions.single() is AgentAction.Message)
    }

    @Test
    fun parsesFullModelSampleWithAllActionTypes() {
        val raw = """
            ```json
            {
              "thought": "半夜了，他应该睡了，我不吵他。刚才看到的那条新闻还没跟他说。",
              "actions": [
                {"type": "nothing"},
                {"type": "message", "text": "刚看到一条新闻，突然想起你昨天说的那件事。"},
                {"type": "browse", "query": "本地天气 明天"},
                {"type": "remember", "content": "他最近在准备一个重要的项目", "drawer": "USER", "importance": 3},
                {"type": "intent", "at": "明早 8 点前后", "note": "问问他睡得怎么样", "hint": "轻声，别追问"},
                {"type": "persona_patch", "patch": {"bond": "更懂得先陪着"}, "summary": "更懂得先陪着"},
                {"type": "quiet_hours", "sleep_at": "23:30", "wake_at": "08:10", "reason": "最近想早点睡"}
              ]
            }
            ```
        """.trimIndent()

        val decision = AgentDecisionCodec.parse(raw, now, zone)!!

        assertEquals(7, decision.actions.size)
        val intent = decision.actions[4] as AgentAction.PlanIntent
        assertWindow(intent.window, "11-14 07:15", "11-14 08:45")
        assertEquals("问问他睡得怎么样", intent.window.note)
        assertEquals("轻声，别追问", intent.window.hint)
        val patch = decision.actions[5] as AgentAction.PersonaPatch
        assertEquals("""{"bond":"更懂得先陪着"}""", patch.patchJson)
        assertEquals("更懂得先陪着", patch.summary)
        val quiet = decision.actions[6] as AgentAction.QuietHours
        assertEquals(23 * 60 + 30, quiet.sleepMinutes)
        assertEquals(8 * 60 + 10, quiet.wakeMinutes)
        assertEquals("最近想早点睡", quiet.reason)
    }

    @Test
    fun brokenActionOnlyDropsThatAction() {
        val raw = """
            {
              "thought": "混了坏数据也不该整份失败",
              "actions": [
                {"type": "message", "text": "在吗"},
                {"type": "message"},
                {"type": "remember", "content": "他最近在准备一个重要的项目", "drawer": "EVIL", "importance": 9},
                {"type": "intent", "note": "忘了写时间"},
                {"type": "unknown_thing", "text": "???"},
                "这不是一个对象",
                {"type": "quiet_hours", "sleep_at": "25:99", "wake_at": "08:00"}
              ]
            }
        """.trimIndent()

        val decision = AgentDecisionCodec.parse(raw, now, zone)!!

        assertEquals("坏动作只丢自己，已解析：${decision.actions}", 2, decision.actions.size)
        assertTrue(decision.actions[0] is AgentAction.Message)
        val remember = decision.actions[1] as AgentAction.Remember
        assertEquals("USER", remember.drawer)
        assertEquals(5, remember.importance)
    }

    @Test
    fun emptyActionsBecomesNothing() {
        val empty = AgentDecisionCodec.parse("""{"thought":"今天不想说话","actions":[]}""", now, zone)!!
        assertEquals(listOf(AgentAction.Nothing), empty.actions)

        val missing = AgentDecisionCodec.parse("""{"thought":"没什么想做的"}""", now, zone)!!
        assertEquals(listOf(AgentAction.Nothing), missing.actions)
    }

    @Test
    fun badInputReturnsNullInsteadOfThrowing() {
        assertNull(AgentDecisionCodec.parse("", now, zone))
        assertNull(AgentDecisionCodec.parse("   ", now, zone))
        assertNull(AgentDecisionCodec.parse("今天不想说话，也没有 JSON", now, zone))
        assertNull(AgentDecisionCodec.parse("""{"thought":"被截断的" """, now, zone))
        assertNull("空对象不是决策", AgentDecisionCodec.parse("{}", now, zone))
        assertNull(AgentDecisionCodec.parse("""{"foo":"bar"}""", now, zone))
    }

    @Test
    fun thoughtIsTrimmedToFourHundredChars() {
        val longThought = "想".repeat(600)
        val decision = AgentDecisionCodec.parse(
            """{"thought":"$longThought","actions":[{"type":"nothing"}]}""",
            now,
            zone
        )!!

        assertEquals(400, decision.thought.length)
    }

    @Test
    fun parsesRememberDrawerAndImportanceAliases() {
        val raw = """
            {
              "thought": "记一下",
              "actions": [
                {"type": "remember", "content": "他怕冷", "drawer": "event", "importance": "4"},
                {"type": "remember", "content": "他喜欢猫", "drawer": "自我", "importance": 0}
              ]
            }
        """.trimIndent()

        val decision = AgentDecisionCodec.parse(raw, now, zone)!!

        // 同一个 type 的过滤在 normalize 里做，parse 保留原样
        assertEquals(2, decision.actions.size)
        val first = decision.actions[0] as AgentAction.Remember
        assertEquals("EVENT", first.drawer)
        assertEquals(4, first.importance)
        val second = decision.actions[1] as AgentAction.Remember
        assertEquals("SELF", second.drawer)
        assertEquals(1, second.importance)
    }

    @Test
    fun parsesAbsoluteWindowFieldsFromOwnJson() {
        val json = """{"thought":"存过的","actions":[{"type":"intent","earliest_at":${now + hour},"latest_at":${now + 3 * hour},"note":"问问他","hint":"轻一点"}]}"""

        val decision = AgentDecisionCodec.parse(json, now, zone)!!

        val intent = decision.actions.single() as AgentAction.PlanIntent
        assertEquals(now + hour, intent.window.earliestAt)
        assertEquals(now + 3 * hour, intent.window.latestAt)
        assertEquals("问问他", intent.window.note)
        assertEquals("轻一点", intent.window.hint)
    }

    // -----------------------------------------------------------------------
    // 2. 归一化
    // -----------------------------------------------------------------------

    @Test
    fun normalizeDropsEmptyAndClipsLengths() {
        val decision = AgentDecision(
            thought = "  归一化我  ",
            actions = listOf(
                AgentAction.Message("   "),
                AgentAction.Message("说".repeat(500)),
                AgentAction.Browse("查".repeat(100)),
                AgentAction.Remember("记".repeat(500), drawer = "evil", importance = 9),
                AgentAction.Remember("第二条", drawer = "EVENT", importance = 0)
            )
        )

        val normalized = AgentDecisionCodec.normalize(decision, now, zone)

        assertEquals("归一化我", normalized.thought)
        assertEquals(3, normalized.actions.size)
        val message = normalized.actions[0] as AgentAction.Message
        assertEquals(300, message.text.length)
        val browse = normalized.actions[1] as AgentAction.Browse
        assertEquals(60, browse.query.length)
        val remember = normalized.actions[2] as AgentAction.Remember
        assertEquals(400, remember.content.length)
        assertEquals("USER", remember.drawer)
        assertEquals(5, remember.importance)
    }

    @Test
    fun normalizeKeepsOnePerTypeAndAtMostFour() {
        val decision = AgentDecision(
            thought = "动作很多",
            actions = listOf(
                AgentAction.Message("一"),
                AgentAction.Message("二"),
                AgentAction.Browse("天气"),
                AgentAction.Remember("他怕冷"),
                AgentAction.PlanIntent(AgentWindow(now + hour, now + 2 * hour)),
                AgentAction.PersonaPatch("""{"bond":"更亲近"}""", "更亲近"),
                AgentAction.QuietHours(23 * 60 + 30, 8 * 60)
            )
        )

        val normalized = AgentDecisionCodec.normalize(decision, now, zone)

        assertEquals(4, normalized.actions.size)
        assertEquals("一", (normalized.actions[0] as AgentAction.Message).text)
        assertTrue(normalized.actions[1] is AgentAction.Browse)
        assertTrue(normalized.actions[2] is AgentAction.Remember)
        assertTrue(normalized.actions[3] is AgentAction.PlanIntent)
    }

    @Test
    fun normalizeDedupesNothingAndFallsBackToNothing() {
        val duplicated = AgentDecisionCodec.normalize(
            AgentDecision("发呆", listOf(AgentAction.Nothing, AgentAction.Nothing)),
            now,
            zone
        )
        assertEquals(listOf(AgentAction.Nothing), duplicated.actions)

        val empty = AgentDecisionCodec.normalize(AgentDecision("什么都没想", emptyList()), now, zone)
        assertEquals(listOf(AgentAction.Nothing), empty.actions)

        // 有正经动作时，nothing 不再保留
        val mixed = AgentDecisionCodec.normalize(
            AgentDecision("说一句", listOf(AgentAction.Nothing, AgentAction.Message("在吗"))),
            now,
            zone
        )
        assertEquals(1, mixed.actions.size)
        assertTrue(mixed.actions.single() is AgentAction.Message)
    }

    @Test
    fun normalizePullsExpiredIntentToNow() {
        val expired = AgentDecision(
            thought = "过期的意向",
            actions = listOf(
                AgentAction.PlanIntent(
                    AgentWindow(now - 3 * hour, now - 2 * hour, note = " 问问他 ", hint = " 轻声 ")
                )
            )
        )

        val normalized = AgentDecisionCodec.normalize(expired, now, zone)

        val intent = normalized.actions.single() as AgentAction.PlanIntent
        assertEquals(now, intent.window.earliestAt)
        assertEquals(now + 2 * hour, intent.window.latestAt)
        assertEquals("问问他", intent.window.note)
        assertEquals("轻声", intent.window.hint)
    }

    @Test
    fun normalizeClampsQuietHoursIntoOneDay() {
        val normalized = AgentDecisionCodec.normalize(
            AgentDecision("作息", listOf(AgentAction.QuietHours(24 * 60 + 30, -10, " 早睡 "))),
            now,
            zone
        )

        val quiet = normalized.actions.single() as AgentAction.QuietHours
        assertEquals(30, quiet.sleepMinutes)
        assertEquals(1430, quiet.wakeMinutes)
        assertEquals("早睡", quiet.reason)
    }

    // -----------------------------------------------------------------------
    // 3. 决策摘要
    // -----------------------------------------------------------------------

    @Test
    fun hasEffectIgnoresNothing() {
        val idle = AgentDecision("发呆", listOf(AgentAction.Nothing))
        assertFalse(idle.hasEffect)
        assertEquals("什么都没做", idle.summary())

        val active = AgentDecision(
            "想做点事",
            listOf(AgentAction.Message("在吗"), AgentAction.Browse("天气"))
        )
        assertTrue(active.hasEffect)
        assertEquals("发消息 + 联网浏览", active.summary())

        val combined = AgentDecision(
            "留个念头",
            listOf(
                AgentAction.PlanIntent(AgentWindow(now + hour, now + 2 * hour)),
                AgentAction.Remember("他怕冷"),
                AgentAction.QuietHours(23 * 60, 7 * 60)
            )
        )
        assertEquals("留个意向 + 记一笔 + 调整作息", combined.summary())
    }

    // -----------------------------------------------------------------------
    // 4. 时间窗口：时段词
    // -----------------------------------------------------------------------

    @Test
    fun tomorrowMorningMapsToSevenToNineThirty() {
        assertWindow(window("明早"), "11-14 07:00", "11-14 09:30")
        assertWindow(window("明天早上"), "11-14 07:00", "11-14 09:30")
        // 已经过了明早的时间点，仍然指向下一个 07:00–09:30
        assertWindow(window("明早", from = at(2026, 11, 14, 10, 0)), "11-15 07:00", "11-15 09:30")
    }

    @Test
    fun tonightMapsToNineteenThirtyToTwentyTwoThirty() {
        assertWindow(window("今晚"), "11-13 19:30", "11-13 22:30")
        // 时段已经过去 → 顺延到次日
        val late = at(2026, 11, 13, 23, 0)
        assertWindow(window("今晚", from = late), "11-14 19:30", "11-14 22:30")
    }

    @Test
    fun afternoonShiftsToNextDayWhenPassed() {
        assertWindow(window("下午"), "11-14 13:00", "11-14 17:00")
        assertWindow(window("下午", from = at(2026, 11, 13, 9, 0)), "11-13 13:00", "11-13 17:00")
        assertWindow(window("中午", from = at(2026, 11, 13, 18, 5)), "11-14 11:30", "11-14 13:30")
    }

    @Test
    fun tomorrowMapsToWholeDayButWindowIsCappedAtSixHours() {
        val win = window("明天")
        assertEquals("11-14 09:00", clock(win.earliestAt))
        assertEquals("整天词映射 09:00–20:00，落成窗口受 6 小时上限保护", 6 * hour, win.latestAt - win.earliestAt)
        assertEquals("11-14 15:00", clock(win.latestAt))
    }

    @Test
    fun midnightAndDawnCrossDayBoundary() {
        // 半夜 = 23:00 到次日 01:30
        assertWindow(window("半夜"), "11-13 23:00", "11-14 01:30")
        // 凌晨 00:30 的「半夜」是正在进行的那一段
        val earlyMorning = at(2026, 11, 13, 0, 30)
        val ongoing = window("半夜", from = earlyMorning)
        assertEquals(earlyMorning, ongoing.earliestAt)
        assertEquals("11-13 01:30", clock(ongoing.latestAt))
        assertWindow(window("凌晨"), "11-14 01:00", "11-14 04:00")
    }

    // -----------------------------------------------------------------------
    // 5. 时间窗口：显式时刻与相对量
    // -----------------------------------------------------------------------

    @Test
    fun explicitClockRangeIsUsedDirectly() {
        assertWindow(window("20:00-21:30"), "11-13 20:00", "11-13 21:30")
        assertWindow(window("23:30 到 01:00"), "11-13 23:30", "11-14 01:00")
        assertWindow(window("明天 20:00-21:30"), "11-14 20:00", "11-14 21:30")
    }

    @Test
    fun explicitClockIsClampedOrShiftedToKeepInvariants() {
        // 已经开始了的窗口，起点收到 now
        assertWindow(window("20:00-21:30", from = at(2026, 11, 13, 21, 0)), "11-13 21:00", "11-13 21:30")
        // 整个窗口都过去了 → 顺延到次日
        assertWindow(window("20:00-21:30", from = at(2026, 11, 13, 22, 0)), "11-14 20:00", "11-14 21:30")
        // 只有 5 分钟的区间补到最小 15 分钟
        val tooShort = window("20:00-20:05")
        assertEquals("11-13 20:00", clock(tooShort.earliestAt))
        assertEquals(15 * minute, tooShort.latestAt - tooShort.earliestAt)
    }

    @Test
    fun singleClockAndFuzzyClock() {
        // 18:05 说「8:30」→ 次日 08:30 起两小时
        assertWindow(window("8:30"), "11-14 08:30", "11-14 10:30")
        assertWindow(window("08:30"), "11-14 08:30", "11-14 10:30")
        assertWindow(window("八点一刻"), "11-14 08:15", "11-14 10:15")
        // 「前后」= 以钟点为中心的 90 分钟窗口
        assertWindow(window("明早 8 点前后"), "11-14 07:15", "11-14 08:45")
    }

    @Test
    fun relativeDurationsAreSupported() {
        assertWindow(window("两小时后"), "11-13 20:05", "11-13 22:05")
        assertWindow(window("2h"), "11-13 20:05", "11-13 22:05")
        assertWindow(window("+90m"), "11-13 19:35", "11-13 21:35")
        assertWindow(window("半小时后"), "11-13 18:35", "11-13 20:35")
        assertWindow(window("一个半小时后"), "11-13 19:35", "11-13 21:35")
        assertWindow(window("十分钟后"), "11-13 18:15", "11-13 20:15")
    }

    @Test
    fun unknownOrBlankTextFallsBackSafely() {
        assertNull("空白文本没有窗口", AgentDecisionCodec.windowFor("   ", now, zone))
        assertWindow(window("等我有空的时候"), "11-13 20:05", "11-13 22:05")
        // defaultHours 参与兜底窗口
        assertWindow(window("说不清楚", hours = 3), "11-13 21:05", "11-14 00:05")
        assertWindow(window("马上"), "11-13 18:05", "11-13 18:35")
    }

    @Test
    fun everyWindowRespectsInvariants() {
        val samples = listOf(
            "明早", "明天早上", "今晚", "明天", "下午", "中午", "晚上", "半夜", "凌晨",
            "2h", "+90m", "两小时后", "半小时后", "20:00-21:30", "23:30 到 01:00",
            "明早 8 点前后", "随便说点什么", "明天 09:00-20:00"
        )
        samples.forEach { text ->
            val win = window(text)
            assertTrue("$text 的起点必须不早于 now：${clock(win.earliestAt)}", win.earliestAt >= now)
            assertTrue("$text 的窗口必须非空", win.earliestAt < win.latestAt)
            val span = win.latestAt - win.earliestAt
            assertTrue("$text 的窗口不能小于 15 分钟", span >= 15 * minute)
            assertTrue("$text 的窗口不能超过 6 小时", span <= 6 * hour)
        }
        // 时区参与换算：同一个 now，在 UTC 下「明早」应落在 UTC 的 07:00
        val utcZone = ZoneId.of("UTC")
        val utcMorning = AgentDecisionCodec.windowFor("明早", now, utcZone)!!
        val utcLocal = Instant.ofEpochMilli(utcMorning.earliestAt).atZone(utcZone)
        assertEquals(7, utcLocal.hour)
        assertEquals(0, utcLocal.minute)
    }

    // -----------------------------------------------------------------------
    // 6. 序列化
    // -----------------------------------------------------------------------

    @Test
    fun toJsonRoundTripsThroughParse() {
        val decision = AgentDecision(
            thought = "想了很久，决定说一句",
            actions = listOf(
                AgentAction.Message("""他说："下雨了"，带伞"""),
                AgentAction.Browse("今晚的月亮"),
                AgentAction.Remember("他最近在准备一个重要的项目", "EVENT", 4),
                AgentAction.PlanIntent(
                    AgentWindow(now + hour, now + 2 * hour, note = "问问他", hint = "轻声")
                ),
                AgentAction.PersonaPatch("""{"bond":"更懂得先陪着"}""", "更懂得先陪着"),
                AgentAction.QuietHours(23 * 60 + 30, 8 * 60 + 10, "最近想早点睡")
            )
        )

        val encoded = AgentDecisionCodec.toJson(decision)
        assertTrue("应带 thought：$encoded", encoded.contains("\"thought\""))
        assertTrue("应带 intent 绝对时间戳：$encoded", encoded.contains("\"earliest_at\""))
        assertTrue(encoded.contains("\"quiet_hours\""))

        val decoded = AgentDecisionCodec.parse(encoded, now, zone)
        assertEquals("toJson → parse 应完全还原", decision, decoded)
    }

    @Test
    fun toJsonNeverThrowsAndDefaultsToNothing() {
        val empty = AgentDecision("", emptyList())
        val encoded = AgentDecisionCodec.toJson(empty)
        assertTrue(encoded.startsWith("{"))

        val decoded = AgentDecisionCodec.parse(encoded, now, zone)!!
        assertEquals(listOf(AgentAction.Nothing), decoded.actions)

        // 坏 patchJson 也不该抛，回退成字符串字段
        val brokenPatch = AgentDecision("x", listOf(AgentAction.PersonaPatch("不是 JSON", "改一下")))
        assertNotNull(AgentDecisionCodec.toJson(brokenPatch))
    }

    // -----------------------------------------------------------------------
    // 7. 提示词
    // -----------------------------------------------------------------------

    @Test
    fun systemPromptExplainsIdentityActionsAndRestraint() {
        val system = AgentPromptBuilder.system(
            personaText = "名字：小满。说话慢，习惯先听。",
            context = "现在是深夜，他应该在睡；你们上次说话是三个小时前。"
        )

        assertTrue("要说明他是谁：$system", system.contains("你是谁"))
        assertTrue("要说明他的处境", system.contains("你现在的处境"))
        assertTrue("人设要进提示词", system.contains("小满"))
        assertTrue("要说清动作清单", system.contains("nothing") && system.contains("quiet_hours"))
        assertTrue(
            "必须点明发消息只是动作之一",
            system.contains("回复消息只是你能做的事之一")
        )
        assertTrue(
            "必须点明大多数时候什么都不做是对的",
            system.contains("大多数时候什么都不做是对的")
        )
        assertTrue("必须劝阻刷存在感", system.contains("不要为了刷存在感而开口"))
        assertTrue("必须给出 JSON 格式约定", system.contains("动作每行一个，用上面的 JSON 格式"))
        assertTrue("必须只在真想做时才输出 actions", system.contains("只在真的想做点什么时才输出 actions"))
        assertTrue("长度不超过 1400 字，实际 ${system.length}", system.length <= 1400)
    }

    @Test
    fun systemPromptStaysShortForHugeInputs() {
        val system = AgentPromptBuilder.system(
            personaText = "人设".repeat(800),
            context = "处境".repeat(800)
        )

        assertTrue("超长输入下也要 ≤ 1400 字，实际 ${system.length}", system.length <= 1400)
        assertTrue("裁剪后仍要保留克制规则", system.contains("大多数时候什么都不做是对的"))
        assertTrue("裁剪后仍要保留动作格式", system.contains("动作每行一个，用上面的 JSON 格式"))
    }

    @Test
    fun userMessageCarriesLocalTimeAndPrompt() {
        val user = AgentPromptBuilder.user(now, zone)

        assertTrue("要带本地时间字符串：$user", user.contains("2026-11-13 18:05"))
        assertTrue("要提醒先想一句再决定动作", user.contains("先在心里想一句，再决定动作"))
        assertTrue("要说明什么都不做也正常", user.contains("什么都不做也是正常选择"))
    }
}
