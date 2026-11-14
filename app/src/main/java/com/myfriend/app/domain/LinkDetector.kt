package com.myfriend.app.domain

/**
 * 链接在原文里的形态。
 *
 * 只为「在应用里看一眼他发来的东西」服务，不联网、不做语义判断。
 */
enum class LinkKind {
    /** 带完整协议的地址，例如 `https://example.com/a`。 */
    URL,

    /** 没写协议的裸域名，例如 `example.com/a`、`www.example.com`。 */
    BARE_DOMAIN,

    /** Markdown 的 `[标题](地址)` 写法，点击区域是可见的「标题」。 */
    MARKDOWN
}

/**
 * 一段链接在原文中的位置。
 *
 * [start] 含、[end] 不含，单位是原文里的字符下标；[url] 是可以直接加载的地址。
 * Markdown 链接的区间只覆盖可见的标题文字（不含 `[]` 与 `(...)`）。
 */
data class LinkSpan(
    val start: Int,
    val end: Int,
    val url: String,
    val kind: LinkKind
) {
    /** 区间长度，方便渲染层判断边界。 */
    val length: Int get() = end - start
}

/**
 * 从一段文字里找出可点的链接，并把各种奇形怪状的写法收敛成 http/https 地址。
 *
 * 约定：
 * - 纯函数：不联网、不读配置、任何输入都不抛异常（流式输出截断的半个链接也要能安全处理）；
 * - 只放行 http / https，`javascript:`、`file:`、`content:`、`intent:`、`data:` 一律拒绝；
 * - 中文标点、句号、成对括号等会话里的尾巴会被剔出地址，不跟着链接一起点；
 * - 只认看得懂的形态，宁可少识别，也不要把普通文字误标成链接。
 */
object LinkDetector {

    /** 单条地址的长度上限，防止把一大段无空格文本当成一条链接。 */
    const val MAX_URL_LENGTH: Int = 2048

    /** 裸域名后面不跟路径时也要有上限，避免长串编号被当成域名。 */
    private const val MAX_HOST_LENGTH: Int = 253

    /** 这些斜杠后缀更像文件名，不像域名后缀（`.md`、`.png` 之类不点亮）。 */
    private val FILE_EXTENSION_LIKE = setOf(
        "txt", "md", "markdown", "kt", "kts", "java", "class", "js", "ts", "jsx", "tsx",
        "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "swift", "php", "sh", "bat",
        "json", "xml", "yaml", "yml", "toml", "ini", "conf", "properties", "gradle",
        "lock", "log", "csv", "tsv", "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg",
        "ico", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "rar", "7z",
        "gz", "tar", "apk", "aab", "so", "dll", "exe", "bin", "mp3", "mp4", "mov", "wav",
        "avi", "mkv", "flac", "html", "htm", "css"
    )

