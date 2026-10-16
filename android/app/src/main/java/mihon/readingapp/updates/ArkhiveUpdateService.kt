package mihon.readingapp.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.storage.getUriCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

internal data class PreparedArkhiveUpdate(
    val release: ArkhiveRelease,
    val file: File,
    val sha256: String,
    val versionCode: Long,
)

internal interface ArkhiveUpdateTransport {
    suspend fun get(url: String, maxBytes: Int): ByteArray
    suspend fun download(url: String, destination: File, maxBytes: Long, progress: (Long, Long) -> Unit): Long
}

/** GitHub-only, cookie-free HTTP transport. Redirects are checked one hop at a time. */
internal class GitHubUpdateTransport(network: NetworkHelper) : ArkhiveUpdateTransport {
    private val client = OkHttpClient.Builder()
        .dns(network.client.dns)
        .proxy(Proxy.NO_PROXY)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .authenticator(okhttp3.Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    private sealed interface Hop<out T> {
        data class Redirect(val url: String) : Hop<Nothing>
        data class Complete<T>(val value: T) : Hop<T>
    }

    override suspend fun get(url: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        require(maxBytes in 1..(2 * 1024 * 1024))
        var next = url
        for (hop in 0 until 4) {
            currentCoroutineContext().ensureActive()
            val safe = when {
                hop == 0 && url.contains("api.github.com") -> ArkhiveUpdatePolicy.validateApiUrl(next)
                hop == 0 -> ArkhiveUpdatePolicy.validateInitialReleaseAsset(next)
                else -> ArkhiveUpdatePolicy.validateFollowupUrl(next)
            }
            when (val result = withResponse(safe) { response ->
                redirected(response, safe)?.let { return@withResponse Hop.Redirect(it) }
                require(response.isSuccessful) { "GitHub returned HTTP ${response.code}." }
                Hop.Complete(response.readBounded(maxBytes))
            }) {
                is Hop.Redirect -> next = result.url
                is Hop.Complete -> return@withContext result.value
            }
        }
        throw IOException("GitHub redirected too many times.")
    }

    override suspend fun download(url: String, destination: File, maxBytes: Long, progress: (Long, Long) -> Unit): Long =
        withContext(Dispatchers.IO) {
            require(maxBytes in 1..ArkhiveUpdatePolicy.MAX_APK_BYTES)
            var next = ArkhiveUpdatePolicy.validateInitialReleaseAsset(url)
            for (hop in 0 until 4) {
                currentCoroutineContext().ensureActive()
                val safe = if (hop == 0) next else ArkhiveUpdatePolicy.validateFollowupUrl(next)
                val result = withResponse(safe) { response ->
                    redirected(response, safe)?.let { return@withResponse Hop.Redirect(it) }
                    require(response.isSuccessful) { "GitHub returned HTTP ${response.code}." }
                    val body = response.body ?: throw IOException("GitHub returned an empty APK response.")
                    val declared = body.contentLength()
                    require(declared in 1..maxBytes) { "The APK download size is missing or exceeds its limit." }
                    destination.parentFile?.mkdirs()
                    val total = FileOutputStream(destination, false).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var downloaded = 0L
                            var lastProgress = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                downloaded += count
                                require(downloaded <= maxBytes) { "The APK download exceeds its size limit." }
                                output.write(buffer, 0, count)
                                if (downloaded - lastProgress >= 512 * 1024 || downloaded == declared) {
                                    progress(downloaded, declared)
                                    lastProgress = downloaded
                                }
                            }
                            output.flush()
                            require(downloaded == declared) { "The APK download ended before GitHub's declared size." }
                            downloaded
                        }
                    }
                    Hop.Complete(total)
                }
                when (result) {
                    is Hop.Redirect -> next = result.url
                    is Hop.Complete -> return@withContext result.value
                }
            }
            throw IOException("GitHub redirected too many times.")
        }

    /** The cancellation watcher stays alive while the caller consumes the body, not just headers. */
    private suspend fun <T> withResponse(url: String, consume: suspend (Response) -> T): T {
        val safe = url.toHttpUrl()
        val request = Request.Builder().url(safe)
            .header("Accept", if (safe.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
            .header("User-Agent", "Arkhive-Android-Update-Checker")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get().build()
        val call = client.newCall(request)
        val operation = currentCoroutineContext()
        val cancellationWatcher = CoroutineScope(operation).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        return try {
            call.await().use { response -> consume(response) }
        } finally {
            withContext(NonCancellable) { cancellationWatcher.cancelAndJoin() }
        }
    }

    private fun redirected(response: Response, from: String): String? {
        if (response.code !in REDIRECT_CODES) return null
        val location = response.header("Location") ?: throw IOException("GitHub returned a redirect without a destination.")
        return ArkhiveUpdatePolicy.validateRedirect(from, location)
    }

    private suspend fun Response.readBounded(maxBytes: Int): ByteArray {
        val body = body ?: throw IOException("GitHub returned an empty response.")
        val declared = body.contentLength()
        require(declared == -1L || declared in 0..maxBytes.toLong()) { "The GitHub response exceeds its size limit." }
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        body.byteStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxBytes) { "The GitHub response exceeds its size limit." }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/** Manual Arkhive updater, deliberately independent of Mihon's extension/app updater. */
internal class ArkhiveUpdateService(
    private val context: Context,
    private val transport: ArkhiveUpdateTransport,
) {
    constructor(context: Context, network: NetworkHelper) : this(context.applicationContext, GitHubUpdateTransport(network))

    fun installedPackage(): ApkIdentity = identity(requireNotNull(context.packageManager.getPackageInfoCompat(context.packageName)))

    fun installedVersionName(): String = context.packageManager.getPackageInfoCompat(context.packageName)?.versionName
        ?: throw IllegalStateException("The installed Arkhive version could not be read.")

    suspend fun check(includePreviews: Boolean): List<ArkhiveRelease> = withTimeout(45_000) {
        val url = "https://api.github.com/repos/FrancisGiann/Arkhive/releases?per_page=${ArkhiveUpdatePolicy.MAX_RELEASES}"
        val response = transport.get(ArkhiveUpdatePolicy.validateApiUrl(url), 2 * 1024 * 1024)
        installedPackage()
        ArkhiveUpdatePolicy.parseReleases(String(response, Charsets.UTF_8), installedVersionName(), includePreviews)
    }

    suspend fun prepare(release: ArkhiveRelease, onProgress: (Long, Long) -> Unit): PreparedArkhiveUpdate {
        val readyFile = AtomicReference<File?>(null)
        var keepReady = false
        try {
            val result = withContext(Dispatchers.IO) {
                val current = installedPackage()
                val expectedHash = release.sha256 ?: release.checksumUrl?.let { sidecar ->
                    ArkhiveUpdatePolicy.parseChecksum(String(transport.get(sidecar, 1024), Charsets.UTF_8), release.apkName)
                } ?: error("This release has no trusted SHA-256 checksum.")
                require(expectedHash.matches(Regex("(?i)^[0-9a-f]{64}$"))) { "The expected APK digest is invalid." }
                val directory = File(context.cacheDir, "arkhive-update").apply { mkdirs() }
                val part = File(directory, "${UUID.randomUUID()}.apk.part")
                val ready = File(directory, "${UUID.randomUUID()}.apk")
                try {
                    val downloaded = transport.download(release.apkUrl, part, minOf(release.apkSize, ArkhiveUpdatePolicy.MAX_APK_BYTES), onProgress)
                    currentCoroutineContext().ensureActive()
                    require(downloaded == release.apkSize && part.length() == release.apkSize) {
                        "The downloaded APK size does not match GitHub's asset metadata."
                    }
                    val actualHash = part.sha256()
                    val archive = archiveInfo(part) ?: throw IllegalArgumentException("The downloaded file is not a readable Android APK.")
                    val candidate = identity(archive)
                    ArkhiveApkVerification.verify(candidate, current, context.packageName, expectedHash, actualHash)
                    require(ReleaseVersion.parse(release.version.normalized()) == release.version) { "The release version is invalid." }
                    require(part.renameTo(ready)) { "The verified APK could not be prepared for installation." }
                    readyFile.set(ready)
                    PreparedArkhiveUpdate(release, ready, actualHash, candidate.versionCode)
                } finally {
                    part.delete()
                }
            }
            currentCoroutineContext().ensureActive()
            keepReady = true
            return result
        } finally {
            if (!keepReady) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                readyFile.getAndSet(null)?.delete()
            }
        }
    }

    fun verifyBeforeInstall(prepared: PreparedArkhiveUpdate) {
        require(prepared.file.isFile && prepared.file.length() in 1..ArkhiveUpdatePolicy.MAX_APK_BYTES) {
            "The verified update file is missing or too large. Download it again."
        }
        val actualHash = prepared.file.sha256()
        val current = installedPackage()
        val archive = archiveInfo(prepared.file) ?: throw IllegalArgumentException("The prepared file is no longer a valid APK.")
        ArkhiveApkVerification.verify(identity(archive), current, context.packageName, prepared.sha256, actualHash)
        require(PackageInfoCompat.getLongVersionCode(archive) == prepared.versionCode) {
            "The APK changed after verification. Download it again."
        }
    }

    fun canInstallUnknownSources(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(): Intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun installerIntent(prepared: PreparedArkhiveUpdate): Intent = withContext(Dispatchers.IO) {
        verifyBeforeInstall(prepared)
        require(canInstallUnknownSources()) { "Allow Arkhive to install this verified update in Android settings, then try again." }
        val uri = prepared.file.getUriCompat(context)
        Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
    }

    private fun archiveInfo(file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        return context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
    }

    private fun identity(info: PackageInfo): ApkIdentity {
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
        val signerHashes = signers.orEmpty().map { it.toByteArray().sha256() }.toSet()
        return ApkIdentity(info.packageName.orEmpty(), PackageInfoCompat.getLongVersionCode(info), signerHashes)
    }

    private fun PackageManager.getPackageInfoCompat(packageName: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(packageFlags().toLong()))
        else {
            @Suppress("DEPRECATION") getPackageInfo(packageName, packageFlags())
        }
    } catch (_: PackageManager.NameNotFoundException) { null }

    private fun packageFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else {
        @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
    }

    private fun File.sha256(): String = inputStream().buffered().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().toHex()
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
