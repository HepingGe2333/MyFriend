package com.yap.app.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
import com.yap.app.domain.MessageBlocks
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
 * 路径 1：**首屏只读最新一块**（`spec/messages-blocks/README.md` §3）。
 *
 * 预置 450 条消息 → 块划分是 200 / 200 / 50（3 块）。打开聊天页时窗口应当只有
 * 最新那一块（50 条），既不会把 450 条全读进来，也不会停在空列表等着「加载更早」。
 *
 * 断言分两层：
 * - 状态层：`ChatUiState.messages` 就是列表渲染的那份数据，条数 = 最新块条数，且 ≤ BLOCK_SIZE；
 * - 语义树层：最新一条真的画出来了（首屏自动贴底），最早那条一次都没被读进窗口。
 *
 * 为什么「≤ 200」不用数屏幕上的气泡：LazyColumn 只组合视口内的几条，
 * 数气泡得到的是屏幕高度，不是窗口大小；窗口大小得看状态，那是真实数据来源。
 */
@HiltAndroidTest
class ChatUiBehaviorFirstBlockTest {

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
    private val store = androidx.lifecycle.ViewModelStore()
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
    fun 首屏打开450条会话时只渲染最新一块_含最新一条不含最早一条() {
        val seeded = runBlocking { harness.seedConversation(messageCount = 450) }
        assertEquals("预置数据应当是 200/200/50 三块", listOf(200, 200, 50), blockSizes(seeded.session.id))

        val vm: ChatViewModel = harness.viewModel(store, seeded.session.id)
        compose.setContent { ChatUiHost(vm) }
        compose.awaitChatState(vm) { it.loaded && it.messages.isNotEmpty() }
        compose.waitForIdle()

        val state = vm.uiState.value
        assertEquals("首屏只读最新一块：450 条的会话里只应有 50 条", 50, state.messages.size)
        assertTrue(
            "首屏窗口绝不能超过一块（BLOCK_SIZE=${MessageBlocks.BLOCK_SIZE}，实际 ${state.messages.size}）",
            state.messages.size <= MessageBlocks.BLOCK_SIZE
        )
        assertEquals("窗口第一条是 2 号块的头一条", "m400", state.messages.first().id)
        assertEquals("窗口最后一条是会话的最新一条", seeded.newestId, state.messages.last().id)
        assertTrue("更早的块还没读，这是「上滑加载更早」的依据", state.hasMoreOlder)
        assertFalse("已经停在最新块，不该有「下方还有更新的消息」", state.hasMoreNewer)

        // 语义树：最新一条被画出来（首屏自动贴底），最早一条完全没进窗口。
        compose.onNodeWithText(seeded.newestId).assertIsDisplayed()
        assertEquals(
            "最早一条不该出现在首屏语义树里",
            0,
            compose.onAllNodesWithText(seeded.oldestId).fetchSemanticsNodes().size
        )
        assertEquals(
            "1 号块的头一条同样不该出现",
            0,
            compose.onAllNodesWithText("m200").fetchSemanticsNodes().size
        )
    }

    private fun blockSizes(sessionId: String): List<Int> = runBlocking {
        database.query(
            "SELECT messageCount FROM message_blocks WHERE sessionId = ? ORDER BY blockIndex",
            arrayOf(sessionId)
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) }
        }
    }
}
