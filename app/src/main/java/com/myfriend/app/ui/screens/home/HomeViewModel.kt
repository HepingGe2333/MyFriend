package com.myfriend.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myfriend.app.data.local.ChatSessionEntity
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.data.prefs.SettingsRepository
import com.myfriend.app.data.repo.ChatRepository
import com.myfriend.app.data.repo.PersonaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 首页上的一个联系人。
 *
 * 1.9 把「人格」和「最近对话」两块合成了一个列表：一个人只有一段会话，
 * 所以这里一行就是一个人，预览、时间、未读都来自他那一段。
 */
data class ContactItem(
    val persona: PersonaEntity,
    val session: ChatSessionEntity?
) {
    val preview: String get() = session?.lastMessagePreview?.takeIf { it.isNotBlank() } ?: "还没有说过话"
    val time: Long get() = session?.lastMessageAt ?: session?.createdAt ?: persona.updatedAt
    val unread: Int get() = session?.unreadCount ?: 0
}

data class HomeUiState(
    val contacts: List<ContactItem> = emptyList(),
    val switchingPersonaId: String? = null,
    val message: String? = null
)

sealed interface HomeEvent {
    data class OpenChat(val sessionId: String) : HomeEvent
    data class Message(val text: String) : HomeEvent
}

/**
 * 联系人列表。
 *
 * 没有「新建对话」：一个人只有一段会话，点进去就是继续。`+` 的语义因此变成「新建人格」。
 * 删除也按人走：删掉联系人会连同他的消息、记忆、念头、规则一起清掉（需要二次确认，在界面上做）。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val personaRepository: PersonaRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    private val localMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val switchingId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val eventsChannel = Channel<HomeEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()

    val uiState: StateFlow<HomeUiState> = combine(
        personaRepository.observeAll(),
        chatRepository.observeSessions(),
        switchingId,
        localMessage
    ) { personas, sessions, switching, message ->
        val byPersona = sessions.associateBy { it.personaId }
        val items = personas.map { ContactItem(it, byPersona[it.id]) }
            .sortedByDescending { it.time }
        HomeUiState(
            contacts = items,
            switchingPersonaId = switching,
            message = message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** 点一个联系人：取他那一段会话（没有就建），然后进聊天。 */
    fun openContact(personaId: String) {
        if (switchingId.value != null) return
        switchingId.value = personaId
        viewModelScope.launch {
            try {
                val session = chatRepository.getOrCreateForPersona(personaId)
                settingsRepository.setActivePersonaId(personaId)
                chatRepository.markRead(session.id)
                eventsChannel.send(HomeEvent.OpenChat(session.id))
            } catch (error: Exception) {
                eventsChannel.send(HomeEvent.Message(error.message ?: "打不开这个联系人，请重试"))
            } finally {
                switchingId.value = null
            }
        }
    }

    /** 删除联系人：消息、记忆、念头、规则一起清掉。 */
    fun deleteContact(personaId: String) {
        viewModelScope.launch {
            runCatching { personaRepository.delete(personaId) }
                .onSuccess { localMessage.value = "联系人已删除" }
                .onFailure { localMessage.value = it.message ?: "删除失败" }
        }
    }

    fun dismissMessage() {
        localMessage.value = null
    }
}
