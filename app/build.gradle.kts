import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// 签名信息：先读项目根的 keystore.properties（不进版本库），再读环境变量 YAP_KEYSTORE_*。
// 两处都没配的时候，release 就老老实实产出一个未签名的 APK，而不是让 `assembleRelease` 直接失败：
// 打包失败会挡住所有验证（比如想确认 R8 有没有把序列化器削掉），而这个失败跟「内容对不对」无关。
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(propertyName: String, envName: String): String? =
    (keystoreProperties.getProperty(propertyName) ?: System.getenv(envName))
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

val releaseStoreFile = signingValue("storeFile", "YAP_KEYSTORE_FILE")?.let { rootProject.file(it) }
val releaseStorePassword = signingValue("storePassword", "YAP_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "YAP_KEYSTORE_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "YAP_KEYSTORE_KEY_PASSWORD")

/**
 * 服务端权威模式（P1a 传输层）的编译期默认地址 / 令牌。
 *
 * 只从 Gradle property 透传（`-PYAP_SERVER_URL=https://…`），默认空 = 关闭。
 * 在 App 里还会被同名**系统属性**覆盖（`com.yap.app.data.remote.ServerConfig`），
 * 方便 JVM 测试与真机调试；release 构建在 `buildTypes.release` 里强制覆盖成空，
 * 免得 CI 误把地址 / 令牌打进发布包（与 AGENT_CORE_* 的处理一致）。
 *
 * 做一次 Java 字符串转义：URL / 令牌里万一有 `"` 或 `\`，别让生成的 BuildConfig 编译不过。
 */
