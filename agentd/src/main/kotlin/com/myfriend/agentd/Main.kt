package com.myfriend.agentd

/**
 * `agentd` 的入口。
 *
 * 用法：
 * ```
 * ./gradlew :agentd:run --args="--port 8788"
 * # 或（distribution 装好之后）
 * agentd/build/install/agentd/bin/agentd --port 8788
 * ```
 *
 * 默认只监听 `127.0.0.1`：它是「嵌入式服务」，不是公网 API。
 * 真要部署成独立服务器时显式传 `--host 0.0.0.0`，并由部署方自己加 TLS/反向代理；
 * 0.11.1 起可以再叠一层可选的共享 token（`--token` 或环境变量 `MYFRIEND_AGENTD_TOKEN`），
 * 但仍然**没有 TLS**——见 README 的「Listen address and auth」。
 *
 * 客户端（Android 远程模式）目前不发 `Authorization`，所以默认不设 token；
 * 设了 token 之后所有请求（含 `/health`）都必须带 `Authorization: Bearer <token>`。
 */
fun main(args: Array<String>) {
    val options = parseArgs(args)
    if (options.help) {
        printUsage()
        return
    }
    val server: AgentdServer.StartedServer = try {
        AgentdServer.start(options.host, options.port, options.token)
    } catch (failure: Exception) {
        System.err.println("启动失败：${failure.message}")
        System.err.println("（端口被占用？换一个 --port 试试）")
        kotlin.system.exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop(0)
            println("myfriend-agentd 已停止")
        }
    )
    println("myfriend-agentd $VERSION 正在监听 http://${options.host}:${options.port}")
    printSecurityNotice(options)
    println("  GET  /health")
    println("  POST /v1/core/sleep-decision")
    println("  POST /v1/core/defer-plan")
    println("  POST /v1/core/proactive-cap")
    println("  POST /v1/core/tool-args")
}

private const val VERSION = AgentdApi.VERSION

/** 共享 token 的最短长度：只做提醒，不拒绝启动（部署方可能有自己的考虑）。 */
private const val MIN_TOKEN_LENGTH = 16

private data class Options(
    val host: String = "127.0.0.1",
    val port: Int = 8788,
    val token: String? = null,
    val help: Boolean = false
)

private fun parseArgs(args: Array<String>): Options {
    var host = "127.0.0.1"
    var port = 8788
    var help = false
    var token: String? = null
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        when {
            arg == "--help" || arg == "-h" -> {
                help = true
                index += 1
            }

            arg == "--host" -> {
                host = args.getOrNull(index + 1)?.takeIf { it.isNotBlank() }
                    ?: exitWith("--host 后面要跟一个地址，例如 127.0.0.1")
                index += 2
            }

            arg.startsWith("--host=") -> {
                host = arg.substringAfter("--host=").takeIf { it.isNotBlank() }
                    ?: exitWith("--host= 后面要跟一个地址")
                index += 1
            }

            arg == "--port" -> {
                port = args.getOrNull(index + 1)?.toIntOrNull() ?: exitWith("--port 后面要跟一个 1–65535 的整数")
                index += 2
            }

            arg.startsWith("--port=") -> {
                port = arg.substringAfter("--port=").toIntOrNull() ?: exitWith("--port= 后面要跟一个 1–65535 的整数")
                index += 1
            }

            arg == "--token" -> {
                token = args.getOrNull(index + 1)?.takeIf { it.isNotBlank() }
                    ?: exitWith("--token 后面要跟一个非空的共享密钥")
                index += 2
            }

            arg.startsWith("--token=") -> {
                token = arg.substringAfter("--token=").takeIf { it.isNotBlank() }
                    ?: exitWith("--token= 后面要跟一个非空的共享密钥")
                index += 1
            }

            else -> exitWith("不认识的参数：$arg（用 --help 看用法）")
        }
    }
    if (port !in 1..65535) exitWith("端口必须在 1–65535 之间，收到 $port")
    // 环境变量是「不想把 token 写进进程参数（ps 可见）」时的替代入口。
    if (token == null) {
        token = System.getenv("MYFRIEND_AGENTD_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
    }
    if (token != null && token.length < MIN_TOKEN_LENGTH) {
        System.err.println("提示：共享 token 少于 $MIN_TOKEN_LENGTH 个字符，容易被猜到；建议用 `openssl rand -hex 32` 生成。")
    }
    return Options(host = host, port = port, token = token, help = help)
}

private fun isLoopbackHost(host: String): Boolean {
    val value = host.trim().lowercase()
    if (value == "localhost" || value == "127.0.0.1" || value == "::1" || value == "[::1]") return true
    return value.startsWith("127.")
}

private fun printSecurityNotice(options: Options) {
    if (isLoopbackHost(options.host)) {
        if (options.token != null) {
            println("  已启用共享 token：所有请求都要带 `Authorization: Bearer <token>`")
        }
        return
    }
    println("  注意：监听地址不是回环地址，而本服务没有 TLS。")
    if (options.token == null) {
        System.err.println("警告：${options.host} 上没有鉴权——同一网络里的任何主机都能调用这 4 个接口。")
        System.err.println("      只在可信网络里这样用；要暴露给别的机器请加 --token，或放在带鉴权的反向代理后面。")
    } else {
        System.err.println("提示：已启用 --token，但流量仍是明文 HTTP；不要把 token 用在不受信网络。")
    }
}

private fun printUsage() {
    println(
        """
        myfriend-agentd $VERSION —— 两端共用的 agent 核心（HTTP 骨架）

        用法：
          agentd [--host 127.0.0.1] [--port 8788] [--token <共享密钥>]

        选项：
          --host <地址>     监听地址，默认 127.0.0.1（只给本机/同进程用）
          --port <端口>     监听端口，默认 8788
          --token <密钥>    可选：所有请求要求 Authorization: Bearer <密钥>
                            （也可用环境变量 MYFRIEND_AGENTD_TOKEN，避免密钥出现在进程参数里）
          --help, -h        打印这段说明

        接口：
          GET  /health                      健康检查（返回服务名与版本；需要 token 时也要带）
          POST /v1/core/sleep-decision      睡眠/免打扰/唤醒阈值判定
          POST /v1/core/defer-plan          defer_reply 时间计划
          POST /v1/core/proactive-cap       主动程度 → 每日上限
          POST /v1/core/tool-args           工具参数夹取

        边界（0.11.1 起）：
          请求体上限 1 MiB（超出 413）；读请求体超时 5 秒（超时 408）；
          请求由 4 个固定工作线程处理，慢连接不会卡住 /health。
        """.trimIndent()
    )
}

private fun exitWith(message: String): Nothing {
    System.err.println(message)
    println("用 --help 看用法")
    kotlin.system.exitProcess(2)
}
