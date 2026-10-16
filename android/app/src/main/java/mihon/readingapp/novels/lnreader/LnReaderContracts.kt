package mihon.readingapp.novels.lnreader

import kotlinx.serialization.json.JsonElement

/** Public repository metadata. The external id is preserved exactly as published by LNReader. */
data class LnReaderPluginMetadata(
    val id: String,
    val name: String,
    val site: String,
    val language: String,
    val version: String,
    val bundleUrl: String,
    val iconUrl: String? = null,
    val customJs: Boolean = false,
    val customCss: Boolean = false,
    val webStorageUtilized: Boolean = false,
    /** Reason this entry may be listed but cannot be safely installed/executed here. */
    val compatibilityError: String? = null,
)

data class LnReaderRepositoryRecord(
    val url: String,
    val refreshedAt: Long = 0,
    val metadata: List<LnReaderPluginMetadata> = emptyList(),
    val error: String? = null,
)

data class LnReaderInstalledPlugin(
    val sourceId: String,
    val repositoryUrl: String,
    val metadata: LnReaderPluginMetadata,
    val sha256: String,
    val approvedOrigins: Set<String>,
    val enabled: Boolean,
    val installedAt: Long,
)

data class LnReaderInstallReview(
    val reviewToken: String,
    val metadata: LnReaderPluginMetadata,
    val sourceId: String,
    val sha256: String,
    val bundleSize: Int,
    val requiredOrigins: Set<String>,
    val existingSha256: String? = null,
)

data class LnReaderRuntimeSupport(val supported: Boolean, val missingFeatures: List<String>, val reason: String? = null)

data class LnReaderExecutionResult(
    val value: JsonElement?,
    val storage: Map<String, JsonElement>,
    val requests: Int,
)

data class LnReaderHttpRequest(
    val id: String,
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
)

data class LnReaderHttpResponse(
    val url: String,
    val status: Int,
    val statusText: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/** Safe, source-declared image request fields supported by LNReader's imageRequestInit contract. */
data class LnReaderImageRequestInit(
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

interface LnReaderHostFetch {
    suspend fun fetch(request: LnReaderHttpRequest, allowedOrigins: Set<String>, maxBytes: Int): LnReaderHttpResponse
}