    /**
     * 找出文字里所有链接，按出现位置排序，区间互不重叠。
     *
     * 括号、引号、中文标点都不会被算进链接；Markdown 链接的区间指向标题。
     */
    fun findAll(text: String): List<LinkSpan> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<LinkSpan>()
        var index = 0
        while (index < text.length) {
            val markdown = matchMarkdownLink(text, index)
            if (markdown != null) {
                result += markdown.span
                index = markdown.endExclusive
                continue
            }
            val schemeUrl = matchSchemeUrl(text, index)
            if (schemeUrl != null) {
                result += schemeUrl
                index = schemeUrl.end
                continue
            }
            val bareDomain = matchBareDomain(text, index)
            if (bareDomain != null) {
                result += bareDomain
                index = bareDomain.end
                continue
            }
            index++
        }
        return result
    }

    /**
     * 把任意写法收敛成可加载的 http/https 地址；不是链接时返回 null。
     *
     * 会补 `https://`、去掉尾部标点、拒绝非 http(s) 的协议。
     */
    fun normalize(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty() || value.length > MAX_URL_LENGTH) return null
        if (!canStandAsUrl(value)) return null

        // 尖括号包裹是 Markdown/邮件里常见的写法：<https://example.com>
        if (value.length > 2 && value.first() == '<' && value.last() == '>') {
            value = value.substring(1, value.length - 1).trim()
        }
        value = trimTrailingPunctuation(value)
        if (value.isEmpty() || value.length > MAX_URL_LENGTH) return null
        // 协议相对写法 //example.com 补成 https
        if (value.startsWith("//")) value = "https:$value"

        val scheme = detectScheme(value)
        when (scheme) {
            null -> {
                // 没写协议：必须像个域名，否则「第一部分：……」这种普通文字会被误判
                val hostPart = value.takeWhile { it != '/' && it != '?' && it != '#' }
                if (!looksLikeBareDomain(hostPart)) return null
                value = "https://$value"
            }
            "http", "https" -> Unit
            // javascript:、file:、content:、intent:、data:、mailto: 等一律不放行
            else -> return null
        }

        return if (isLoadableHttpUrl(value)) value else null
    }

    // ---------------------------------------------------------------- 匹配

    /** 一次匹配的结果：链接本身 + 这段写法在原文里结束的位置。 */
    private data class Match(val span: LinkSpan, val endExclusive: Int)

    /** `[标题](地址)`：区间只覆盖标题，跳过整个写法。 */
    private fun matchMarkdownLink(text: String, bracketStart: Int): Match? {
        if (text[bracketStart] != '[') return null
        val labelStart = bracketStart + 1
        val labelEnd = text.indexOf("](", labelStart)
        if (labelEnd <= labelStart) return null
        val urlStart = labelEnd + 2
        val label = text.substring(labelStart, labelEnd)
        if (label.isEmpty() || label.contains('\n') || label.length > MAX_URL_LENGTH) return null
        // 地址里本身可能带成对的括号（维基百科那种 …/Foo_(bar)），
        // 不能拿第一个 ')' 收尾，否则会被截成一个 404 地址、后面还留半个括号在正文里。
        val closed = closeParenFor(text, urlStart)
        if (closed <= urlStart) return null
        val url = normalize(text.substring(urlStart, closed)) ?: return null
        return Match(LinkSpan(labelStart, labelEnd, url, LinkKind.MARKDOWN), closed + 1)
    }

    /**
     * 从 [start] 起找到与地址开头表达式配对的右括号位置；找不到返回 -1。
     *
     * 只在同一行里找，并且遇到空白就直接放弃——Markdown 链接的地址段不该有空格，
     * 有空格说明这根本不是链接（宁可少识别，也不要把后面半段正文吃进地址）。
     */
    private fun closeParenFor(text: String, start: Int): Int {
        var depth = 0
        var index = start
        while (index < text.length) {
            when (text[index]) {
                '(' -> depth++
                '\n' -> return -1
                ' ' -> return -1
                ')' -> {
                    if (depth == 0) return index
                    depth--
                }
            }
            index++
        }
        return -1
    }

    /** `http://` / `https://` 开头的地址。 */
    private fun matchSchemeUrl(text: String, start: Int): LinkSpan? {
        if (!startsWithScheme(text, start)) return null
        if (!isLinkBoundary(text, start)) return null
        val rawEnd = consumeUrlChars(text, start)
        if (rawEnd <= start || rawEnd - start > MAX_URL_LENGTH) return null
        val trimmed = trimTrailingPunctuation(text.substring(start, rawEnd))
        if (trimmed.isEmpty()) return null
        val url = normalize(trimmed) ?: return null
        return LinkSpan(start, start + trimmed.length, url, LinkKind.URL)
    }

    /** 没写协议的域名，例如 `example.com/x`、`www.example.com`。 */
    private fun matchBareDomain(text: String, start: Int): LinkSpan? {
        if (!isBareDomainCandidate(text, start)) return null
        if (!isLinkBoundary(text, start)) return null
        val rawEnd = consumeUrlChars(text, start)
        if (rawEnd <= start || rawEnd - start > MAX_URL_LENGTH) return null
        val trimmed = trimTrailingPunctuation(text.substring(start, rawEnd))
        if (trimmed.isEmpty()) return null
        val hostPart = trimmed.takeWhile { it != '/' && it != '?' && it != '#' }
        if (!looksLikeBareDomain(hostPart)) return null
        // 识别出来的地址必须和 normalize 的判断一致，避免渲染层与检测层各说各话
        val url = normalize(trimmed) ?: return null
        return LinkSpan(start, start + trimmed.length, url, LinkKind.BARE_DOMAIN)
    }

    // ---------------------------------------------------------------- 扫描辅助

    private fun startsWithScheme(text: String, index: Int): Boolean {
        if (index > 0) {
            val previous = text[index - 1]
            // 只从链接真正开始的位置试：`xhttps://`、`a.https://` 之类不算
            if (isWordChar(previous) || previous == '@' || previous == '.' || previous == '/' ||
                previous == '%' || previous == '-'
            ) return false
        }
        return text.startsWith("https://", index, ignoreCase = true) ||
            text.startsWith("http://", index, ignoreCase = true)
    }

    /** 从 [start] 往后吃掉所有可能属于地址的 ASCII 字符。 */
    private fun consumeUrlChars(text: String, start: Int): Int {
        var index = start
        val limit = minOf(text.length, start + MAX_URL_LENGTH + 1)
        while (index < limit && isUrlChar(text[index])) index++
        return index
    }

    private fun isUrlChar(char: Char): Boolean {
        // 空格、控制符、中文和全角标点都算地址结束
        if (char.code < 0x21 || char.code > 0x7E) return false
        return when (char) {
            // `*` 留给 Markdown 的粗体/斜体标记，别把它吃进地址
            '<', '>', '"', '\'', '`', '[', ']', '{', '}', '|', '\\', '^', '*' -> false
            else -> true
        }
    }

    /** 链接前面不能紧贴单词字符，否则 `someone@example.com`、`v1.2` 会被切开当链接。 */
    private fun isLinkBoundary(text: String, start: Int): Boolean {
        if (start == 0) return true
        val previous = text[start - 1]
        return !isWordChar(previous) && previous != '@' && previous != '.' &&
            previous != '/' && previous != '%' && previous != '-'
    }

    /** 只把 ASCII 字母数字当「单词的一部分」：`看example.com` 这种中文紧贴的链接也要能认出来。 */
    private fun isWordChar(char: Char): Boolean =
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_'

    /**
     * 便宜的预筛：只有以字母数字开头、且后面不远就有 `www.` 或一个点的位置才值得细看。
     */
    private fun isBareDomainCandidate(text: String, start: Int): Boolean {
        val char = text[start]
        if (!char.isLetterOrDigit()) return false
        if (start > 0 && (isWordChar(text[start - 1]) || text[start - 1] == '@')) return false
        val limit = minOf(text.length, start + 64)
        var index = start
        while (index < limit) {
            if (text[index] == '.') return true
            index++
        }
        return false
    }

    // ---------------------------------------------------------------- 校验

    /** 只做粗筛：地址里不能有空白、换行和控制字符。 */
    private fun canStandAsUrl(value: String): Boolean {
        for (char in value) {
            if (char.isWhitespace()) return false
            val code = char.code
            if (code < 0x20 || code == 0x7F) return false
        }
        return true
    }

    /** 冒号前是不是一个已知的协议名；不是的话说明冒号属于「域名:端口」。 */
    private fun detectScheme(value: String): String? {
        val colon = value.indexOf(':')
        if (colon <= 0) return null
        val scheme = value.substring(0, colon)
        if (scheme.length > 32) return null
        if (!scheme[0].isLetter()) return null
        // 协议名里不允许点，这样 example.com:8080 不会被当成协议
        if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' }) return null
        return scheme.lowercase()
    }

    /**
     * 裸域名（可能带端口）的形态检查：至少两段、后缀是字母、不能是文件名后缀。
     */
    private fun looksLikeBareDomain(hostPart: String): Boolean {
        if (hostPart.isEmpty()) return false
        val host = hostPart.substringBefore(':')
        val port = hostPart.substringAfter(':', missingDelimiterValue = "")
        if (hostPart.contains(':') && (port.isEmpty() || port.length > 5 || port.any { !it.isDigit() })) {
            return false
        }
        if (!isValidHost(host)) return false
        val tld = host.substringAfterLast('.').lowercase()
        return tld !in FILE_EXTENSION_LIKE
    }

    private fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > MAX_HOST_LENGTH) return false
        if (host.startsWith('.') || host.endsWith('.')) return false
        val labels = host.split('.')
        // 只认能落地的主机名：单段（localhost 之类）不算，避免把 `https://abc` 也点亮
        if (labels.size < 2) return false
        if (labels.any { it.isEmpty() || it.length > 63 }) return false
        if (labels.any { label ->
                label.any { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-') }
            }
        ) return false
        if (labels.any { it.startsWith('-') || it.endsWith('-') }) return false
        // IPv4 字面量：192.168.1.1 这种也是可以看的地址
        if (labels.size == 4 && labels.all { label ->
                label.length <= 3 && label.all { it.isDigit() } && label.toInt() in 0..255
            }
        ) return true
        val tld = labels.last()
        if (tld.length < 2) return false
        if (!tld.all { it in 'a'..'z' || it in 'A'..'Z' } && !tld.lowercase().startsWith("xn--")) {
            return false
        }
        return true
    }

    /** 完整地址的结构检查：协议、有权重的主机名、合法端口。 */
    private fun isLoadableHttpUrl(url: String): Boolean {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return false
        val scheme = url.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return false
        val rest = url.substring(schemeEnd + 3)
        if (rest.isEmpty()) return false
        if (rest.length > MAX_URL_LENGTH) return false
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.isEmpty()) return false
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.isEmpty()) return false
        // IPv6 字面量（[::1]:8080）只放行带方括号的写法
        if (hostPort.startsWith("[")) return hostPort.contains(']')
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', missingDelimiterValue = "")
        if (hostPort.contains(':') && (port.isEmpty() || port.length > 5 || port.any { !it.isDigit() })) {
            return false
        }
        return isValidHost(host)
    }

    /** 去掉句尾、中文标点和不成对括号；括号成对时保留（维基百科那种网址）。 */
    private fun trimTrailingPunctuation(raw: String): String {
        var end = raw.length
        while (end > 0) {
            val char = raw[end - 1]
            val trim = when (char) {
                '.', ',', ';', ':', '!', '?', '\'', '"',
                '。', '，', '、', '；', '：', '！', '？', '…',
                '）', '】', '》', '」', '』', '”', '’', '·' -> true
                ')' -> countChar(raw, end, ')') > countChar(raw, end, '(')
                ']' -> countChar(raw, end, ']') > countChar(raw, end, '[')
                else -> false
            }
            if (!trim) break
            end--
        }
        return raw.substring(0, end)
    }

    private fun countChar(raw: String, end: Int, char: Char): Int {
        var count = 0
        var index = 0
        while (index < end) {
            if (raw[index] == char) count++
            index++
        }
        return count
    }
}
