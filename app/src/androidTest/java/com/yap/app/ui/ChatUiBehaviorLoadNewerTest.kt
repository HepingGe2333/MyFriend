package com.yap.app.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.yap.app.data.local.YapDatabase
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.data.repo.ApiConfigRepository
import com.yap.app.data.repo.ChatRepository
import com.yap.app.data.repo.MediaJanitor
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
 * 路径 3：**定位到旧块之后，滚到底向下加载一块**（规范 §3 Newer）。
 *
 * 450 条 / 3 块，用 `messageId` 跳进 0 号块：窗口 = `m000..m199`，`hasMoreNewer=true`
 * ——这就是底部那一行「下面还有更新的消息」出现的条件。滚到底时
 * `LaunchedEffect(hasMoreNewer, loadingNewer, isAtBottom)` 会自动 `loadNewer()`，
 * 1 号块整体拼上来（消息数 > 200）。
 *
 * 已知不稳定点（见报告）：底部那一行一露出来就会自动触发它自己代表的加载，
 * 所以「文案可见」没法稳定断言（露出的同一帧就被加载掉了）。这里断言驱动它的状态位
 * `hasMoreNewer`，以及加载结果（1 号块的 `m200` / `m399` 进了窗口）。
 */
@HiltAndroidTest
class ChatUiBehaviorLoadNewerTest {

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
            mediaJanitor = mediaJanitor
        )
    }

    @org.junit.After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun 从旧块滚到底会加载下方一块_下方块的消息出现() {
        val seeded = runBlocking { harness.seedConversation(messageCount = 450) }
        val vm: ChatViewModel = harness.viewModel(store, seeded.session.id, focusMessageId = "m000")
        compose.setContent { ChatUiHost(vm) }
        compose.awaitChatState(vm) { it.loaded }
        compose.awaitChatState(vm, timeoutMillis = 30_000L) { it.messages.any { message -> message.id == "m000" } }
        compose.waitForIdle()

        val jumped = vm.uiState.value
        assertTrue("定位窗口至少包含 0 号块（实际 ${jumped.messages.size}）", jumped.messages.size >= 200)
        assertTrue("定位窗口不应超过「目标块 + 一块上下文」", jumped.messages.size <= 400)
        assertTrue("0 号块的尾条 m199 在窗口里", jumped.messages.any { it.id == "m199" })
        assertTrue(
            "下面还有没加载的块 → 底部「下面还有更新的消息」入口就靠它出现",
            jumped.hasMoreNewer
        )
        assertFalse("0 号块上面已经没有了", jumped.hasMoreOlder)

        // 列表项：1 条日期分隔 + 200 条消息 + 「下面还有更新的消息」+ 末尾占位。
        val lastItemIndex = 1 + jumped.messages.size + 1
        compose.chatList().performScrollToIndex(lastItemIndex)
        compose.awaitChatState(viewModel = vm, timeoutMillis = 20_000L) { it.messages.size > 200 }

        val loaded = vm.uiState.value
        assertTrue(
            "下方块已经拼进来：消息总数必须超过 200（实际 ${loaded.messages.size}）",
            loaded.messages.size > 200
        )
        assertTrue("下方块的头条 m200 应该出现", loaded.messages.any { it.id == "m200" })
        assertTrue("下方块的尾条 m399 也应该出现（整块都拼上了）", loaded.messages.any { it.id == "m399" })
    }
}
