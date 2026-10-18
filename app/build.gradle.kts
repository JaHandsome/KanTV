import java.security.MessageDigest
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Fail closed before dependency resolution, including builds that skip unit tests.
val ffmpegProvenance = Properties().apply {
    file("libs/media3-ffmpeg-provenance.properties").inputStream().use { load(it) }
}
val ffmpegAarName = requireNotNull(ffmpegProvenance.getProperty("aar.file"))
check(ffmpegAarName.matches(Regex("media3-decoder-ffmpeg-[A-Za-z0-9.-]+\\.aar"))) {
    "Invalid FFmpeg AAR filename in provenance manifest"
}
val ffmpegAar = file("libs/$ffmpegAarName")
val ffmpegExpectedSha256 = requireNotNull(ffmpegProvenance.getProperty("aar.sha256"))
check(ffmpegExpectedSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid FFmpeg AAR SHA256 in provenance manifest" }
check(ffmpegAar.isFile) { "Missing verified FFmpeg AAR: $ffmpegAarName" }
val ffmpegActualSha256 = MessageDigest.getInstance("SHA-256").digest(ffmpegAar.readBytes())
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
check(ffmpegActualSha256 == ffmpegExpectedSha256) {
    "FFmpeg AAR SHA256 differs from provenance manifest: $ffmpegAarName"
}

val pairedDiagnosticEndpoint = providers.environmentVariable("KANTV_DIAGNOSTIC_ENDPOINT").orNull.orEmpty()
val pairedDiagnosticToken = providers.environmentVariable("KANTV_DIAGNOSTIC_TOKEN").orNull.orEmpty()
val pairedUpdateEndpoint = providers.environmentVariable("KANTV_UPDATE_ENDPOINT").orNull.orEmpty()
val pairedUpdateToken = providers.environmentVariable("KANTV_UPDATE_TOKEN").orNull.orEmpty()
// 枢纽（HUB CONTRACT v1）端点与回流 token。token 只经构建期环境变量注入，绝不入库。
// 端点与 token 默认空串；仅注入端点的构建启用枢纽，通用构建不访问家庭服务。
val pairedHubEndpoint = providers.environmentVariable("KANTV_HUB_ENDPOINT").orNull.orEmpty()
val pairedHubToken = providers.environmentVariable("KANTV_HUB_FEEDBACK_TOKEN").orNull.orEmpty()
// 仅当本次确实要构建 release 时才提示（不影响 debug/diagnostic 构建）。
val releaseBuildRequested = gradle.startParameter.taskNames.any { it.contains("Release") }
val releaseKeystoreFile = providers.environmentVariable("KANTV_RELEASE_KEYSTORE_FILE").orNull.orEmpty()
val releaseKeystorePassword = providers.environmentVariable("KANTV_RELEASE_KEYSTORE_PASSWORD").orNull.orEmpty()
val releaseKeyAlias = providers.environmentVariable("KANTV_RELEASE_KEY_ALIAS").orNull.orEmpty()
val releaseKeyPassword = providers.environmentVariable("KANTV_RELEASE_KEY_PASSWORD").orNull.orEmpty()
val releaseSigningValues = listOf(releaseKeystoreFile, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
check(releaseSigningValues.none(String::isNotBlank) || releaseSigningValues.all(String::isNotBlank)) {
    "正式签名必须同时配置 KANTV_RELEASE_KEYSTORE_FILE、KANTV_RELEASE_KEYSTORE_PASSWORD、KANTV_RELEASE_KEY_ALIAS、KANTV_RELEASE_KEY_PASSWORD"
}
val releaseSigningConfigured = releaseSigningValues.all(String::isNotBlank)
val detectedGitRevision = providers.exec {
    commandLine("git", "rev-parse", "--short=12", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().takeIf(String::isNotBlank) ?: "unknown" }
val detectedGitDirty = providers.exec {
    commandLine("git", "status", "--porcelain", "--untracked-files=no")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.isNotBlank() }
val diagnosticGitRevision = providers.environmentVariable("KANTV_BUILD_GIT_REVISION")
    .orElse(detectedGitRevision.zip(detectedGitDirty) { revision, dirty ->
        if (revision == "unknown") revision else revision + if (dirty) "-dirty" else "-clean"
    }).map { value -> value.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) } ?: "unknown" }
val diagnosticBuildTimeEpochMs = providers.environmentVariable("KANTV_BUILD_EPOCH_MS")
    .map { it.toLongOrNull() ?: -1L }.getOrElse(System.currentTimeMillis())

// 极光盒子 5X 是 32 位 Android 11（API 30）设备，abiList 只有 armeabi-v7a / armeabi。
// 默认仍只打 arm64-v8a 单 ABI 包；仅当显式传入 -Pkantv.abi=armeabi-v7a 时，才把本次构建切成
// 32 位单 ABI。取值只允许下面两项，其他值在配置阶段直接失败；两个 ABI 永不进入同一个默认包，
// 避免产出通用双 ABI 巨包。
val supportedTargetAbis = listOf("arm64-v8a", "armeabi-v7a")
val targetAbiOverride = providers.gradleProperty("kantv.abi")
val targetAbi = targetAbiOverride.orNull ?: supportedTargetAbis.first()
check(targetAbi in supportedTargetAbis) {
    "Unsupported -Pkantv.abi=$targetAbi; supported values: ${supportedTargetAbis.joinToString(", ")}"
}
if (targetAbiOverride.isPresent) {
    logger.lifecycle("KanTV single-ABI override via -Pkantv.abi=$targetAbi")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.kantv.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kantv.app"
        // GeckoView 146（镜像模式的解密宿主）要求最低 API 26；两台目标真机
        // （TCL Q10M Pro / 极光盒子 5X）均为 Android 11+，2026-09-13 确认无更低设备。
        minSdk = 26
        targetSdk = 36
        ndk { abiFilters += targetAbi }
        versionCode = 54
        versionName = "0.2.41-dev"
        buildConfigField("String", "DEV_PLAYLIST_URL", "\"\"")
        // 开发包保留诊断能力；正式包在 release 中关闭，上传仍须 endpoint+token 双条件。
        buildConfigField("boolean", "KANTV_DIAGNOSTICS", "true")
        buildConfigField("String", "DIAGNOSTIC_DEFAULT_ENDPOINT", "\"\"")
        buildConfigField("String", "DIAGNOSTIC_DEFAULT_TOKEN", "\"\"")
        buildConfigField("String", "UPDATE_DEFAULT_ENDPOINT", buildConfigString(pairedUpdateEndpoint))
        buildConfigField("String", "UPDATE_DEFAULT_TOKEN", buildConfigString(pairedUpdateToken))
        buildConfigField("String", "HUB_DEFAULT_ENDPOINT", buildConfigString(pairedHubEndpoint))
        buildConfigField("String", "HUB_DEFAULT_TOKEN", buildConfigString(pairedHubToken))
        buildConfigField("int", "HUB_SCHEMA", "2")
        buildConfigField("String", "GIT_REVISION", buildConfigString(diagnosticGitRevision.get()))
        buildConfigField("long", "BUILD_TIME_EPOCH_MS", "${diagnosticBuildTimeEpochMs}L")
        buildConfigField("String", "MEDIA3_VERSION", "\"1.11.0\"")
        buildConfigField("String", "FFMPEG_EXTENSION_VERSION", "\"1.11.0\"")

    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("formalRelease") {
                storeFile = file(releaseKeystoreFile)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug { }
        release {
            // 5X 的日常播放关闭采样和音画观测包装，避免后续正式构建重新带回监测开销。
            buildConfigField("boolean", "KANTV_DIAGNOSTICS", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("formalRelease")
            buildConfigField("String", "DIAGNOSTIC_DEFAULT_ENDPOINT", buildConfigString(pairedDiagnosticEndpoint))
            buildConfigField("String", "DIAGNOSTIC_DEFAULT_TOKEN", buildConfigString(pairedDiagnosticToken))
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 枢纽回流 token 只在构建期经环境变量注入。缺失时回流通道会静默关闭
            // （Skipped("TOKEN_MISSING")），且无法在不重新构建的情况下补上——所以这里只给出
            // 一条非致命提醒，绝不失败，也不影响 debug/diagnostic 构建。
            if (releaseBuildRequested && pairedHubToken.isBlank()) {
                logger.warn(
                    "KANTV_HUB_FEEDBACK_TOKEN 未注入：本次 release 发布后枢纽回流将静默关闭（TOKEN_MISSING），" +
                        "必须重新构建才能补上——建议本次一并注入该环境变量后重建 release 产物。"
                )
            }
            if (releaseBuildRequested &&
                (pairedDiagnosticEndpoint.isBlank() || pairedDiagnosticToken.isBlank())
            ) {
                logger.warn(
                    "诊断端点或上传码未注入：本次 release 只会本地记录，不会自动上传。"
                )
            }
        }
    }



    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    // 允许把「服务端权威向量 / 真实订阅 payload」的路径透传给单测 JVM，而不必把
    // 含真实流地址的文件写入仓库（见 docs/HUB_CLIENT.md §3）：
    //   ./gradlew :app:testDebugUnitTest -Dkantv.urlkey.vectors=/tmp/server_vectors.json
    //   ./gradlew :app:testDebugUnitTest -Dkantv.hub.payload=/tmp/hub_payload.json
    listOf("kantv.urlkey.vectors", "kantv.hub.payload").forEach { key ->
        providers.systemProperty(key).orNull?.takeIf { it.isNotBlank() }?.let { value ->
            systemProperty(key, value)
        }
    }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    // GeckoView 主依赖（2026-09-13）：央视系镜像模式的解密宿主。TCL 系统 WebView 83
    // 承载官方直播页长期卡加载且不可升级（系统组件），Gecko 内核随 APK 分发。
    // 代价：arm64 APK 体积实测 +126~161MB；minSdk 相应抬到 26（见 defaultConfig 注释）。
    implementation("org.mozilla.geckoview:geckoview:146.0.20251217121356")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    // 枢纽 route `dash-clearkey` 需要 DASH 容器支持；缺此模块时 .mpd 无法被 Media3 识别。
    implementation("androidx.media3:media3-exoplayer-dash:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")
    // Pinned source, reproducible build and corresponding-source identity live in app/libs.
    implementation(files(ffmpegAar))

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // 纯 JVM 单测需要真实的 org.json；AGP 的 mockable android.jar 中 org.json 是抛异常的桩。
    // 仅作用于 test classpath，不会进入 APK。
    testImplementation("org.json:json:20240303")
}
