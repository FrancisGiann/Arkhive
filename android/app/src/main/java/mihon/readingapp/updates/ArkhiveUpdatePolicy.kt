package mihon.readingapp.updates

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class ArkhiveRelease(
    val tag: String,
    val version: ReleaseVersion,
    val title: String,
    val preview: Boolean,
    val changelog: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String?,
    val checksumUrl: String?,
)

/** Numeric semantic version ordering; numeric build suffixes are app build numbers, not previews. */
internal data class ReleaseVersion(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val prerelease: List<String> = emptyList(),
) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)
            .takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        }
        for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease[index]
            val right = other.prerelease[index]
            val leftNumber = left.toLongOrNull()
            val rightNumber = right.toLongOrNull()
            val result = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (result != 0) return result
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    fun normalized(): String = "$major.$minor.$patch" + prerelease.takeIf { it.isNotEmpty() }?.joinToString(".", prefix = "-").orEmpty()

    companion object {
        private val pattern = Regex("^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

        fun parse(value: String): ReleaseVersion? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            val parts = match.groupValues
            val prerelease = parts[4].takeIf(String::isNotEmpty)?.split('.') ?: emptyList()
            if (prerelease.any { it.isEmpty() || (it.all(Char::isDigit) && it.length > 1 && it.startsWith('0')) }) return null
            return ReleaseVersion(parts[1].toLongOrNull() ?: return null,
                parts[2].toLongOrNull() ?: return null, parts[3].toLongOrNull() ?: return null, prerelease)
        }

        /** The app uses `versionCodeSuffix` in VERSION_NAME (for example `0.4.1-1`). */
        fun parseInstalled(value: String): ReleaseVersion? = parse(value.trim().replace(Regex("-[0-9]+$"), ""))
    }
}

internal object ArkhiveUpdatePolicy {
    const val MAX_RELEASES = 30
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    private const val MAX_CHANGELOG_CHARS = 12_000
    private const val EXPECTED_OWNER = "FrancisGiann"
    private const val EXPECTED_REPOSITORY = "Arkhive"
    private const val GITHUB_HOST = "github.com"
    private const val HEX_256 = "[0-9a-f]{64}"
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun parseReleases(payload: String, installedName: String, includePreviews: Boolean): List<ArkhiveRelease> {
        require(payload.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "The GitHub release list is too large." }
        val installed = ReleaseVersion.parseInstalled(installedName)
            ?: throw IllegalArgumentException("The installed version name is not a supported numeric version.")
        val root = json.parseToJsonElement(payload) as? JsonArray
            ?: throw IllegalArgumentException("GitHub returned an invalid release list.")
        require(root.size <= MAX_RELEASES) { "GitHub returned more releases than the supported limit." }
        val releases = root.mapNotNull { parseRelease(it as? JsonObject ?: return@mapNotNull null) }
            .filter { (includePreviews || !it.preview) && it.version > installed }
            .sortedByDescending { it.version }
        return releases
    }