fun serverBuildConfigValue(propertyName: String): String {
    val raw = (findProperty(propertyName) as String?)?.trim().orEmpty()
    return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/** 四项齐了并且 keystore 文件真的在，才算「配好了 release 签名」。 */
val hasReleaseSigning = releaseStoreFile?.exists() == true &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "com.yap.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.yap.app"
        minSdk = 26
        targetSdk = 35
        // 内测版本号：0.9.0 → 0.10.0 → 0.10.2 → 0.11.0，本次 0.11.1（审计修复：备份引用完整性 /
        // 定位窗口 / 事务外删文件 / 主线程 IO / 深链校验 / i18n 守卫）。
        // versionCode 只增不减，升级路径才不会断。
        versionCode = 19
        versionName = "0.12.1"
        vectorDrawables { useSupportLibrary = true }
        // 0.12：仪器化 UI 测试要在真实 DI 图上跑（@HiltAndroidTest 只认 Hilt 的测试
        // Application），所以换成 Hilt 官方规定的 runner；它其余行为与
        // AndroidJUnitRunner 完全一致（迁移/块维护那 28 条照旧）。
        testInstrumentationRunner = "com.yap.app.HiltTestRunner"

        // agent 决策层走哪条路：默认进程内（InProcessAgentCoreClient），行为与 0.10.x 完全一致。
        //
        // 打开远程模式（`-PagentCoreRemote=true`）后，睡眠判定 / defer_reply 计划 / 主动上限 /
        // 工具参数夹取会先请求 `agentd` 的 `/v1/core/*`；请求失败（超时、5xx、坏 JSON）自动回退到
        // 进程内实现并记一行日志——**决策层不能因为服务不可用就让聊天挂掉**。
        //
        // 这是给开发/联调用构建开关，不是用户可见设置项：release 包默认仍走进程内。
        // 地址默认指向模拟器里的宿主机（10.0.2.2）；真机联调可 `-PagentCoreBaseUrl=http://<局域网 IP>:8788`。
        buildConfigField(
            "boolean",
            "AGENT_CORE_REMOTE",
            (findProperty("agentCoreRemote") as String?)?.toBoolean()?.toString() ?: "false"
        )
        buildConfigField(
            "String",
            "AGENT_CORE_BASE_URL",
            "\"" + ((findProperty("agentCoreBaseUrl") as String?) ?: "http://10.0.2.2:8788") + "\""
        )

        // 服务端权威模式（P1a 传输层）的连接默认值：默认空 = 关闭（App 仍走本地 Room 路径）。
        // 打开方式：`-PYAP_SERVER_URL=https://server.example:8788 -PYAP_SERVER_TOKEN=…`
        // （也可以只配 token，然后在 App 里用系统属性同名覆盖地址）。见上面的 serverBuildConfigValue。
        buildConfigField("String", "YAP_SERVER_URL", serverBuildConfigValue("YAP_SERVER_URL"))
        buildConfigField("String", "YAP_SERVER_TOKEN", serverBuildConfigValue("YAP_SERVER_TOKEN"))
    }

    signingConfigs {
        val store = releaseStoreFile
        if (hasReleaseSigning && store != null) {
            create("release") {
                storeFile = store
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 以前这里用 debug 的签名配置兜底，可那签出来的包永远升不了级（签名不一致），
            // 等于把升级失败留给用户自己的手机。现在：配了就签，没配就不签，绝不拿 debug key 冒充。
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            // 审计 P2-14（0.11.1）：**release 强制走进程内**。
            // 这个开关是开发/联调用的，不是用户可见设置项；以前只靠「不传 -PagentCoreRemote」
            // 保证，CI 误传就会把 release 打成远程客户端：
            // - base URL 是 http → release 的 usesCleartextTraffic=false 让每次决策先被系统拒绝、
            //   再静默回退（永远连不上，却只留一行 logcat）；
            // - base URL 是 https → 真的把决策请求（含模型文本）发到编译期写死的主机。
            // defaultConfig 里那份值仍按 Gradle property 生成（debug 可用），release 这里覆盖成 false。
            buildConfigField("boolean", "AGENT_CORE_REMOTE", "false")
            buildConfigField("String", "AGENT_CORE_BASE_URL", "\"\"")

            // 同上：服务端地址 / 令牌是联调用的，不许被打进发布包（release 只允许用户在
            // App 内显式配置，且 P1a 阶段还没有设置界面）。debug 构建仍按 Gradle property 生成。
            buildConfigField("String", "YAP_SERVER_URL", "\"\"")
            buildConfigField("String", "YAP_SERVER_TOKEN", "\"\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
    sourceSets["main"].java.srcDirs("src/main/java")
    // 把导出的 schema 当成测试资产，以后用 MigrationTestHelper 时可以直接读历史版本。
    sourceSets["androidTest"].assets.srcDirs("$projectDir/schemas")
}

/**
 * 可选的端到端联调：`-PagentCoreE2eUrl=http://127.0.0.1:8788`（或同名环境变量 `AGENTCORE_E2E_URL`）
 * 时，`AgentCoreRemoteE2ETest` 会真的打到运行中的 `agentd`。
 *
 * 默认不设置 → 那条测试自行跳过（CI 不依赖外部进程）；设置后它要求 **4 个决策全部走远程成功**，
 * 一旦回退进程内就直接失败——这比「结果相等」更能证明客户端/服务端真的接上了。
 */
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val e2eUrl = providers.gradleProperty("agentCoreE2eUrl").orNull
        ?: System.getenv("AGENTCORE_E2E_URL")
    if (!e2eUrl.isNullOrBlank()) {
        environment("AGENTCORE_E2E_URL", e2eUrl)
    }
}

ksp {
    // Room 把每一版的表结构导出到 app/schemas/<库名>/<版本号>.json。
    // 没有这份历史，就没法证明「升级不会把用户的记忆清空」；有了它，迁移测试和 review 才有依据。
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // 两端一致的 agent 决策（纯 Kotlin/JVM、零第三方依赖）：睡眠判定、defer_reply、主动上限、工具参数夹取。
    implementation(project(":agentcore"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    // 迁移测试跑在真机/模拟器上（androidTest），这四件是标配。
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")

    // 0.12 Compose UI 行为测试（首屏一块 / 上下一块 / 定位高亮 / 发送失败 / 清空重开）：
    // - ui-test-junit4：语义树断言 + 滚动/输入注入；版本由上面的 Compose BOM 决定。
    // - hilt-android-testing + 测试源集里的 hilt 编译器：@HiltAndroidTest 注入**真实**仓库图
    //   （真 Room、真 ChatRepository），不引入假客户端，也不给生产代码开口子。
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.52")
    kspAndroidTest("com.google.dagger:hilt-compiler:2.52")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
