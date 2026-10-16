package mihon.readingapp.novels.lnreader

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class LnReaderManagerSnapshot(
    val repositories: List<LnReaderRepositoryRecord>,
    val installedPlugins: List<LnReaderInstalledPlugin>,
)

class LnReaderUnapprovedCoverOriginException(val origin: String) : IllegalArgumentException(
    "This cover needs the exact origin $origin. Open Novel extensions, review the current bundle, and select this public HTTPS origin before trusting the update.",
)

/**
 * Private, explicit-review repository and plugin state. Listing metadata never evaluates code;
 * only an in-memory review token plus the exact displayed SHA-256 can install a staged bundle.
 */
class LnReaderExtensionManager(
    context: Context,
    private val broker: LnReaderHostFetch = LnReaderHttpBroker(),
    private val runtime: LnReaderSandboxRuntime,
) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "lnreader-compatible")
    private val bundleDirectory = File(root, "bundles")
    private val storageDirectory = File(root, "storage")
    private val stateFile = File(root, "manager.json")
    private val stateLock = Mutex()
    private val executionLock = Mutex()
    private val activeExecutions = linkedMapOf<String, MutableSet<Job>>()
    private val staged = linkedMapOf<String, StagedBundle>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private var state: State = State()
    private var stateLoaded = false
    private val snapshotMutable = MutableStateFlow(LnReaderManagerSnapshot(emptyList(), emptyList()))
    val snapshots: StateFlow<LnReaderManagerSnapshot> = snapshotMutable.asStateFlow()

    suspend fun snapshot(): LnReaderManagerSnapshot = withContext(Dispatchers.IO) {
        stateLock.withLock {
            ensureLoaded()
            currentSnapshot()
        }
    }

    fun currentInstalled(sourceId: String): LnReaderInstalledPlugin? =
        snapshotMutable.value.installedPlugins.firstOrNull { it.sourceId == sourceId }

    suspend fun requireEnabled(sourceId: String): LnReaderInstalledPlugin =
        snapshot().installedPlugins.singleOrNull { it.sourceId == sourceId && it.enabled }
            ?: error("This LNReader source is disabled or no longer installed.")

    suspend fun requireApprovedHttpsUrl(sourceId: String, candidate: String): String {
        val safe = parsePublicHttps(candidate)
        val installed = requireEnabled(sourceId)
        require(safe.originKey() in installed.approvedOrigins) {
            "This source URL uses an origin that has not been reviewed. Add it during a fresh extension review before opening this link."
        }
        return safe.toString()
    }

    suspend fun addRepository(url: String): LnReaderRepositoryRecord = withContext(Dispatchers.IO) {
        stateLock.withLock {
            ensureLoaded()
            val normalized = parsePublicHttps(url).toString()
            require(state.repositories.containsKey(normalized) || state.repositories.size < MAX_REPOSITORIES) {
                "The repository limit has been reached."
            }
            val record = LnReaderRepositoryRecord(normalized)
            val next = state.copy(repositories = state.repositories + (normalized to record))
            writeState(next)
            state = next
            publishSnapshot()
            record
        }
    }

    suspend fun refreshRepository(url: String): LnReaderRepositoryRecord = withContext(Dispatchers.IO) {
        val normalized = parsePublicHttps(url).toString()
        val prior = stateLock.withLock {
            ensureLoaded()
            state.repositories[normalized] ?: error("Add this repository before refreshing it.")
        }
        val origin = parsePublicHttps(normalized).originKey()
        val updated = try {
            val response = broker.fetch(
                LnReaderHttpRequest(id = "0", url = normalized, method = "GET", headers = emptyMap(), body = null),
                setOf(origin), MAX_MANIFEST_BYTES,
            )
            require(response.status in 200..299) { "Repository metadata is unavailable (HTTP${response.status})." }
            parseManifest(response.body).let { entries ->
                LnReaderRepositoryRecord(normalized, System.currentTimeMillis(), entries, null)
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            LnReaderRepositoryRecord(normalized, System.currentTimeMillis(), prior.metadata,
                failure.message?.take(240) ?: "Repository refresh failed.")
        }
        stateLock.withLock {
            ensureLoaded()
            require(normalized in state.repositories) { "This repository was removed while it refreshed." }
            val next = state.copy(repositories = state.repositories + (normalized to updated))
            writeState(next)
            state = next
            publishSnapshot()
        }
        updated
    }

    suspend fun stagePlugin(repositoryUrl: String, pluginId: String): LnReaderInstallReview = withContext(Dispatchers.IO) {
        val repo = parsePublicHttps(repositoryUrl).toString()
        val metadata = stateLock.withLock {
            ensureLoaded()
            val record = state.repositories[repo] ?: error("Refresh this repository before selecting a plugin.")
            record.metadata.singleOrNull { it.id == pluginId }
                ?: error("This plugin is no longer present in the refreshed repository.")
        }
        require(metadata.id.matches(EXTERNAL_PLUGIN_ID)) { "The plugin id is invalid." }
        require(metadata.compatibilityError == null) { metadata.compatibilityError ?: "This source is not supported." }
        require(!metadata.customJs && !metadata.customCss) { "Unsupported capability: this entry declares custom JavaScript or CSS." }
        require(!metadata.webStorageUtilized) { "Unsupported capability: this source requires browser Web Storage capture." }
        val bundleUrl = parsePublicHttps(metadata.bundleUrl)
        val response = broker.fetch(
            LnReaderHttpRequest(id = "0", url = bundleUrl.toString(), method = "GET", headers = emptyMap(), body = null),
            setOf(bundleUrl.originKey()), MAX_BUNDLE_BYTES,
        )
        require(response.status in 200..299) { "The plugin bundle is unavailable (HTTP${response.status})." }
        require(response.body.isNotEmpty()) { "The plugin bundle is empty." }
        val sourceId = stableSourceId(repo, metadata.id)
        val hash = sha256(response.body)
        val prior = stateLock.withLock {
            ensureLoaded()
            check(state.repositories[repo]?.metadata?.singleOrNull { it.id == pluginId } == metadata) {
                "Repository metadata changed while the bundle was being staged. Refresh and review again."
            }
            state.installed[sourceId]?.sha256
        }
        val origins = linkedSetOf(bundleUrl.originKey(), parsePublicHttps(metadata.site).originKey())
        metadata.iconUrl?.let { origins += parsePublicHttps(it).originKey() }
        stateLock.withLock {
            check(state.repositories[repo]?.metadata?.singleOrNull { it.id == pluginId } == metadata) {
                "Repository metadata changed while the bundle was being staged. Refresh and review again."
            }
            staged.entries.removeAll { it.value.sourceId == sourceId }
            require(staged.size < MAX_STAGED_PLUGINS && staged.values.sumOf { it.bundle.size } + response.body.size <= MAX_STAGED_BYTES) {
                "Too many plugin bundles are awaiting review. Finish or restart a review first."
            }
            val token = UUID.randomUUID().toString()
            staged[token] = StagedBundle(repo, metadata, sourceId, response.body, hash, origins, prior)
            LnReaderInstallReview(token, metadata, sourceId, hash, response.body.size, origins, prior)
        }
    }

    suspend fun installReviewed(
        reviewToken: String,
        confirmedSha256: String,
        approvedOrigins: Set<String>,
    ): LnReaderInstalledPlugin = withContext(Dispatchers.IO) {
        val (installed, jobsToStop) = stateLock.withLock {
            ensureLoaded()
            val review = staged.remove(reviewToken) ?: error("This plugin review expired. Stage the bundle again.")
            require(confirmedSha256.equals(review.sha256, ignoreCase = true)) { "The reviewed bundle hash does not match." }
            val normalizedApprovals = approvedOrigins.map { parseOrigin(it) }.toSet()
            require(review.requiredOrigins.all { it in normalizedApprovals }) {
                "Confirm every required public source origin shown in the review before installing."
            }
            require(normalizedApprovals.size <= MAX_APPROVED_ORIGINS) { "The approved origin limit has been reached." }
            require(!review.metadata.customJs && !review.metadata.customCss && !review.metadata.webStorageUtilized) {
                "This plugin requests a capability that is unsupported by the isolated runtime."
            }
            val destination = bundleFile(review.sourceId, review.sha256)
            val createdDestination = !destination.exists()
            if (!createdDestination) {
                check(destination.isFile && destination.length() == review.bundle.size.toLong() &&
                    sha256(destination.readBytes()) == review.sha256) { "An existing bundle with this hash failed its integrity check." }
            } else atomicWrite(destination, review.bundle)
            val installed = LnReaderInstalledPlugin(
                sourceId = review.sourceId,
                repositoryUrl = review.repositoryUrl,
                metadata = review.metadata,
                sha256 = review.sha256,
                approvedOrigins = normalizedApprovals,
                enabled = true,
                installedAt = System.currentTimeMillis(),
            )
            val next = state.copy(installed = state.installed + (installed.sourceId to installed))
            try { writeState(next) } catch (failure: Throwable) {
                if (createdDestination) destination.delete()
                throw failure
            }
            state = next
            publishSnapshot()
            pruneBundles(installed.sourceId, installed.sha256)
            installed to activeExecutions[installed.sourceId].orEmpty().toList()
        }
        // Changing the installed record revokes the old execution trust even when its new bundle
        // hash is identical (for example, when the approved-origin set changes).
        withContext(NonCancellable) {
            jobsToStop.forEach { it.cancel(CancellationException("LNReader source installation changed.")) }
            jobsToStop.joinAll()
        }
        installed
    }

    suspend fun discardReview(reviewToken: String): Boolean = withContext(Dispatchers.IO) {
        stateLock.withLock { staged.remove(reviewToken) != null }
    }

    suspend fun setEnabled(sourceId: String, enabled: Boolean): LnReaderInstalledPlugin = withContext(Dispatchers.IO) {
        val (updated, jobsToStop) = stateLock.withLock {
            ensureLoaded()
            val current = state.installed[sourceId] ?: error("This source is not installed.")
            val updated = current.copy(enabled = enabled)
            val next = state.copy(installed = state.installed + (sourceId to updated))
            writeState(next)
            state = next
            publishSnapshot()
            updated to if (enabled) emptyList() else activeExecutions[sourceId].orEmpty().toList()
        }
        jobsToStop.forEach { it.cancel(CancellationException("LNReader source disabled by the user.")) }
        jobsToStop.joinAll()
        updated
    }

    suspend fun uninstall(sourceId: String) = withContext(Dispatchers.IO) {
        val jobsToStop = stateLock.withLock {
            ensureLoaded()
            require(sourceId in state.installed) { "This source is not installed." }
            val next = state.copy(installed = state.installed - sourceId)
            writeState(next)
            state = next
            publishSnapshot()
            // Remove the owned executable and storage while the state lock prevents a concurrent
            // reinstall from writing a new bundle that this uninstall would then delete.
            bundleDirectory.resolve(sourceId).deleteRecursively()
            storageDirectory.resolve("$sourceId.json").delete()
            activeExecutions[sourceId].orEmpty().toList()
        }
        jobsToStop.forEach { it.cancel(CancellationException("LNReader source uninstalled by the user.")) }
        jobsToStop.joinAll()
        // NovelRepository data is deliberately stored elsewhere and remains available offline.
    }

    suspend fun execute(
        sourceId: String,
        operation: String,
        arguments: List<JsonElement>,
    ): LnReaderExecutionResult = withContext(Dispatchers.IO) { executionLock.withLock {
        val executionJob = currentCoroutineContext()[Job] ?: error("The source operation has no cancellable job.")
        try {
            val (installed, bundle, stored) = stateLock.withLock {
                ensureLoaded()
                val current = state.installed[sourceId] ?: error("This LNReader source is not installed.")
                check(current.enabled) { "This LNReader source is disabled." }
                val file = bundleFile(sourceId, current.sha256)
                check(file.isFile && file.length() in 1..MAX_BUNDLE_BYTES.toLong()) { "The installed plugin bundle is missing or invalid." }
                val bytes = file.readBytes()
                check(sha256(bytes) == current.sha256) { "The installed plugin bundle failed its integrity check." }
                val storageFile = storageDirectory.resolve("$sourceId.json")
                val storage = if (storageFile.isFile && storageFile.length() <= MAX_STORAGE_BYTES) {
                    runCatching { json.parseToJsonElement(storageFile.readText()).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                } else JsonObject(emptyMap())
                activeExecutions.getOrPut(sourceId) { linkedSetOf() }.add(executionJob)
                Triple(current, bytes, storage)
            }
            val result = runtime.execute(
                pluginId = installed.metadata.id,
                pluginName = installed.metadata.name,
                pluginSite = installed.metadata.site,
                pluginVersion = installed.metadata.version,
                bundle = bundle,
                operation = operation,
                arguments = arguments,
                approvedOrigins = installed.approvedOrigins,
                storage = stored,
                webStorageUtilized = installed.metadata.webStorageUtilized,
                authorizationCheck = {
                    stateLock.withLock {
                        ensureLoaded()
                        state.installed[sourceId]?.let { current ->
                            current.enabled && current.sha256 == installed.sha256 &&
                                current.approvedOrigins == installed.approvedOrigins &&
                                current.repositoryUrl == installed.repositoryUrl && current.metadata == installed.metadata
                        } == true
                    }
                },
            )
            require(result.storageSize() <= MAX_STORAGE_BYTES) { "Plugin storage exceeds its 64 KiB limit." }
            stateLock.withLock {
                ensureLoaded()
                val latest = state.installed[sourceId]
                check(latest?.let { current ->
                    current.enabled && current.sha256 == installed.sha256 &&
                        current.approvedOrigins == installed.approvedOrigins &&
                        current.repositoryUrl == installed.repositoryUrl && current.metadata == installed.metadata
                } == true) {
                    "This LNReader source was disabled or removed while the operation was running."
                }
                val storageFile = storageDirectory.resolve("$sourceId.json")
                atomicWrite(storageFile, JsonObject(result.storage).toString().toByteArray(Charsets.UTF_8))
            }
            result
        } finally {
            withContext(NonCancellable) {
                stateLock.withLock {
                    activeExecutions[sourceId]?.let { jobs ->
                        jobs.remove(executionJob)
                        if (jobs.isEmpty()) activeExecutions.remove(sourceId)
                    }
                }
            }
        }
    } }

    suspend fun runtimeStatus(): LnReaderRuntimeSupport = withContext(Dispatchers.IO) { runtime.supportStatus() }

    private fun currentSnapshot() = LnReaderManagerSnapshot(state.repositories.values.toList(), state.installed.values.toList())

    private fun publishSnapshot() { snapshotMutable.value = currentSnapshot() }

    /** Resolve only an existing owned image; offline library covers do not need an installed source. */
    suspend fun cachedCoverFile(sourceId: String, imageUrl: String): File? = withContext(Dispatchers.IO) {
        require(sourceId.matches(STABLE_SOURCE_ID))
        val url = parseCoverUrl(imageUrl)
        val coverDirectory = File(root, "covers")
        val cache = File(coverDirectory, "${sha256("$sourceId\u0000$url".toByteArray()).take(48)}.img")
        cache.takeIf(::validImage)?.also { it.setLastModified(System.currentTimeMillis()) }
    }

    /** Return a broker-owned, validated raster cache. Existing bytes remain usable after revocation. */
    suspend fun coverFile(
        sourceId: String,
        imageUrl: String,
        imageRequestInit: LnReaderImageRequestInit = LnReaderImageRequestInit(),
    ): File? = withContext(Dispatchers.IO) {
        require(sourceId.matches(STABLE_SOURCE_ID))
        val url = parseCoverUrl(imageUrl)
        val coverDirectory = File(root, "covers")
        val cache = File(coverDirectory, "${sha256("$sourceId\u0000$url".toByteArray()).take(48)}.img")
        if (validImage(cache)) {
            cache.setLastModified(System.currentTimeMillis())
            return@withContext cache
        }
        val origin = parsePublicHttps(url).originKey()
        val job = currentCoroutineContext()[Job] ?: error("The cover request has no cancellable job.")
        val trusted = stateLock.withLock {
            ensureLoaded()
            val installed = state.installed[sourceId]
            if (installed == null || !installed.enabled) null
            else {
                if (origin !in installed.approvedOrigins) throw LnReaderUnapprovedCoverOriginException(origin)
                installed.also { activeExecutions.getOrPut(sourceId) { linkedSetOf() }.add(job) }
            }
        } ?: return@withContext null
        try {
            val response = broker.fetch(
                LnReaderHttpRequest("cover", url, imageRequestInit.method, imageRequestInit.headers, imageRequestInit.body?.toByteArray()),
                trusted.approvedOrigins,
                MAX_COVER_BYTES,
            )
            currentCoroutineContext().ensureActive()
            if (response.status !in 200..299 || !validImage(response.body)) return@withContext null
            stateLock.withLock {
                ensureLoaded()
                val latest = state.installed[sourceId]
                check(latest?.let { it.enabled && it.sha256 == trusted.sha256 && it.approvedOrigins == trusted.approvedOrigins } == true) {
                    "This LNReader source was disabled or its trust changed while the cover was loading."
                }
                atomicWrite(cache, response.body)
                trimCoverCache(coverDirectory, cache)
            }
            cache.takeIf(::validImage)
        } finally {
            withContext(NonCancellable) {
                stateLock.withLock {
                    activeExecutions[sourceId]?.let { jobs -> jobs.remove(job); if (jobs.isEmpty()) activeExecutions.remove(sourceId) }
                }
            }
        }
    }

    private fun parseCoverUrl(imageUrl: String): String = try {
        parsePublicHttps(imageUrl).toString()
    } catch (failure: Exception) {
        throw IllegalArgumentException("This extension returned a cover URL that is not public HTTPS.", failure)
    }

    private fun validImage(file: File): Boolean = file.isFile && file.length() in 1..MAX_COVER_BYTES.toLong() &&
        runCatching { validImage(file.readBytes()) }.getOrDefault(false)

    private fun validImage(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth in 1..MAX_COVER_DIMENSION && options.outHeight in 1..MAX_COVER_DIMENSION &&
            options.outWidth.toLong() * options.outHeight <= MAX_COVER_PIXELS
    }

    private fun trimCoverCache(directory: File, newest: File) {
        var total = 0L
        directory.listFiles().orEmpty().filter(File::isFile).sortedByDescending(File::lastModified).forEach { file ->
            total += file.length()
            if (total > MAX_COVER_CACHE_BYTES && file != newest) file.delete()
        }
    }

    private fun ensureLoaded() {
        if (!stateLoaded) {
            state = readState()
            stateLoaded = true
            publishSnapshot()
        }
    }

    private fun parseManifest(bytes: ByteArray): List<LnReaderPluginMetadata> {
        require(bytes.size <= MAX_MANIFEST_BYTES) { "The repository manifest exceeds its 2 MiB limit." }
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray
        require(root.size <= MAX_MANIFEST_ENTRIES) { "The repository contains too many entries." }
        val seen = mutableSetOf<String>()
        return root.map { item ->
            val fields = item.jsonObject
            fun text(key: String, required: Boolean = true): String? {
                val value = fields[key]?.jsonPrimitive?.contentOrNull
                if (required) require(!value.isNullOrBlank()) { "Repository entry is missing $key." }
                return value
            }
            val id = requireNotNull(text("id"))
            require(id.matches(EXTERNAL_PLUGIN_ID) && seen.add(id)) { "The repository contains an invalid or duplicate plugin id." }
            val name = requireNotNull(text("name"))
            val siteInput = requireNotNull(text("site"))
            val version = requireNotNull(text("version"))
            val language = text("lang")?.take(32).orEmpty()
            val bundleInput = requireNotNull(text("url"))
            val iconInput = text("iconUrl", required = false)?.takeIf { it.isNotBlank() }
            val customJs = fields["customJS"].isEnabledCapability()
            val customCss = fields["customCSS"].isEnabledCapability()
            val webStorage = fields["webStorageUtilized"].isEnabledCapability()
            require(name.length <= 160 && version.length <= 80 && siteInput.length <= 4096 && bundleInput.length <= 4096) {
                "A repository entry has a field that exceeds its limit."
            }
            val parsedSite = runCatching { parsePublicHttps(siteInput).toString() }.getOrNull()
            val parsedBundle = runCatching { parsePublicHttps(bundleInput).toString() }.getOrNull()
            val parsedIcon = iconInput?.let { runCatching { parsePublicHttps(it).toString() }.getOrNull() }
            val compatibilityError = when {
                parsedSite == null -> "Unsupported source site: public HTTPS is required. This entry cannot be executed."
                parsedBundle == null -> "Unsupported bundle URL: public HTTPS is required. This entry cannot be staged."
                iconInput != null && parsedIcon == null -> "Unsupported icon URL: public HTTPS is required. This entry cannot be staged."
                customJs -> "Unsupported capability: custom JavaScript extensions are not executed."
                customCss -> "Unsupported capability: custom CSS extensions are not applied."
                webStorage -> "Unsupported capability: browser Web Storage capture is unavailable."
                else -> null
            }
            // Keep the source's published strings unchanged for LNReader's metadata equality
            // checks. Parsing above is only the host security validation step.
            LnReaderPluginMetadata(id, name, siteInput, language, version,
                bundleInput, iconInput, customJs, customCss, webStorage, compatibilityError)
        }
    }

    private fun JsonElement?.isEnabledCapability(): Boolean = when (this) {
        is JsonPrimitive -> booleanOrNull ?: (contentOrNull?.isNotBlank() == true && contentOrNull != "false")
        null -> false
        else -> true
    }

    private fun readState(): State {
        if (!stateFile.isFile || stateFile.length() > MAX_STATE_BYTES) return State()
        return runCatching { decodeState(json.parseToJsonElement(stateFile.readText()).jsonObject) }.getOrDefault(State())
    }

    private fun writeState(next: State) {
        root.mkdirs()
        val element = encodeState(next)
        val bytes = element.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STATE_BYTES) { "LNReader manager state exceeds its storage limit." }
        atomicWrite(stateFile, bytes)
    }

    private fun decodeState(root: JsonObject): State {
        val repositories = root["repositories"]?.jsonArray.orEmpty().associate { row ->
            val item = row.jsonObject
            val url = parsePublicHttps(item.getValue("url").jsonPrimitive.content).toString()
            val metadata = item["metadata"]?.jsonArray.orEmpty().map(::decodeMetadata)
            url to LnReaderRepositoryRecord(url, item["refreshedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                metadata, item["error"]?.jsonPrimitive?.contentOrNull)
        }
        val installed = root["installed"]?.jsonArray.orEmpty().associate { row ->
            val item = row.jsonObject
            val metadata = decodeMetadata(item.getValue("metadata"))
            val sourceId = item.getValue("sourceId").jsonPrimitive.content
            require(sourceId.matches(STABLE_SOURCE_ID))
            val sha = item.getValue("sha256").jsonPrimitive.content
            require(sha.matches(SHA256))
            sourceId to LnReaderInstalledPlugin(sourceId,
                parsePublicHttps(item.getValue("repositoryUrl").jsonPrimitive.content).toString(), metadata,
                sha,
                item["approvedOrigins"]?.jsonArray.orEmpty().map { parseOrigin(it.jsonPrimitive.content) }.toSet(),
                item["enabled"]?.jsonPrimitive?.content != "false",
                item["installedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0)
        }
        return State(repositories, installed)
    }

    private fun encodeState(state: State): JsonObject = buildJsonObject {
        put("repositories", buildJsonArray {
            state.repositories.values.forEach { repository -> add(buildJsonObject {
                put("url", JsonPrimitive(repository.url)); put("refreshedAt", JsonPrimitive(repository.refreshedAt))
                put("metadata", JsonArray(repository.metadata.map(::encodeMetadata)))
                repository.error?.let { put("error", JsonPrimitive(it)) }
            }) }
        })
        put("installed", buildJsonArray {
            state.installed.values.forEach { plugin -> add(buildJsonObject {
                put("sourceId", JsonPrimitive(plugin.sourceId)); put("repositoryUrl", JsonPrimitive(plugin.repositoryUrl))
                put("metadata", encodeMetadata(plugin.metadata)); put("sha256", JsonPrimitive(plugin.sha256))
                put("approvedOrigins", JsonArray(plugin.approvedOrigins.sorted().map(::JsonPrimitive)))
                put("enabled", JsonPrimitive(plugin.enabled)); put("installedAt", JsonPrimitive(plugin.installedAt))
            }) }
        })
    }

    private fun encodeMetadata(value: LnReaderPluginMetadata) = buildJsonObject {
        put("id", JsonPrimitive(value.id)); put("name", JsonPrimitive(value.name)); put("site", JsonPrimitive(value.site))
        put("language", JsonPrimitive(value.language)); put("version", JsonPrimitive(value.version))
        put("bundleUrl", JsonPrimitive(value.bundleUrl)); value.iconUrl?.let { put("iconUrl", JsonPrimitive(it)) }
        put("customJs", JsonPrimitive(value.customJs)); put("customCss", JsonPrimitive(value.customCss))
        put("webStorageUtilized", JsonPrimitive(value.webStorageUtilized))
        value.compatibilityError?.let { put("compatibilityError", JsonPrimitive(it)) }
    }

    private fun decodeMetadata(value: JsonElement): LnReaderPluginMetadata {
        val item = value.jsonObject
        fun text(key: String) = item[key]?.jsonPrimitive?.contentOrNull ?: ""
        val id = text("id")
        require(id.matches(EXTERNAL_PLUGIN_ID))
        val siteInput = text("site")
        val bundleInput = text("bundleUrl")
        val iconInput = text("iconUrl").takeIf { it.isNotBlank() }
        val site = runCatching { parsePublicHttps(siteInput).toString() }.getOrNull()
        val bundle = runCatching { parsePublicHttps(bundleInput).toString() }.getOrNull()
        val icon = iconInput?.let { runCatching { parsePublicHttps(it).toString() }.getOrNull() }
        val customJs = item["customJs"]?.jsonPrimitive?.content == "true"
        val customCss = item["customCss"]?.jsonPrimitive?.content == "true"
        val webStorage = item["webStorageUtilized"]?.jsonPrimitive?.content == "true"
        val reason = item["compatibilityError"]?.jsonPrimitive?.contentOrNull ?: when {
            site == null -> "Unsupported source site: public HTTPS is required. This entry cannot be executed."
            bundle == null -> "Unsupported bundle URL: public HTTPS is required. This entry cannot be staged."
            iconInput != null && icon == null -> "Unsupported icon URL: public HTTPS is required. This entry cannot be staged."
            customJs -> "Unsupported capability: custom JavaScript extensions are not executed."
            customCss -> "Unsupported capability: custom CSS extensions are not applied."
            webStorage -> "Unsupported capability: browser Web Storage capture is unavailable."
            else -> null
        }
        return LnReaderPluginMetadata(id, text("name").take(160), siteInput.take(4096),
            text("language").take(32), text("version").take(80), bundleInput.take(4096),
            iconInput?.take(4096),
            customJs, customCss, webStorage, reason)
    }

    private fun bundleFile(sourceId: String, sha256: String): File {
        require(sourceId.matches(STABLE_SOURCE_ID) && sha256.matches(SHA256)) { "The stored source identity is invalid." }
        return bundleDirectory.resolve(sourceId).resolve("$sha256.js")
    }

    private fun pruneBundles(sourceId: String, keepHash: String) {
        bundleDirectory.resolve(sourceId).listFiles().orEmpty().filter { it.name != "$keepHash.js" }.forEach(File::delete)
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        require(bytes.size <= MAX_STATE_BYTES || target.extension == "js")
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            check(temporary.renameTo(target)) { "Could not atomically save LNReader source data." }
        } finally { temporary.delete() }
    }

    private fun parseOrigin(url: String): String = parsePublicHttps(url).originKey().also { origin ->
        val parsed = parsePublicHttps(url)
        require(url == origin || (parsed.encodedPath == "/" && parsed.query == null && parsed.fragment == null)) {
            "Only exact public HTTPS origins can be approved."
        }
    }

    private fun stableSourceId(repositoryUrl: String, pluginId: String): String {
        val material = "$repositoryUrl\u0000$pluginId".toByteArray(Charsets.UTF_8)
        return "lnreader." + sha256(material).take(32)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun LnReaderExecutionResult.storageSize(): Int = JsonObject(storage).toString().toByteArray(Charsets.UTF_8).size

    private data class StagedBundle(
        val repositoryUrl: String,
        val metadata: LnReaderPluginMetadata,
        val sourceId: String,
        val bundle: ByteArray,
        val sha256: String,
        val requiredOrigins: Set<String>,
        val existingSha256: String?,
    )

    private data class State(
        val repositories: Map<String, LnReaderRepositoryRecord> = emptyMap(),
        val installed: Map<String, LnReaderInstalledPlugin> = emptyMap(),
    )

    private companion object {
        val EXTERNAL_PLUGIN_ID = Regex("[A-Za-z0-9._-]{1,100}")
        val STABLE_SOURCE_ID = Regex("[a-z0-9._-]{1,100}")
        val SHA256 = Regex("[a-f0-9]{64}")
        const val MAX_REPOSITORIES = 16
        const val MAX_MANIFEST_ENTRIES = 5_000
        const val MAX_MANIFEST_BYTES = 2_000_000
        const val MAX_BUNDLE_BYTES = 2_000_000
        const val MAX_STORAGE_BYTES = 64 * 1024
        const val MAX_STATE_BYTES = 8_000_000
        const val MAX_APPROVED_ORIGINS = 24
        const val MAX_STAGED_PLUGINS = 4
        const val MAX_STAGED_BYTES = 8_000_000
        const val MAX_COVER_BYTES = 512 * 1024
        const val MAX_COVER_DIMENSION = 4096
        const val MAX_COVER_PIXELS = 12_000_000L
        const val MAX_COVER_CACHE_BYTES = 25L * 1024 * 1024
    }
}
