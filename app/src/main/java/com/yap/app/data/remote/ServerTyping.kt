package com.yap.app.data.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「他正在输入」的服务端信号口（`spec/experience/README.md` §8）。
 *
 * 服务端在**一条消息入队时**发 `typing.started`，最后一条发完再发 `typing.stopped`；
 * 客户端只在收到事件时显示指示器，**不自己算延迟、不自己播动画**。
 *
 * 抽成接口是为了纯 JVM 单测：事件轮询器只需要「把开始/结束告诉谁」，不必构造 Hilt 单例。
 * [None] 是默认空实现（不关心输入指示的调用方/测试用）。
 */
interface ServerTypingSink {
    fun typingStarted(sessionId: String)
    fun typingStopped(sessionId: String)

    companion object {
        /** 什么都不做：默认值，让既有调用点与只测编排的假实现不必关心输入指示。 */
        val None: ServerTypingSink = object : ServerTypingSink {
            override fun typingStarted(sessionId: String) = Unit
            override fun typingStopped(sessionId: String) = Unit
        }
    }
}

/**
 * 进程内「哪些会话正在输入」。
 *
 * 为什么放进程级单例而不是塞进 Room：输入指示是**瞬时**状态（几秒），不值得落库，
 * 也不能在离线同步时被「补放」出来——离线回来的消息直接上屏，不补打字动画。
 *
 * 键是**本地会话 id**（轮询器/发送流都会先做 server→local 映射），这样聊天页不必再关心
 * 两套 id；事件里 sessionId 为空时由调用方忽略，不会把指示器挂到一个空会话上。
 *
 * `typingStopped` 是幂等的：重复收到、或没有收到过 started 时都只是把该会话移出集合。
 */
@Singleton
class ServerTypingState @Inject constructor() : ServerTypingSink {

    private val sessions = MutableStateFlow<Set<String>>(emptySet())

    /** 当前正在输入的本地会话 id 集合（界面/测试都可直接读）。 */
    val typingSessions: StateFlow<Set<String>> = sessions.asStateFlow()

    /** 某个会话是否正在输入；同一值不会重复发射（避免聊天页无意义重组）。 */
    fun observe(sessionId: String): Flow<Boolean> =
        sessions.map { sessionId in it }.distinctUntilChanged()

    override fun typingStarted(sessionId: String) {
        val id = sessionId.trim()
        if (id.isEmpty()) return
        sessions.update { it + id }
    }

    override fun typingStopped(sessionId: String) {
        val id = sessionId.trim()
        if (id.isEmpty()) return
        sessions.update { it - id }
    }

    /** 换服务端/退出服务端模式时清空：旧连接上的指示器不该留在新环境里。 */
    fun clearAll() {
        sessions.value = emptySet()
    }
}
