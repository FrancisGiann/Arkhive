package mihon.readingapp.updates

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArkhiveUpdatePolicyTest {
    private val digest = "a".repeat(64)

    @Test
    fun `release selection handles build suffix preview preference and numeric ordering`() {
        val releases = ArkhiveUpdatePolicy.parseReleases(
            """
            [
              ${release("0.4.3", false)},
              ${release("v0.4.2-rc.2", true)},
              ${release("0.4.2-rc.10", true, prereleaseFlag = false)},
              ${release("0.4.1", false)},
              ${release("0.4.0", false)}
            ]
            """.trimIndent(),
            installedName = "0.4.1-8",
            includePreviews = true,
        )
        assertEquals(listOf("0.4.3", "0.4.2-rc.10", "0.4.2-rc.2"), releases.map { it.version.normalized() })
        assertEquals(listOf("0.4.3"), ArkhiveUpdatePolicy.parseReleases(
            "[${release("0.4.3", false)},${release("0.4.2-rc.10", true)}]", "0.4.1-8", includePreviews = false,
        ).map { it.version.normalized() })
        assertEquals(emptyList<ArkhiveRelease>(), ArkhiveUpdatePolicy.parseReleases("[${release("0.4.1", false)}]", "0.4.1-8", true))
    }

    @Test
    fun `checksum must name exact APK and release requires authenticated digest source`() {
        assertEquals(digest, ArkhiveUpdatePolicy.parseChecksum("$digest  Arkhive-0.4.2.apk\n", "Arkhive-0.4.2.apk"))
        assertThrows(IllegalArgumentException::class.java) {
            ArkhiveUpdatePolicy.parseChecksum("$digest  other.apk", "Arkhive-0.4.2.apk")
        }
        val withoutTrust = release("0.4.2", false, includeDigest = false)
        assertThrows(IllegalArgumentException::class.java) {
            ArkhiveUpdatePolicy.parseReleases("[$withoutTrust]", "0.4.1", true)
        }
    }

    @Test
    fun `metadata rejects ambiguous APK asset URLs and oversized downloads`() {
        val wrongPath = release("0.4.2", false).replace(
            "https://github.com/FrancisGiann/Arkhive/releases/download/0.4.2/Arkhive-0.4.2.apk",
            "https://evil.example/Arkhive-0.4.2.apk",
        )
        assertThrows(IllegalArgumentException::class.java) {
            ArkhiveUpdatePolicy.parseReleases("[$wrongPath]", "0.4.1", true)
        }
        val tooLarge = release("0.4.2", false, size = ArkhiveUpdatePolicy.MAX_APK_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) {
            ArkhiveUpdatePolicy.parseReleases("[$tooLarge]", "0.4.1", true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArkhiveUpdatePolicy.validateInitialReleaseAsset("http://github.com/FrancisGiann/Arkhive/releases/download/0.4.2/Arkhive-0.4.2.apk")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArkhiveUpdatePolicy.validateInitialReleaseAsset("https://user@github.com/FrancisGiann/Arkhive/releases/download/0.4.2/Arkhive-0.4.2.apk")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArkhiveUpdatePolicy.validateApiUrl("https://api.github.com/repos/someone-else/project/releases?per_page=30")
        }
    }

    @Test
    fun `only the exact release path and official release CDN hosts can be followed`() {
        val start = "https://github.com/FrancisGiann/Arkhive/releases/download/0.4.2/Arkhive-0.4.2.apk"
        assertEquals("https://release-assets.githubusercontent.com/path/to/asset?sig=fixture",
            ArkhiveUpdatePolicy.validateRedirect(start, "https://release-assets.githubusercontent.com/path/to/asset?sig=fixture"))
        assertEquals("https://objects.githubusercontent.com/path/to/asset?sig=fixture",
            ArkhiveUpdatePolicy.validateRedirect("https://release-assets.githubusercontent.com/path/to/asset?sig=fixture",
                "https://objects.githubusercontent.com/path/to/asset?sig=fixture"))
        assertThrows(IllegalArgumentException::class.java) { ArkhiveUpdatePolicy.validateRedirect(start, "https://evil.example/asset") }
        assertThrows(IllegalArgumentException::class.java) { ArkhiveUpdatePolicy.validateRedirect(start, "//evil.example/asset") }
        assertThrows(IllegalArgumentException::class.java) {
            ArkhiveUpdatePolicy.validateRedirect("https://api.github.com/repos/FrancisGiann/Arkhive/releases?per_page=30", start)
        }
    }

    @Test
    fun `package verifier requires expected hash package exact signers and strictly newer version`() {
        val installed = ApkIdentity("mihon.app", 8, setOf("signer-a"))
        val accepted = ApkIdentity("mihon.app", 9, setOf("signer-a"))
        ArkhiveApkVerification.verify(accepted, installed, "mihon.app", digest, digest)
        assertThrows(IllegalArgumentException::class.java) {
            ArkhiveApkVerification.verify(accepted, installed, "mihon.app", digest, "b".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArkhiveApkVerification.verify(accepted.copy(signerSha256 = setOf("other")), installed, "mihon.app", digest, digest)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArkhiveApkVerification.verify(accepted.copy(versionCode = 8), installed, "mihon.app", digest, digest)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArkhiveApkVerification.verify(accepted.copy(packageName = "attacker.app"), installed, "mihon.app", digest, digest)
        }
        assertFalse(ReleaseVersion.parse("0.04.2") != null)
        assertTrue(ReleaseVersion.parseInstalled("0.4.1-8") == ReleaseVersion.parse("0.4.1"))
    }

    private fun release(
        tag: String,
        prerelease: Boolean,
        size: Long = 4,
        includeDigest: Boolean = true,
        prereleaseFlag: Boolean = prerelease,
    ): String {
        val version = requireNotNull(ReleaseVersion.parse(tag))
        val name = "Arkhive-${version.normalized()}.apk"
        val digestField = if (includeDigest) "\"digest\":\"sha256:$digest\"," else ""
        return """
            {"draft":false,"tag_name":"$tag","prerelease":$prereleaseFlag,"name":"Arkhive ${version.normalized()}","body":"Fixture notes","assets":[{"name":"$name","size":$size,$digestField"browser_download_url":"https://github.com/FrancisGiann/Arkhive/releases/download/$tag/$name"}]}
        """.trimIndent()
    }
}
