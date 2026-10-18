package com.kantv.app.update

import com.kantv.app.util.Hashing
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URI
import java.util.zip.ZipFile

/**
 * 发布通道的 ABI 词汇表。
 *
 * 每个正式产物只打包**一个** ABI（见 app/build.gradle.kts 的 `-Pkantv.abi` 单 ABI 入口），
 * 所以客户端按「已装包 ABI → 设备首选 ABI」的顺序挑一个通道，永远只下载单 ABI 包，
 * 不会拿到双 ABI 巨包。旧 arm64 客户端不认识本约定，仍按 legacy `/v1/releases/latest`
 * 拿 arm64 清单，行为不变。
 */
object LanUpdateAbiPolicy {
    const val ARM64_V8A = "arm64-v8a"
    const val ARMEABI_V7A = "armeabi-v7a"

    /** 通道选择头；旧客户端不带此头，NAS 按 legacy arm64 清单处理。 */
    const val ABI_HEADER = "X-KanTV-ABI"

    /** 优先级顺序（64 位优先），保证 [select] 结果确定。 */
    val publishedAbis = listOf(ARM64_V8A, ARMEABI_V7A)

    fun isPublished(abi: String): Boolean = abi in publishedAbis

    /**
     * 选择本次更新应走的 ABI 通道。
     *
     * 先看**当前已装 APK** 内含的 ABI（单 ABI 包只会命中一个），避免更新在 32/64 位之间
     * 来回切换；读不到已装包 ABI 时才退回设备首选 ABI 列表（`Build.SUPPORTED_ABIS`）。
     * 两者都命中不了时返回 null，调用方保持 legacy 行为（不带 ABI 头、不做 ABI 校验）。
     */
    fun select(installedApkAbis: Set<String>, deviceAbis: List<String>): String? =
        publishedAbis.firstOrNull { it in installedApkAbis }
            ?: publishedAbis.firstOrNull { it in deviceAbis }
}

/**
 * 从 APK 的 `lib/<abi>/` 目录项读出实际打包的 ABI 集合。
 *
 * 纯 JVM 实现（`java.util.zip.ZipFile`），因此可在本地单测里用构造的 zip 覆盖。读取失败
 * 按 fail-closed 返回空集合，让上层的 ABI 校验直接拒绝，而不是静默放行。
 */
object ApkAbiInspector {
    private const val LIB_PREFIX = "lib/"

    fun containedAbis(apk: File): Set<String> {
        val result = linkedSetOf<String>()
        try {
            ZipFile(apk).use { archive ->
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (!name.startsWith(LIB_PREFIX)) continue
                    val segments = name.split('/')
                    if (segments.size >= 3 && segments[1].isNotEmpty()) result += segments[1]
                }
            }
        } catch (_: IOException) {
            return emptySet()
        } catch (_: SecurityException) {
            return emptySet()
        }
        return result
    }
}

data class LanReleaseManifest(
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val apkPath: String,
    val size: Long,
    val sha256: String,
    val signingCertSha256: String,
    val publishedAt: String,
)

object LanUpdateEndpointPolicy {
    private val tokenPattern = Regex("[A-Za-z0-9_-]{32,128}")
    private val apkNamePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,126}\\.apk")

    fun validToken(value: String): Boolean = tokenPattern.matches(value)

    /** Literal RFC1918 address only: no DNS, credentials, query, fragment, or alternate path. */
    fun validLatestEndpoint(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme !in setOf("http", "https") || uri.userInfo != null || uri.query != null ||
            uri.fragment != null || uri.path != "/v1/releases/latest" || uri.port == 0 || uri.port > 65535
        ) return false
        val host = uri.host.orEmpty()
        val octets = host.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 } || octets.joinToString(".") != host) return false
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    fun validApkPath(value: String): Boolean = apkNamePattern.matches(value) && ".." !in value

    fun artifactUri(latestEndpoint: String, apkPath: String): URI? {
        if (!validLatestEndpoint(latestEndpoint) || !validApkPath(apkPath)) return null
        val latest = URI(latestEndpoint)
        return URI(latest.scheme, null, latest.host, latest.port, "/v1/releases/apk/$apkPath", null, null)
    }
}

class ReleaseManifestException(message: String) : IllegalArgumentException(message)

/** Strict scalar-only parser for the small release contract; duplicate and unknown fields are rejected. */
object LanReleaseManifestParser {
    private val fields = setOf(
        "applicationId", "versionCode", "versionName", "apkPath", "size", "sha256",
        "signingCertSha256", "publishedAt",
    )
    private val appId = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val digest = Regex("[0-9a-f]{64}")
    private val published = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")

    fun parse(json: String): LanReleaseManifest {
        if (json.length > 16 * 1024) throw ReleaseManifestException("manifest_too_large")
        val values = ScalarJsonObjectParser(json).parse()
        if (values.keys != fields) throw ReleaseManifestException("manifest_fields")
        fun string(name: String): String = values[name] as? String
            ?: throw ReleaseManifestException("manifest_$name")
        fun number(name: String): Long = values[name] as? Long
            ?: throw ReleaseManifestException("manifest_$name")
        val result = LanReleaseManifest(
            applicationId = string("applicationId"),
            versionCode = number("versionCode"),
            versionName = string("versionName"),
            apkPath = string("apkPath"),
            size = number("size"),
            sha256 = string("sha256"),
            signingCertSha256 = string("signingCertSha256"),
            publishedAt = string("publishedAt"),
        )
        if (!appId.matches(result.applicationId)) throw ReleaseManifestException("manifest_application_id")
        if (result.versionCode <= 0) throw ReleaseManifestException("manifest_version_code")
        if (result.versionName.isEmpty() || result.versionName.length > 100 || result.versionName.any { it < ' ' }) {
            throw ReleaseManifestException("manifest_version_name")
        }
        if (!LanUpdateEndpointPolicy.validApkPath(result.apkPath)) throw ReleaseManifestException("manifest_apk_path")
        if (result.size <= 0 || result.size > LanUpdateClient.MAX_APK_BYTES) throw ReleaseManifestException("manifest_size")
        if (!digest.matches(result.sha256)) throw ReleaseManifestException("manifest_sha256")
        if (!digest.matches(result.signingCertSha256)) throw ReleaseManifestException("manifest_signing_cert")
        if (!published.matches(result.publishedAt)) throw ReleaseManifestException("manifest_published_at")
        return result
    }
}

