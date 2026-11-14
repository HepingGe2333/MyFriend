package com.myfriend.agentd

import com.sun.net.httpserver.HttpServer

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
 * 真要部署成独立服务器时显式传 `--host 0.0.0.0`，并由部署方自己加鉴权/反向代理 ——
 * 这个骨架里刻意没有鉴权（见 README 的「还没做」）。
 */
fun main(args: Array<String>) {
    val options = parseArgs(args)
    if (options.help) {
        printUsage()
        return
    }
    val server: HttpServer = try {
        AgentdServer.start(options.host, options.port)
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
    println("  GET  /health")
    println("  POST /v1/core/sleep-decision")
    println("  POST /v1/core/defer-plan")
    println("  POST /v1/core/proactive-cap")
    println("  POST /v1/core/tool-args")
}

private const val VERSION = AgentdApi.VERSION

private data class Options(
    val host: String = "127.0.0.1",
    val port: Int = 8788,
    val help: Boolean = false
)

private fun parseArgs(args: Array<String>): Options {
    var host = "127.0.0.1"
    var port = 8788
    var help = false
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

            else -> exitWith("不认识的参数：$arg（用 --help 看用法）")
        }
    }
    if (port !in 1..65535) exitWith("端口必须在 1–65535 之间，收到 $port")
    return Options(host = host, port = port, help = help)
}

private fun printUsage() {
    println(
        """
        myfriend-agentd $VERSION —— 两端共用的 agent 核心（HTTP 骨架）

        用法：
          agentd [--host 127.0.0.1] [--port 8788]

        选项：
          --host <地址>   监听地址，默认 127.0.0.1（只给本机/同进程用）
          --port <端口>   监听端口，默认 8788
          --help, -h      打印这段说明

        接口：
          GET  /health                      健康检查
          POST /v1/core/sleep-decision      睡眠/免打扰/唤醒阈值判定
          POST /v1/core/defer-plan          defer_reply 时间计划
          POST /v1/core/proactive-cap       主动程度 → 每日上限
          POST /v1/core/tool-args           工具参数夹取
        """.trimIndent()
    )
}

private fun exitWith(message: String): Nothing {
    System.err.println(message)
    println("用 --help 看用法")
    kotlin.system.exitProcess(2)
}
