package com.myfriend.app.i18n

import com.myfriend.app.util.StringLookup
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 单测里直接读 res 下 values / values-en 里的文案。
 *
 * 这样「记忆整理结果」这类纯拼句子的工具可以在 JVM 单测里跑，又不用把中文抄一份到测试里：
 * 断言的是真实资源，改了文案而没改预期，测试会红。
 *
 * 只做 Android 字符串资源里那几种转义（`\n` `\t` `\'` `\"` `\\` `\uXXXX`），
 * XML 实体由解析器处理；这是测试脚手架，不是通用的 aapt 实现。
 */
object TestResources {

    /** `values` / `values-en` 这类目录名。 */
    private val RES_DIR: File by lazy { locateResDir() }

    private fun locateResDir(): File {
        val candidates = listOf(
            File("src/main/res"),
            File("app/src/main/res")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 res 目录，当前工作目录：${File(".").absolutePath}")
    }

    fun valuesDir(qualifier: String = "values"): File = File(RES_DIR, qualifier)

    /** 一个语言目录下所有 xml 文件路径（排序，便于报错时稳定复现）。 */
    fun xmlFiles(qualifier: String = "values"): List<File> =
        valuesDir(qualifier).listFiles { file -> file.isFile && file.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            .orEmpty()

    /** 名字 → 原文（已反转义）。同名 key 出现在两个文件里算错。 */
    fun strings(qualifier: String = "values"): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        xmlFiles(qualifier).forEach { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                val name = element.getAttribute("name")
                require(result.put(name, unescape(element.textContent)) == null) {
                    "重复的 string key「$name」，出现在 ${file.name}"
                }
            }
        }
        return result
    }

    /** 名字 → 各 quantity 的原文。 */
    fun plurals(qualifier: String = "values"): Map<String, Map<String, String>> {
        val result = LinkedHashMap<String, Map<String, String>>()
        xmlFiles(qualifier).forEach { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("plurals")
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                val name = element.getAttribute("name")
                val items = LinkedHashMap<String, String>()
                val children = element.getElementsByTagName("item")
                for (j in 0 until children.length) {
                    val item = children.item(j) as Element
                    items[item.getAttribute("quantity")] = unescape(item.textContent)
                }
                result[name] = items
            }
        }
        return result
    }

    /** 别的资源类型（integer 之类）的 key 集合，一致性守卫会一起比。 */
    fun keysOfType(tag: String, qualifier: String = "values"): Set<String> {
        val result = LinkedHashSet<String>()
        xmlFiles(qualifier).forEach { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                result.add((nodes.item(i) as Element).getAttribute("name"))
            }
        }
        return result
    }

    /** 一个按资源 id 取文案的实现，断言用的就是 `values/` 或 `values-en/` 的原话。 */
    fun lookup(qualifier: String = "values"): StringLookup {
        val names = idToName
        val text = strings(qualifier)
        return StringLookup { id, args ->
            val name = names[id] ?: error("资源 id 0x${id.toString(16)} 不在 R.string 里")
            val template = text[name] ?: error("R.string.$name 没有在 $qualifier 里定义")
            if (args.isEmpty()) template else String.format(Locale.ROOT, template, *args)
        }
    }

    /** `R.string` 的 id → 名字；反射一次，之后靠字符串名找文案。 */
    private val idToName: Map<Int, String> by lazy {
        val rClass = Class.forName("com.myfriend.app.R\$string")
        rClass.declaredFields.mapNotNull { field ->
            runCatching { field.isAccessible = true; field.getInt(null) to field.name }.getOrNull()
        }.toMap()
    }

    /** Android 字符串资源里的转义，够本工程用。 */
    fun unescape(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i == raw.length - 1) {
                out.append(c)
                i++
                continue
            }
            when (val next = raw[i + 1]) {
                'n' -> { out.append('\n'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                '\'' -> { out.append('\''); i += 2 }
                '"' -> { out.append('"'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                'u' -> {
                    out.append(raw.substring(i + 2, i + 6).toInt(16).toChar())
                    i += 6
                }
                else -> { out.append(next); i += 2 }
            }
        }
        return out.toString()
    }
}
