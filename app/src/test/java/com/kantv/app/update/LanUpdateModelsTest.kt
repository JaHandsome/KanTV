package com.kantv.app.update

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LanUpdateModelsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun apkWithAbis(vararg abis: String): File {
        val file = temporary.newFile("fixture-${abis.joinToString("-").ifEmpty { "none" }}.apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            abis.forEach { abi ->
                zip.putNextEntry(ZipEntry("lib/$abi/libkantv.so"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(byteArrayOf(0))
            zip.closeEntry()
        }
        return file
    }

    private val validJson = """
        {
          "applicationId":"com.kantv.app.diagnostic",
          "versionCode":8,
          "versionName":"0.1.7-diagnostic",
          "apkPath":"kantv-diagnostic.apk",
          "size":12,
          "sha256":"${"ab".repeat(32)}",
          "signingCertSha256":"${"cd".repeat(32)}",
          "publishedAt":"2026-09-10T12:00:00Z"
        }
    """.trimIndent()

    @Test fun manifestParserAcceptsExactContractAndRejectsUnknownDuplicateOrUnsafePath() {
        val parsed = LanReleaseManifestParser.parse(validJson)
        assertEquals("com.kantv.app.diagnostic", parsed.applicationId)
        assertEquals(8L, parsed.versionCode)
        assertEquals("kantv-diagnostic.apk", parsed.apkPath)

        listOf(
            validJson.replaceFirst("{", "{\"extra\":1,"),
            validJson.replaceFirst("\"versionCode\":8", "\"versionCode\":8,\"versionCode\":9"),
            validJson.replace("kantv-diagnostic.apk", "../kantv.apk"),
            validJson.replace("\"size\":12", "\"size\":0"),
            validJson.replace("ab".repeat(32), "AB".repeat(32)),
        ).forEach { invalid ->
            assertTrue(runCatching { LanReleaseManifestParser.parse(invalid) }.isFailure)
        }
    }

    @Test fun onlyLiteralRfc1918LatestEndpointsAndBoundedArtifactNamesAreAccepted() {
        listOf(
            "http://10.0.0.2/v1/releases/latest",
            "https://172.16.0.1:8443/v1/releases/latest",
            "http://172.31.255.254/v1/releases/latest",
            "http://192.168.1.10:8091/v1/releases/latest",
        ).forEach { assertTrue(it, LanUpdateEndpointPolicy.validLatestEndpoint(it)) }
        listOf(
            "http://127.0.0.1/v1/releases/latest",
            "http://169.254.1.1/v1/releases/latest",
            "http://172.32.0.1/v1/releases/latest",
            "http://192.168.003.227/v1/releases/latest",
            "http://192.168.1.10@evil.test/v1/releases/latest",
            "http://192.168.1.10/v1/releases/latest?token=x",
            "http://192.168.1.10/v1/releases/latest#x",
            "http://192.168.1.10/v1/releases/apk/app.apk",
            "file:///v1/releases/latest",
        ).forEach { assertFalse(it, LanUpdateEndpointPolicy.validLatestEndpoint(it)) }
        assertEquals(
            "http://192.168.1.10:8091/v1/releases/apk/kantv.apk",
            LanUpdateEndpointPolicy.artifactUri(
                "http://192.168.1.10:8091/v1/releases/latest", "kantv.apk",
            ).toString(),
        )
        assertEquals(null, LanUpdateEndpointPolicy.artifactUri("http://192.168.1.10/v1/releases/latest", "../kantv.apk"))
    }

    @Test fun installedIdentityIsTrustRootAndOnlyHigherMatchingArtifactPasses() {
        val manifest = LanReleaseManifestParser.parse(validJson)
        val currentSigner = setOf(manifest.signingCertSha256)
        assertEquals(
            ArtifactRejection.NONE,
            LanUpdateArtifactPolicy.checkOffer(manifest, manifest.applicationId, 7, currentSigner),
        )
        assertEquals(
            ArtifactRejection.NOT_NEWER,
            LanUpdateArtifactPolicy.checkOffer(manifest, manifest.applicationId, 8, currentSigner),
        )
        assertEquals(
            ArtifactRejection.WRONG_APPLICATION_ID,
            LanUpdateArtifactPolicy.checkOffer(manifest, "com.kantv.app", 7, currentSigner),
        )
        assertEquals(
            ArtifactRejection.MANIFEST_SIGNER_MISMATCH,
            LanUpdateArtifactPolicy.checkOffer(manifest, manifest.applicationId, 7, setOf("ef".repeat(32))),
        )
        assertEquals(
            ArtifactRejection.ARCHIVE_IDENTITY_MISMATCH,
            LanUpdateArtifactPolicy.verifyDownloaded(
                manifest, manifest.size, manifest.sha256,
                ApkIdentity(manifest.applicationId, manifest.versionCode, setOf("ef".repeat(32))),
            ),
        )
    }

    @Test fun downloadedBytesMustMatchManifestSizeHashPackageVersionAndSigner() {
        val file = temporary.newFile("fixture.apk")
        file.writeBytes("fixture-apk".toByteArray())
        val hash = LanUpdateArtifactPolicy.sha256(file)
        val manifest = LanReleaseManifest(
            "com.kantv.app.diagnostic", 8, "0.1.7", "fixture.apk", file.length(), hash,
            "cd".repeat(32), "2026-09-10T12:00:00Z",
        )
        val archive = ApkIdentity(manifest.applicationId, manifest.versionCode, setOf(manifest.signingCertSha256))
        assertEquals(ArtifactRejection.NONE, LanUpdateArtifactPolicy.verifyDownloaded(manifest, file.length(), hash, archive))
        assertEquals(ArtifactRejection.SIZE_MISMATCH, LanUpdateArtifactPolicy.verifyDownloaded(manifest, file.length() + 1, hash, archive))
        assertEquals(ArtifactRejection.HASH_MISMATCH, LanUpdateArtifactPolicy.verifyDownloaded(manifest, file.length(), "00".repeat(32), archive))
        assertEquals(
            ArtifactRejection.ARCHIVE_IDENTITY_MISMATCH,
            LanUpdateArtifactPolicy.verifyDownloaded(manifest, file.length(), hash, archive.copy(versionCode = 7)),
        )
    }

    @Test fun legacyManifestRejectsAbiFieldSoOldArm64ClientsKeepWorking() {
        // 旧 arm64 客户端要求恰好 8 个字段；legacy /v1/releases/latest 绝不能新增 abi 字段。
        assertTrue(runCatching { LanReleaseManifestParser.parse(validJson) }.isSuccess)
        val withAbi = validJson.replaceFirst("{", "{\"abi\":\"armeabi-v7a\",")
        assertTrue(runCatching { LanReleaseManifestParser.parse(withAbi) }.isFailure)
    }

    @Test fun apkAbiInspectorReadsNativeLibLayoutAndFailsClosedOnGarbage() {
        assertEquals(setOf("armeabi-v7a"), ApkAbiInspector.containedAbis(apkWithAbis("armeabi-v7a")))
        assertEquals(setOf("arm64-v8a"), ApkAbiInspector.containedAbis(apkWithAbis("arm64-v8a")))
        // 双 ABI 巨包会被看出两个条目，上层据此拒绝。
        assertEquals(
            setOf("arm64-v8a", "armeabi-v7a"),
            ApkAbiInspector.containedAbis(apkWithAbis("arm64-v8a", "armeabi-v7a")),
        )
        assertEquals(emptySet<String>(), ApkAbiInspector.containedAbis(temporary.newFile("garbage.apk")))
    }

    @Test fun updateChannelPrefersInstalledApkAbiThenDeviceAbiOrder() {
        assertEquals("arm64-v8a", LanUpdateAbiPolicy.select(setOf("arm64-v8a"), listOf("armeabi-v7a")))
        assertEquals("armeabi-v7a", LanUpdateAbiPolicy.select(setOf("armeabi-v7a"), listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("arm64-v8a", LanUpdateAbiPolicy.select(emptySet(), listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("armeabi-v7a", LanUpdateAbiPolicy.select(emptySet(), listOf("armeabi-v7a", "armeabi")))
        // 已装包 ABI 优先，避免更新在 32/64 位之间来回切换。
        assertEquals("armeabi-v7a", LanUpdateAbiPolicy.select(setOf("armeabi-v7a"), listOf("arm64-v8a")))
        assertEquals(null, LanUpdateAbiPolicy.select(emptySet(), listOf("x86_64")))
        assertTrue(LanUpdateAbiPolicy.isPublished("arm64-v8a"))
        assertTrue(LanUpdateAbiPolicy.isPublished("armeabi-v7a"))
        assertFalse(LanUpdateAbiPolicy.isPublished("x86"))
    }

    @Test fun downloadedArtifactMustMatchRequestedAbiAndRejectDualAbiFatPackage() {
        val file = temporary.newFile("abi-fixture.apk")
        file.writeBytes("abi-fixture".toByteArray())
        val hash = LanUpdateArtifactPolicy.sha256(file)
        val manifest = LanReleaseManifest(
            "com.kantv.app", 24, "0.2.11", "abi-fixture.apk", file.length(), hash,
            "cd".repeat(32), "2026-09-13T12:00:00Z",
        )
        val archive = ApkIdentity(manifest.applicationId, manifest.versionCode, setOf(manifest.signingCertSha256))
        assertEquals(
            ArtifactRejection.NONE,
            LanUpdateArtifactPolicy.verifyDownloaded(
                manifest, file.length(), hash, archive, "armeabi-v7a", setOf("armeabi-v7a"),
            ),
        )
        assertEquals(
            ArtifactRejection.ABI_MISMATCH,
            LanUpdateArtifactPolicy.verifyDownloaded(
                manifest, file.length(), hash, archive, "armeabi-v7a", setOf("arm64-v8a"),
            ),
        )
        assertEquals(
            ArtifactRejection.ABI_MISMATCH,
            LanUpdateArtifactPolicy.verifyDownloaded(
                manifest, file.length(), hash, archive, "armeabi-v7a", setOf("arm64-v8a", "armeabi-v7a"),
            ),
        )
        // expectedAbi 为 null 时保持旧行为，不因缺 lib/ 而失败。
        assertEquals(
            ArtifactRejection.NONE,
            LanUpdateArtifactPolicy.verifyDownloaded(manifest, file.length(), hash, archive),
        )
    }
}