private class ScalarJsonObjectParser(private val input: String) {
    private var offset = 0

    fun parse(): Map<String, Any> {
        whitespace(); expect('{'); whitespace()
        val result = linkedMapOf<String, Any>()
        if (peek('}')) { offset++; whitespace(); finish(); return result }
        while (true) {
            val key = string()
            if (result.containsKey(key)) fail("duplicate_field")
            whitespace(); expect(':'); whitespace()
            result[key] = if (peek('"')) string() else integer()
            whitespace()
            when {
                peek(',') -> { offset++; whitespace() }
                peek('}') -> { offset++; whitespace(); finish(); return result }
                else -> fail("object_separator")
            }
        }
    }

    private fun string(): String {
        expect('"')
        val output = StringBuilder()
        while (offset < input.length) {
            val value = input[offset++]
            when {
                value == '"' -> return output.toString()
                value == '\\' -> {
                    if (offset >= input.length) fail("escape")
                    when (val escaped = input[offset++]) {
                        '"', '\\', '/' -> output.append(escaped)
                        'b' -> output.append('\b')
                        'f' -> output.append('\u000c')
                        'n' -> output.append('\n')
                        'r' -> output.append('\r')
                        't' -> output.append('\t')
                        'u' -> {
                            if (offset + 4 > input.length) fail("unicode_escape")
                            val code = input.substring(offset, offset + 4).toIntOrNull(16) ?: fail("unicode_escape")
                            output.append(code.toChar()); offset += 4
                        }
                        else -> fail("escape")
                    }
                }
                value < ' ' -> fail("control_character")
                else -> output.append(value)
            }
        }
        fail("unterminated_string")
    }

    private fun integer(): Long {
        val start = offset
        if (peek('-')) offset++
        val digitStart = offset
        while (offset < input.length && input[offset] in '0'..'9') offset++
        if (digitStart == offset || (offset - digitStart > 1 && input[digitStart] == '0')) fail("integer")
        return input.substring(start, offset).toLongOrNull() ?: fail("integer")
    }

    private fun whitespace() { while (offset < input.length && input[offset] in " \t\r\n") offset++ }
    private fun expect(value: Char) { if (!peek(value)) fail("expected_$value") else offset++ }
    private fun peek(value: Char): Boolean = offset < input.length && input[offset] == value
    private fun finish() { if (offset != input.length) fail("trailing_data") }
    private fun fail(code: String): Nothing = throw ReleaseManifestException(code)
}

data class ApkIdentity(
    val applicationId: String,
    val versionCode: Long,
    val signerCertSha256: Set<String>,
)

enum class ArtifactRejection {
    NONE,
    WRONG_APPLICATION_ID,
    NOT_NEWER,
    MANIFEST_SIGNER_MISMATCH,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    ABI_MISMATCH,
    ARCHIVE_IDENTITY_MISMATCH,
}

object LanUpdateArtifactPolicy {
    fun checkOffer(
        manifest: LanReleaseManifest,
        currentApplicationId: String,
        currentVersionCode: Long,
        currentSignerCertSha256: Set<String>,
    ): ArtifactRejection = when {
        manifest.applicationId != currentApplicationId -> ArtifactRejection.WRONG_APPLICATION_ID
        manifest.versionCode <= currentVersionCode -> ArtifactRejection.NOT_NEWER
        currentSignerCertSha256 != setOf(manifest.signingCertSha256) -> ArtifactRejection.MANIFEST_SIGNER_MISMATCH
        else -> ArtifactRejection.NONE
    }

    fun verifyDownloaded(
        manifest: LanReleaseManifest,
        downloadedSize: Long,
        downloadedSha256: String,
        archiveIdentity: ApkIdentity,
        expectedAbi: String? = null,
        containedAbis: Set<String> = emptySet(),
    ): ArtifactRejection = when {
        downloadedSize != manifest.size -> ArtifactRejection.SIZE_MISMATCH
        downloadedSha256 != manifest.sha256 -> ArtifactRejection.HASH_MISMATCH
        // 只下载「清单所在通道」的单 ABI 包；expectedAbi 为 null 时保持旧行为（不校验 ABI）。
        expectedAbi != null && containedAbis != setOf(expectedAbi) -> ArtifactRejection.ABI_MISMATCH
        archiveIdentity.applicationId != manifest.applicationId ||
            archiveIdentity.versionCode != manifest.versionCode ||
            archiveIdentity.signerCertSha256 != setOf(manifest.signingCertSha256) -> ArtifactRejection.ARCHIVE_IDENTITY_MISMATCH
        else -> ArtifactRejection.NONE
    }

    fun sha256(file: File): String = FileInputStream(file).use { Hashing.sha256Hex(it) }
}