    private fun parseRelease(item: JsonObject): ArkhiveRelease? {
        if (item["draft"]?.jsonPrimitive?.booleanOrNull != false) return null
        val tag = item["tag_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length in 1..80 } ?: return null
        val version = ReleaseVersion.parse(tag) ?: return null
        if (!Regex("^v?${Regex.escape(version.normalized())}$").matches(tag)) return null
        val preview = (item["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false) || version.prerelease.isNotEmpty()
        val apkName = "Arkhive-${version.normalized()}.apk"
        val assets = item["assets"]?.jsonArray ?: return null
        val matching = assets.filter { asset -> asset.jsonObject["name"]?.jsonPrimitive?.contentOrNull == apkName }
        if (matching.isEmpty()) return null
        require(matching.size == 1) { "GitHub listed duplicate $apkName assets." }
        val apk = matching.single().jsonObject
        val size = apk["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: throw IllegalArgumentException("GitHub did not provide the APK size.")
        require(size in 1..MAX_APK_BYTES) { "The APK is outside the 256 MiB download limit." }
        val apkUrl = releaseAssetUrl(tag, apkName)
        require(apk["browser_download_url"]?.jsonPrimitive?.contentOrNull == apkUrl) {
            "The APK download URL does not match the official Arkhive release path."
        }
        val digest = apk["digest"]?.jsonPrimitive?.contentOrNull?.let { raw ->
            require(raw.matches(Regex("^sha256:$HEX_256$"))) { "GitHub returned an invalid SHA-256 asset digest." }
            raw.substringAfter(':')
        }
        val checksumName = "$apkName.sha256"
        val checksumAssets = assets.filter { asset -> asset.jsonObject["name"]?.jsonPrimitive?.contentOrNull == checksumName }
        require(checksumAssets.size <= 1) { "GitHub listed duplicate $checksumName assets." }
        val checksumUrl = checksumAssets.singleOrNull()?.jsonObject?.let { sidecar ->
            releaseAssetUrl(tag, checksumName).also { expected ->
                require(sidecar["browser_download_url"]?.jsonPrimitive?.contentOrNull == expected) {
                    "The checksum download URL does not match the official Arkhive release path."
                }
                require((sidecar["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: Long.MAX_VALUE) in 1..1024) {
                    "The checksum file exceeds its size limit."
                }
            }
        }
        require(digest != null || checksumUrl != null) { "This release has no GitHub SHA-256 digest or exact APK checksum asset." }
        val title = item["name"]?.jsonPrimitive?.contentOrNull?.take(160)?.ifBlank { "Arkhive ${version.normalized()}" }
            ?: "Arkhive ${version.normalized()}"
        val changelog = item["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(MAX_CHANGELOG_CHARS)
        return ArkhiveRelease(tag, version, title, preview, changelog, apkName, apkUrl, size, digest, checksumUrl)
    }

    fun parseChecksum(payload: String, exactApkName: String): String {
        require(payload.toByteArray(Charsets.UTF_8).size in 1..1024) { "The checksum file is empty or too large." }
        val match = Regex("(?i)^([0-9a-f]{64})\\s+\\*?${Regex.escape(exactApkName)}\\s*$")
            .matchEntire(payload.trim()) ?: throw IllegalArgumentException("The checksum file does not name the exact APK asset.")
        return match.groupValues[1].lowercase()
    }

    fun validateRedirect(from: String, location: String): String {
        val source = parseAllowedUrl(from)
        val resolved = source.resolve(location) ?: throw IllegalArgumentException("GitHub returned an invalid redirect.")
        val safe = parseAllowedUrl(resolved.toString())
        require(source.host != "api.github.com") { "The GitHub API endpoint must not redirect." }
        require(safe.host in setOf(GITHUB_HOST, "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
            "GitHub redirected outside its release download hosts."
        }
        return safe.toString()
    }

    fun validateInitialReleaseAsset(url: String): String = parseAllowedUrl(url, allowCdn = false).toString()
    fun validateFollowupUrl(url: String): String = parseAllowedUrl(url).toString()
    fun validateApiUrl(url: String): String {
        val parsed = parseAllowedUrl(url, allowCdn = false)
        require(parsed.host == "api.github.com" && parsed.encodedPath == "/repos/$EXPECTED_OWNER/$EXPECTED_REPOSITORY/releases" &&
            parsed.queryParameter("per_page") == MAX_RELEASES.toString()) { "The update API endpoint is not the official Arkhive releases endpoint." }
        return parsed.toString()
    }

    private fun releaseAssetUrl(tag: String, name: String): String =
        "https://$GITHUB_HOST/$EXPECTED_OWNER/$EXPECTED_REPOSITORY/releases/download/$tag/$name"

    private fun parseAllowedUrl(value: String, allowCdn: Boolean = true): HttpUrl {
        val url = value.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("The release URL is invalid.")
        require(url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
            "Update downloads must use credential-free public HTTPS URLs."
        }
        when (url.host) {
            "api.github.com" -> require(url.encodedPath.startsWith("/repos/$EXPECTED_OWNER/$EXPECTED_REPOSITORY/releases")) {
                "The update API host returned an unrelated URL."
            }
            GITHUB_HOST -> require(url.encodedPath.startsWith("/$EXPECTED_OWNER/$EXPECTED_REPOSITORY/releases/download/")) {
                "The release URL is outside the Arkhive GitHub releases path."
            }
            "release-assets.githubusercontent.com", "objects.githubusercontent.com" -> require(allowCdn) {
                "The initial update URL must use the official Arkhive GitHub release path."
            }
            else -> throw IllegalArgumentException("The update URL host is not an official GitHub release host.")
        }
        return url
    }
}

internal data class ApkIdentity(val packageName: String, val versionCode: Long, val signerSha256: Set<String>)

internal object ArkhiveApkVerification {
    fun verify(candidate: ApkIdentity, installed: ApkIdentity, expectedPackage: String, expectedSha256: String, actualSha256: String) {
        require(expectedSha256.matches(Regex("(?i)^[0-9a-f]{64}$"))) { "The expected APK digest is invalid." }
        require(actualSha256.equals(expectedSha256, ignoreCase = true)) { "The downloaded APK failed SHA-256 verification." }
        require(candidate.packageName == expectedPackage && installed.packageName == expectedPackage) {
            "The APK is not an Arkhive application package."
        }
        require(candidate.signerSha256.isNotEmpty() && candidate.signerSha256 == installed.signerSha256) {
            "The APK signing certificate does not match the installed Arkhive application."
        }
        require(candidate.versionCode > installed.versionCode) { "The verified APK is not newer than the installed application." }
    }
}
