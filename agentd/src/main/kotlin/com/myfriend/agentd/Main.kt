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
 * 0.12.0 起再加一层按来源的令牌桶限流（`--rate-limit` / `--rate-burst`），
 * 但仍然**没有 TLS**——见 README 的「限流」与「部署与 TLS」两节。
 *
 * 客户端（Android/iOS 远程模式）目前不发 `Authorization`，所以默认不设 token；
 * 设了 token 之后所有请求（含 `/health`）都必须带 `Authorization: Bearer <token>`。
 */
fun main(args: Array<String>) {
    val options = try {
        parseOptions(args)
    } catch (failure: IllegalArgumentException) {
        exitWith(failure.message ?: "参数不正确")
    }
    if (options.help) {
        printUsage()
        return
    }
    val rateLimiter = RateLimiter(options.rateLimitPerMinute, options.rateBurst)
    val server: AgentdServer.StartedServer = try {
        AgentdServer.start(options.host, options.port, options.token, rateLimiter)
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
    if (rateLimiter.enabled) {
        println(
            "  限流：每个来源 ${options.rateLimitPerMinute} 请求/分钟，突发 ${options.rateBurst}；" +
                "/health 单独计一个桶"
        )
    } else {
        println("  限流：已关闭（--rate-limit 0）")
    }
    println("  GET  /health")
    println("  POST /v1/core/sleep-decision")
    println("  POST /v1/core/defer-plan")
    println("  POST /v1/core/proactive-cap")
    println("  POST /v1/core/tool-args")
}

private const val VERSION = AgentdApi.VERSION

/** 共享 token 的最短长度：只做提醒，不拒绝启动（部署方可能有自己的考虑）。 */
private const val MIN_TOKEN_LENGTH = 16

/** 共享 token 的环境变量入口（不写进 `ps`）。 */
internal const val ENV_TOKEN = "MYFRIEND_AGENTD_TOKEN"

/** 限流速率的环境变量入口（请求/分钟，0 = 关闭）。 */
internal const val ENV_RATE_LIMIT = "MYFRIEND_AGENTD_RATE_LIMIT"

/** 限流突发的环境变量入口。 */
internal const val ENV_RATE_BURST = "MYFRIEND_AGENTD_RATE_BURST"

/**
 * 解析后的启动参数。
 *
 * `internal` 是为了让 `MainOptionsTest` 不用启动服务就能覆盖「命令行 > 环境变量 > 默认值」
 * 这三层优先级；`main` 之外不应该有别的调用方。
 */
internal data class Options(
    val host: String = "127.0.0.1",
    val port: Int = 8788,
    val token: String? = null,
    val rateLimitPerMinute: Int = RateLimiter.DEFAULT_PER_MINUTE,
    val rateBurst: Int = RateLimiter.DEFAULT_BURST,
    val help: Boolean = false
)

/**
 * 解析命令行 + 环境变量，**不退出进程**（出错抛 [IllegalArgumentException]，由 `main` 转成退出码 2）。
 *
 * 优先级：命令行显式值 > 环境变量 > 默认值。这样容器里可以用环境变量固定配置，
 * 临时覆盖时再用命令行（例如 `--rate-limit 0` 临时关掉限流做压测）。
 */
internal fun parseOptions(
    args: Array<String>,
    env: Map<String, String> = System.getenv()
): Options {
    var host = "127.0.0.1"
    var port = 8788
    var help = false
    var token: String? = null
    var rateLimit: Int? = null
    var rateBurst: Int? = null
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
                    ?: throw IllegalArgumentException("--host 后面要跟一个地址，例如 127.0.0.1")
                index += 2
            }

            arg.startsWith("--host=") -> {
                host = arg.substringAfter("--host=").takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("--host= 后面要跟一个地址")
                index += 1
            }

            arg == "--port" -> {
                port = intArgument("--port", args.getOrNull(index + 1))
                index += 2
            }

            arg.startsWith("--port=") -> {
                port = intArgument("--port", arg.substringAfter("--port="))
                index += 1
            }

            arg == "--token" -> {
                token = args.getOrNull(index + 1)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("--token 后面要跟一个非空的共享密钥")
                index += 2
            }

            arg.startsWith("--token=") -> {
                token = arg.substringAfter("--token=").takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("--token= 后面要跟一个非空的共享密钥")
                index += 1
            }

            arg == "--rate-limit" -> {
                rateLimit = intArgument("--rate-limit", args.getOrNull(index + 1))
                index += 2
            }

            arg.startsWith("--rate-limit=") -> {
                rateLimit = intArgument("--rate-limit", arg.substringAfter("--rate-limit="))
                index += 1
            }

            arg == "--rate-burst" -> {
                rateBurst = intArgument("--rate-burst", args.getOrNull(index + 1))
                index += 2
            }

            arg.startsWith("--rate-burst=") -> {
                rateBurst = intArgument("--rate-burst", arg.substringAfter("--rate-burst="))
                index += 1
            }

            else -> throw IllegalArgumentException("不认识的参数：$arg（用 --help 看用法）")
        }
    }
    if (port !in 1..65535) throw IllegalArgumentException("端口必须在 1–65535 之间，收到 $port")

    // 环境变量是「不想把 token 写进进程参数（ps 可见）」时的替代入口。
    if (token == null) {
        token = env[ENV_TOKEN]?.trim()?.takeIf { it.isNotEmpty() }
    }
    val envRateLimit = envInt(env, ENV_RATE_LIMIT)
    val envRateBurst = envInt(env, ENV_RATE_BURST)
    val perMinute = rateLimit ?: envRateLimit ?: RateLimiter.DEFAULT_PER_MINUTE
    val burst = rateBurst ?: envRateBurst ?: RateLimiter.DEFAULT_BURST
    if (perMinute < 0) {
        throw IllegalArgumentException(
            "${sourceOf(rateLimit, ENV_RATE_LIMIT, "--rate-limit")} 不能是负数（0 = 关闭限流），收到 $perMinute"
        )
    }
    if (burst < 1) {
        throw IllegalArgumentException(
            "${sourceOf(rateBurst, ENV_RATE_BURST, "--rate-burst")} 至少是 1，收到 $burst"
        )
    }

    return Options(
        host = host,
        port = port,
        token = token,
        rateLimitPerMinute = perMinute,
        rateBurst = burst,
        help = help
    )
}

