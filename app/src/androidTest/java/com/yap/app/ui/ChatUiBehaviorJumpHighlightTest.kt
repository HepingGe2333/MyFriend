package com.yap.app.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.yap.app.data.local.YapDatabase
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.data.repo.ApiConfigRepository
import com.yap.app.data.repo.ChatRepository
import com.yap.app.data.repo.MediaJanitor
import com.yap.app.data.repo.StickerRepository
import com.yap.app.data.repo.MemoryRepository
import com.yap.app.data.repo.MessageBlockWriter
import com.yap.app.data.repo.MoodRepository
import com.yap.app.data.repo.PersonaCardRepository
import com.yap.app.data.repo.PersonaRepository
import com.yap.app.data.repo.PresenceRepository
import com.yap.app.data.repo.ProactiveRepository
import com.yap.app.ui.screens.chat.ChatViewModel
import com.yap.app.util.ChatNotifier
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * 路径 4：**从检索结果跳进聊天并定位到那条旧消息**（`ChatEvent.FocusMessage`）。
 *
 * 做法：给 ViewModel 的 `SavedStateHandle` 塞 `messageId="m050"`（导航层就是这么传的），
 * `startJumpToTarget()` 会把分页窗口换成目标所在的 0 号块，再发 `FocusMessage` 事件；
 * `ChatScreen` 收到后滚到它、并给约 2 秒高亮。
 *
 * 断言：
 * - 窗口真的换到了目标所在的块（`m000..m199`），而不是停在最新块；
 * - 目标消息在语义树里可见，且被滚到视口上半部分（定位留了 2 条上下文，不是碰巧可见）；
 * - 最新一条不在窗口里 —— 反证「不是只把最新一屏画出来」。
 *
 * 已知限制（见报告）：高亮是 `MessageBubble` 里的一层 `background(accent 16%)` 动画，
 * 语义树上没有任何可读属性，`testTag` 也只在生产代码里加；不改生产代码的前提下
 * 只能断言「滚到了 + 可见」这一半，高亮本身靠代码路径（`highlighted = id == focusMessageId`）
 * 保证，没做像素采样（主题色随强调色/深色模式变，抓像素只会变成脆弱断言）。
 */
@HiltAndroidTest
class ChatUiBehaviorJumpHighlightTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Inject lateinit var database: YapDatabase
    @Inject lateinit var blockWriter: MessageBlockWriter
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var personaRepository: PersonaRepository
    @Inject lateinit var apiConfigRepository: ApiConfigRepository
    @Inject lateinit var memoryRepository: MemoryRepository
    @Inject lateinit var personaCardRepository: PersonaCardRepository
    @Inject lateinit var moodRepository: MoodRepository
    @Inject lateinit var presenceRepository: PresenceRepository
    @Inject lateinit var proactiveRepository: ProactiveRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var chatNotifier: ChatNotifier
    @Inject lateinit var mediaJanitor: MediaJanitor
    @Inject lateinit var stickerRepository: StickerRepository
    /** 0.13：语音/视频（只看附件解析成可播放文件这一条路径，UI 测试里不会真的播）。 */
    @Inject lateinit var audioVideoRepository: com.yap.app.data.repo.AudioVideoRepository

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store = ViewModelStore()
    private lateinit var harness: ChatUiHarness

    @Before
    fun setUp() {
        hilt.inject()
        harness = ChatUiHarness(
            context = context,
            db = database,
            blockWriter = blockWriter,
            chatRepository = chatRepository,
            personaRepository = personaRepository,
            apiConfigRepository = apiConfigRepository,
            memoryRepository = memoryRepository,
            personaCardRepository = personaCardRepository,
            moodRepository = moodRepository,
            presenceRepository = presenceRepository,
            proactiveRepository = proactiveRepository,
            settingsRepository = settingsRepository,
            chatNotifier = chatNotifier,
            mediaJanitor = mediaJanitor,
            stickerRepository = stickerRepository,
            audioVideoRepository = audioVideoRepository
        )
    }

    @org.junit.After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun 从检索跳转会定位并显示旧消息_窗口换到它所在的块() {
        val seeded = runBlocking { harness.seedConversation(messageCount = 450) }
        val target = "m050"

        val vm: ChatViewModel = harness.viewModel(store, seeded.session.id, focusMessageId = target)
        compose.setContent { ChatUiHost(vm) }
        compose.awaitChatState(vm) { state -> state.loaded }
        compose.awaitChatState(vm, timeoutMillis = 30_000L) { state -> state.messages.any { it.id == target } }
        compose.waitForIdle()

        val state = vm.uiState.value
        assertTrue("定位窗口至少包含 0 号块（实际 ${state.messages.size}）", state.messages.size >= 200)
        assertTrue("定位窗口不应超过「目标块 + 一块上下文」", state.messages.size <= 400)
        assertEquals("0 号块的头一条", "m000", state.messages.first().id)
        assertFalse(
            "最新一条不在这个窗口里：说明确实跳到了旧块，而不是停在最新一屏",
            state.messages.any { it.id == seeded.newestId }
        )

        val targetNode = compose.onNodeWithText(target)
        targetNode.assertIsDisplayed()

        // 定位会滚到目标上方留 2 条上下文：目标应当落在视口上半部分。
        val targetBounds = targetNode.fetchSemanticsNode().boundsInRoot
        val rootHeight = compose.onRoot().fetchSemanticsNode().size.height
        assertTrue(
            "目标消息应被滚到视口上半部分（top=${targetBounds.top}, 视口高=$rootHeight）",
            targetBounds.top < rootHeight / 2f
        )
    }
}
