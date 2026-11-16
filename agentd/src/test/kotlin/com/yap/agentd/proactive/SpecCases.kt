package com.yap.agentd.proactive

import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * 读 `spec/agent-core/cases/` 下那些 JSON 的测试夹具。
 *
 * `spec/agent-core/` 是两端的可执行契约：同一批 JSON 被 Android、iOS、`:agentcore` 三套测试读。
 * `agentd` 的主动引擎不重新发明规则（它直接调 `:agentcore`），但仍然要证明**接线**是对的：
 * 睡眠判定、每日上限、心跳概率这三条链路在服务端拿到的输入与契约用例一致、输出也一致。
 *
 * 路径怎么找：Gradle 跑单测时 `user.dir` 是模块目录（`agentd/`），IDE 里可能是仓库根。
 * 所以从 `user.dir` 一路往上找「同时有 settings.gradle.kts 和 spec/agent-core/cases」的目录；
 * **找不到就跳过**（当 `agentd` 被单独拷出去构建时，跳过等价于 iOS 的 XCTSkip），
 * 而不是让一次「没有仓库上下文」的构建变红。
 */
internal object SpecCases {

    /** 仓库根；找不到返回 null（调用方用 [assumeTrue] 跳过）。 */
    fun repoRootOrNull(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val settings = File(dir, "settings.gradle.kts")
            val cases = File(dir, "spec/agent-core/cases")
            if (settings.isFile && cases.isDirectory) return dir
            dir = dir.parentFile
        }
        return null
    }

    /** 读一个用例文件；找不到就跳过当前测试。 */
    fun cases(fileName: String): JsonValue.Obj? {
        val root = repoRootOrNull()
        assumeTrue(
            "找不到仓库根目录（settings.gradle.kts + spec/agent-core/cases），跳过 $fileName 契约用例",
            root != null
        )
        val file = File(root, "spec/agent-core/cases/$fileName.json")
        assumeTrue("用例文件不存在：${file.absolutePath}", file.isFile)
        return Json.parse(file.readText(Charsets.UTF_8)) as? JsonValue.Obj
    }

    /** 遍历 `cases[]`，把断言失败包成「文件 + 用例名」的形式（红了就知道是哪一条）。 */
    fun forEachCase(
        fileName: String,
        block: (name: String, input: JsonValue.Obj, expect: JsonValue.Obj) -> Unit
    ) {
        val root = cases(fileName) ?: return
        val list = root.fields["cases"] as? JsonValue.Arr ?: error("$fileName 缺少 cases 数组")
        for (element in list.items) {
            val entry = element as? JsonValue.Obj ?: error("$fileName 的 cases 元素必须是对象")
            val name = entry.fields["name"].asString().orEmpty()
            val input = entry.fields["input"] as? JsonValue.Obj ?: error("$fileName/$name 缺少 input")
            val expect = entry.fields["expect"] as? JsonValue.Obj ?: error("$fileName/$name 缺少 expect")
            try {
                block(name, input, expect)
            } catch (failure: AssertionError) {
                throw AssertionError("[$fileName / $name] ${failure.message}", failure)
            }
        }
    }
}

internal fun JsonValue?.asString(): String? = (this as? JsonValue.Str)?.value

internal fun JsonValue?.asInt(): Int? = (this as? JsonValue.Num)?.asInt

internal fun JsonValue?.asLong(): Long? = (this as? JsonValue.Num)?.asLong

internal fun JsonValue?.asBool(): Boolean? = (this as? JsonValue.Bool)?.value

internal fun JsonValue?.asDouble(): Double? = (this as? JsonValue.Num)?.asDouble

internal fun JsonValue.Obj.str(key: String): String? = fields[key].asString()

internal fun JsonValue.Obj.int(key: String): Int? = fields[key].asInt()

internal fun JsonValue.Obj.long(key: String): Long? = fields[key].asLong()

internal fun JsonValue.Obj.bool(key: String): Boolean? = fields[key].asBool()

internal fun JsonValue.Obj.double(key: String): Double? = fields[key].asDouble()

internal fun JsonValue.Obj.obj(key: String): JsonValue.Obj? = fields[key] as? JsonValue.Obj
