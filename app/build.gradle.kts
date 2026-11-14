import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// 签名信息：先读项目根的 keystore.properties（不进版本库），再读环境变量 MYFRIEND_KEYSTORE_*。
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

val releaseStoreFile = signingValue("storeFile", "MYFRIEND_KEYSTORE_FILE")?.let { rootProject.file(it) }
val releaseStorePassword = signingValue("storePassword", "MYFRIEND_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "MYFRIEND_KEYSTORE_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "MYFRIEND_KEYSTORE_KEY_PASSWORD")

/** 四项齐了并且 keystore 文件真的在，才算「配好了 release 签名」。 */
val hasReleaseSigning = releaseStoreFile?.exists() == true &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "com.myfriend.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.myfriend.app"
        minSdk = 26
        targetSdk = 35
        // 内测版本号：1.9.0 → 0.9.0 → 0.9.1 → 0.10.0，本次 0.10.1（i18n 收尾 + 聊天分页 + 媒体清理）。
        // versionCode 只增不减，升级路径才不会断。
        versionCode = 13
        versionName = "0.10.1"
        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

ksp {
    // Room 把每一版的表结构导出到 app/schemas/<库名>/<版本号>.json。
    // 没有这份历史，就没法证明「升级不会把用户的记忆清空」；有了它，迁移测试和 review 才有依据。
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

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

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
