package com.myfriend.app.data.repo

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfriend.app.R
import com.myfriend.app.domain.MoodState
import com.myfriend.app.domain.RecallDepth
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 工具痕迹显示文案的**真机**验证：同一组工具调用在中文 / 英文下产出的标题与副标题。
 *
 * 为什么要一条 instrumented 测试：`ToolTraceTextTest`（JVM）只能读 XML 里的文案，
 * 证明不了 `R.string` / `R.plurals` 真的按语言解析、`%1$d` 真的填进去了。
 * 这里用 `createConfigurationContext` 分别造出 zh / en 的 Context，跑真实的
 * `Resources.getString` / `getQuantityString`——也就是 `ToolExecutor` 写入痕迹时走的那条路。
 *
 * 运行：`:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...ToolTraceTextLocaleTest`
 */
@RunWith(AndroidJUnit4::class)
class ToolTraceTextLocaleTest {

    private fun context(tag: String): Context {
        val configuration = Configuration(
            ApplicationProvider.getApplicationContext<Context>().resources.configuration
        )
        configuration.setLocale(Locale.forLanguageTag(tag))
        return ApplicationProvider.getApplicationContext<Context>()
            .createConfigurationContext(configuration)
    }

    private val zh = context("zh")
    private val en = context("en")

    @Test
    fun traceTitlesFollowTheInterfaceLanguage() {
        // 同一张表的中英两列：与 iOS 的 ToolTraceTitleLocalizationTests 逐字一致。
        val expected = mapOf(
            ToolTraceTitle.THINK to ("想了想" to "Thought it over"),
            ToolTraceTitle.WEB_SEARCH to ("联网搜索" to "Web search"),
            ToolTraceTitle.WEB_FETCH to ("读了网页" to "Read a page"),
            ToolTraceTitle.REMEMBER to ("记住了一件事" to "Remembered something"),
            ToolTraceTitle.REMEMBER_PENDING to ("记下了（还在确认）" to "Noted (still confirming)"),
            ToolTraceTitle.REMEMBER_CONFIRMED to ("记住了" to "Remembered"),
            ToolTraceTitle.REMEMBER_REINFORCED to ("又确认了一次" to "Confirmed again"),
            ToolTraceTitle.REMEMBER_SKIPPED to ("这次先不记" to "Not saving this one"),
            ToolTraceTitle.REMEMBER_CONSIDERING to ("想了想要不要记" to "Thought about whether to remember it"),
            ToolTraceTitle.UPDATE_PERSONA to ("调整了自己的人设" to "Adjusted his profile"),
            ToolTraceTitle.PERSONA_UNCHANGED to ("想了想，没有改" to "Thought it over, left it unchanged"),
            ToolTraceTitle.SET_QUIET_HOURS to ("安排了自己的作息" to "Set his sleep hours"),
            ToolTraceTitle.SET_MOOD to ("心情变了" to "Mood changed"),
            ToolTraceTitle.RECALL to ("回想了一下" to "Recalled"),
            ToolTraceTitle.DEFER_REPLY to ("决定先不回" to "Decided not to reply yet"),
            ToolTraceTitle.UNKNOWN to ("动作" to "Action")
        )
        expected.forEach { (title, texts) ->
            assertEquals("中文标题 ${title.name}", texts.first, ToolTraceText.title(zh, title))
            assertEquals("英文标题 ${title.name}", texts.second, ToolTraceText.title(en, title))
        }
        // 按工具名取默认标题（ToolExecutor 里走的就是这一条）。
        assertEquals("联网搜索", ToolTraceText.title(zh, "web_search"))
        assertEquals("Web search", ToolTraceText.title(en, "web_search"))
        assertEquals("动作", ToolTraceText.title(zh, "no_such_tool"))
    }

