package com.kantv.app.build

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 极光盒子 5X 真机为 32 位 Android 11（API 30），abiList 只有 armeabi-v7a / armeabi。
 * 构建入口契约：默认仍是 arm64-v8a 单 ABI；只有显式 -Pkantv.abi=armeabi-v7a 才把本次构建
 * 切成 32 位单 ABI；其他取值在配置阶段失败；默认包绝不把两个 ABI 同时打进去（通用双 ABI 巨包）。
 *
 * 测试工作目录是 app/，因此这里的 build.gradle.kts 就是 app/build.gradle.kts。
 */
class AbiBuildContractTest {
    private val gradle: String = File("build.gradle.kts").readText()

    @Test
    fun defaultAbiIsArm64And32BitRequiresExplicitProperty() {
        // supportedTargetAbis 的第一项即默认值，必须仍是 arm64-v8a；第二项才是 32 位。
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), supportedTargetAbis())
        assertTrue(gradle.contains("providers.gradleProperty(\"kantv.abi\")"))
        assertTrue(gradle.contains("?: supportedTargetAbis.first()"))
    }

    @Test
    fun defaultConfigUsesValidatedSingleAbiInsteadOfHardCodedArm64() {
        val defaultConfig = gradle.substringAfter("defaultConfig {").substringBefore("signingConfigs {")
        assertTrue(defaultConfig.contains("ndk { abiFilters += targetAbi }"))
        assertFalse(defaultConfig.contains("ndk { abiFilters += \"arm64-v8a\" }"))
        // 默认配置里不得出现把两个 ABI 一起加入的写法，否则会产出通用双 ABI 巨包。
        assertFalse(defaultConfig.contains("abiFilters += listOf("))
    }

    @Test
    fun unsupportedAbiValuesAreRejectedAtConfigurationTime() {
        assertTrue(gradle.contains("check(targetAbi in supportedTargetAbis)"))
        assertTrue(gradle.contains("Unsupported -Pkantv.abi="))
    }

    private fun supportedTargetAbis(): List<String> {
        val match = Regex("val supportedTargetAbis = listOf\\(([^)]*)\\)").find(gradle)
            ?: error("build.gradle.kts 缺少 supportedTargetAbis 声明")
        return Regex("\"([^\"]+)\"").findAll(match.groupValues[1]).map { it.groupValues[1] }.toList()
    }
}
