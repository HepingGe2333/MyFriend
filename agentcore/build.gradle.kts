plugins {
    kotlin("jvm")
}

// `agentcore` 是「两端必须一致的 agent 决策」的唯一一份实现：
// Android（`:app`）与 JVM 服务（`:agentd`）都依赖它，谁也不再复制源码、谁也不再维护 exclude 列表。
//
// 硬约束（0.11.0 的架构前提，动它会同时影响两端）：
// - **零第三方 runtime 依赖**：只用 Kotlin 标准库 + JDK。这样 `:agentd` 的「零依赖可执行 jar」
//   仍然成立（fatJar 里只会多 AgentCore 的 class），Android 侧也不会被动多出传递依赖。
// - **不依赖 Android / Room / Hilt / kotlinx-serialization / kotlinx-coroutines**：
//   纯函数 + 纯数据类，JVM 单测就能覆盖，不需要 Robolectric 也不需要 coroutine test。
// - 包名保持 `com.myfriend.app.domain`：源码是从 `app/src/main/java/com/myfriend/app/domain`
//   搬过来的，包名不变，Android 侧与既有测试的 import 一行都不用改。
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 运行时零第三方依赖；JUnit 只进测试源集。
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
    }
}
