package com.kantv.app.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 构建与安装配置的契约：Gradle 签名、Manifest 权限/接收器、ABI 头常量。
 * 只钉构建配置与公共常量，不读 src/main 源码文本——原安装器/客户端/宿主接线的
 * 源码文本断言已随第 1 批重构移除（纯搬移 MainActivity / GeckoMirrorLiveActivity
 * 会误报红）；ABI 选择与安装包校验的行为由 LanUpdateModelsTest 覆盖。
 */
class LanUpdateBuildContractTest {
    @Test fun manifestDeclaresInstallPermissionsAndPrivateResultReceiver() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(manifest.contains("android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION"))
        assertTrue(manifest.contains(".update.LanUpdateInstallResultReceiver"))
        assertTrue(manifest.contains("android:exported=\"false\""))
    }

    @Test fun formalSignerCoversTheSingleReleasePathWithoutEmbeddingSecrets() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("KANTV_RELEASE_KEYSTORE_FILE"))
        assertTrue(gradle.contains("create(\"formalRelease\")"))
        // 2026-09-15：diagnostic / tvAcceptance 两个变体已删除（用户决定精简为
        // debug（模拟器验收）+ release（真机/发布）两个变体），因此正式签名只需覆盖 release。
        assertFalse(gradle.contains("create(\"diagnostic\")"))
        assertFalse(gradle.contains("create(\"tvAcceptance\")"))
        val release = gradle.substringAfter("release {")
        assertTrue(
            "唯一的正式签名路径必须绑定 formalRelease",
            release.contains("signingConfig = signingConfigs.getByName(\"formalRelease\")"),
        )
        assertFalse("绝不把口令写进构建脚本", gradle.contains("storePassword = \""))
        assertFalse(gradle.contains("keyPassword = \""))
    }

    @Test fun abiHeaderIsThePublishedContractConstant() {
        // 行为断言（取代原 LanUpdateModels.kt / LanUpdateClient.kt 源码文本断言）：
        // 客户端发出的请求头常量必须与已发布的服务端契约一致。
        assertEquals("X-KanTV-ABI", LanUpdateAbiPolicy.ABI_HEADER)
    }
}
