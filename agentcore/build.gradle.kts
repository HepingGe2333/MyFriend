plugins {
    kotlin("multiplatform")
}

// `agentcore` 是「两端必须一致的 agent 决策」的唯一一份实现：
// - Android（`:app`）与 JVM 服务（`:agentd`）消费 `jvm()` 变体；
// - iOS 侧（`ios/`）消费 `AgentCoreKit.framework`（Kotlin/Native 静态 framework）。
//
// 硬约束（0.11.0 的架构前提，动它会同时影响两端）：
// - **零第三方 runtime 依赖**：commonMain 只用 Kotlin 标准库；jvmMain 只用 JDK。
//   这样 `:agentd` 的「零依赖可执行 jar」仍然成立，Android 侧也不会被动多出传递依赖。
// - **不依赖 Android / Room / Hilt / kotlinx-serialization / kotlinx-coroutines**：
//   纯函数 + 纯数据类，JVM 单测就能覆盖。
// - 包名保持 `com.yap.app.domain`：Android 侧与既有测试的 import 一行都不用改。
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // iOS 三件套：真机（arm64）、Intel 模拟器、Apple Silicon 模拟器。
    // 每个 target 都产出一个静态 framework，Swift 侧 `import AgentCoreKit` 即可。
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "AgentCoreKit"
            isStatic = true
        }
    }

    sourceSets {
        commonTest.dependencies {
            // JVM 上按 KMP 的默认变体解析到 kotlin-test-junit（JUnit 4）；iOS 上是 kotlin-test。
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // AgentCoreSpecTest 直接用 org.junit.*（读 spec/agent-core/cases 下的真实用例文件）。
            implementation("junit:junit:4.13.2")
        }
    }
}

// KMP 之后 JVM 测试任务叫 `jvmTest`；`:agentcore:test` 仍是唯一入口（CI/文档里都这么写）。
tasks.register("test") {
    group = "verification"
    description = "Runs the JVM test suite (commonTest + jvmTest) — alias for jvmTest."
    dependsOn("jvmTest")
}

tasks.named<Test>("jvmTest") {
    testLogging {
        events("passed", "failed", "skipped")
    }
}