private fun intArgument(flag: String, raw: String?): Int =
    raw?.trim()?.toIntOrNull()
        ?: throw IllegalArgumentException("$flag 后面要跟一个整数（收到 ${raw?.trim()?.ifEmpty { "空字符串" } ?: "缺参数"}）")

/** 出错时告诉用户「这个值是从命令行还是环境变量来的」，而不是笼统写一个 flag。 */
private fun sourceOf(cliValue: Int?, envName: String, flag: String): String =
    if (cliValue == null) "环境变量 $envName" else flag

private fun envInt(env: Map<String, String>, name: String): Int? {
    val raw = env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return raw.toIntOrNull() ?: throw IllegalArgumentException("环境变量 $name 必须是整数，收到 $raw")
}

private fun isLoopbackHost(host: String): Boolean {
    val value = host.trim().lowercase()
    if (value == "localhost" || value == "127.0.0.1" || value == "::1" || value == "[::1]") return true
    return value.startsWith("127.")
}

private fun printSecurityNotice(options: Options) {
    if (options.token != null && options.token.length < MIN_TOKEN_LENGTH) {
        System.err.println(
            "提示：共享 token 少于 $MIN_TOKEN_LENGTH 个字符，容易被猜到；建议用 `openssl rand -hex 32` 生成。"
        )
    }
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
                 [--rate-limit 60] [--rate-burst 30]

        选项：
          --host <地址>            监听地址，默认 127.0.0.1（只给本机/同进程用）
          --port <端口>            监听端口，默认 8788
          --token <密钥>           可选：所有请求要求 Authorization: Bearer <密钥>
                                   （也可用环境变量 MYFRIEND_AGENTD_TOKEN，避免密钥出现在进程参数里）
          --rate-limit <n>         每个来源每分钟允许的请求数，默认 60；0 = 关闭限流
                                   （环境变量 MYFRIEND_AGENTD_RATE_LIMIT）
          --rate-burst <n>         突发容量（空闲时可以攒下的令牌数），默认 30，至少 1
                                   （环境变量 MYFRIEND_AGENTD_RATE_BURST）
          --help, -h               打印这段说明

        接口：
          GET  /health                      健康检查（返回服务名与版本；需要 token 时也要带）
          POST /v1/core/sleep-decision      睡眠/免打扰/唤醒阈值判定
          POST /v1/core/defer-plan          defer_reply 时间计划
          POST /v1/core/proactive-cap       主动程度 → 每日上限
          POST /v1/core/tool-args           工具参数夹取

        边界：
          请求体上限 1 MiB（超出 413）；读请求体超时 5 秒（超时 408）；
          请求由 4 个固定工作线程处理，慢连接不会卡住 /health；
          每个来源一个令牌桶，超限 429 + Retry-After；/health 单独计一个桶。
          服务本身只有明文 HTTP：对外部署请放在 Caddy/Nginx/Traefik 后面由代理终止 TLS
          （见 agentd/README.md 的「部署与 TLS」）。
        """.trimIndent()
    )
}

private fun exitWith(message: String): Nothing {
    System.err.println(message)
    println("用 --help 看用法")
    kotlin.system.exitProcess(2)
}