    @Test
    fun generatedSubtitlesAndDetailsFollowTheInterfaceLanguage() {
        // 回忆：没翻到 / 1 条 / 2 条（中文没有单数变形，英文要 one/other）。
        assertEquals("翻了翻，没想起什么", ToolTraceText.recallSubtitle(zh, null))
        assertEquals("Looked back — nothing came to mind", ToolTraceText.recallSubtitle(en, null))
        assertEquals("翻了翻记忆 · 1 条", ToolTraceText.recallSubtitle(zh, 1))
        assertEquals("Looked back · 1 memory", ToolTraceText.recallSubtitle(en, 1))
        assertEquals("翻了翻记忆 · 2 条", ToolTraceText.recallSubtitle(zh, 2))
        assertEquals("Looked back · 2 memories", ToolTraceText.recallSubtitle(en, 2))
        assertEquals("（随口想想）翻了一遍，没有找到", ToolTraceText.recallEmptyDetail(zh, RecallDepth.QUICK))
        assertEquals(
            "(a quick recall) — looked back, found nothing",
            ToolTraceText.recallEmptyDetail(en, RecallDepth.QUICK)
        )

        // 作息。
        assertEquals("08:00 睡 · 07:00 起", ToolTraceText.quietHoursSubtitle(zh, 8 * 60, 7 * 60))
        assertEquals("Sleep 08:00 · wake 07:00", ToolTraceText.quietHoursSubtitle(en, 8 * 60, 7 * 60))
        assertEquals(
            "这段时间他不会主动来打扰。\n睡前会开免打扰：睡着时不会被消息叫醒。",
            ToolTraceText.quietHoursDetail(zh, "", true)
        )

        // 先不回：分钟数进模板，英文按 one/other 选。
        assertEquals("过 1 分钟再想这件事", ToolTraceText.deferDetail(zh, 1, ""))
        assertEquals("过 2 分钟再想这件事", ToolTraceText.deferDetail(zh, 2, ""))
        assertEquals("Will think about it again in 1 minute", ToolTraceText.deferDetail(en, 1, ""))
        assertEquals("Will think about it again in 2 minutes", ToolTraceText.deferDetail(en, 2, ""))
        assertEquals(
            "Will think about it again in 45 minutes (busy)",
            ToolTraceText.deferDetail(en, 45, "busy")
        )

        // 心情：词表里的词按语言翻，强度与原因照填。
        val mood = MoodState(label = "雀跃", intensity = 4, reason = "刚聊到想去的地方")
        assertEquals("雀跃（4/5）· 刚聊到想去的地方", ToolTraceText.mood(zh, mood))
        assertEquals("Elated (4/5) · 刚聊到想去的地方", ToolTraceText.mood(en, mood))
        // 模型自造的怪词原样显示，不硬翻。
        val invented = MoodState(label = "半梦半醒", intensity = 3, reason = "")
        assertEquals("半梦半醒（3/5）", ToolTraceText.mood(zh, invented))
        assertEquals("半梦半醒 (3/5)", ToolTraceText.mood(en, invented))

        // 人设：没改 / 改了（原因 + 已生效）。
        assertEquals(
            "这处设定本来就是你说的样子，不用改。",
            ToolTraceText.personaUnchangedDetail(zh)
        )
        assertEquals("原因：想更安静一点", ToolTraceText.personaReason(zh, "想更安静一点"))
        assertEquals("Reason: want it quieter", ToolTraceText.personaReason(en, "want it quieter"))
        assertEquals("Applied — the change is in his version history.", ToolTraceText.personaApplied(en))
    }

    @Test
    fun storedSessionTitlePreviewAndCountsFollowTheInterfaceLanguage() {
        assertEquals("与 林深 的对话", zh.getString(R.string.chat_session_default_title, "林深"))
        assertEquals("Chat with Rowan", en.getString(R.string.chat_session_default_title, "Rowan"))
        assertEquals("图片", zh.getString(R.string.chat_preview_image))
        assertEquals("Photo", en.getString(R.string.chat_preview_image))
        assertEquals("主动提议", zh.getString(R.string.chat_preview_proactive))
        assertEquals("Reached out", en.getString(R.string.chat_preview_proactive))
        assertEquals("回复失败", zh.getString(R.string.chat_preview_failed))
        assertEquals("Reply failed", en.getString(R.string.chat_preview_failed))

        // 气泡下面那行「N 次」：英文 1 次说 once，2 次说 2 times。
        assertEquals("1 次", zh.resources.getQuantityString(R.plurals.chat_trace_count, 1, 1))
        assertEquals("2 次", zh.resources.getQuantityString(R.plurals.chat_trace_count, 2, 2))
        assertEquals("once", en.resources.getQuantityString(R.plurals.chat_trace_count, 1, 1))
        assertEquals("2 times", en.resources.getQuantityString(R.plurals.chat_trace_count, 2, 2))

        // 主动节奏的默认说明：小时数进模板，英文 1 小时不带 s。
        assertEquals("每 2 小时主动关心一次", zh.resources.getQuantityString(R.plurals.proactive_default_note_interval, 2, 2))
        assertEquals("Checks in every hour", en.resources.getQuantityString(R.plurals.proactive_default_note_interval, 1, 1))
        assertEquals("Checks in every 2 hours", en.resources.getQuantityString(R.plurals.proactive_default_note_interval, 2, 2))
    }
}
