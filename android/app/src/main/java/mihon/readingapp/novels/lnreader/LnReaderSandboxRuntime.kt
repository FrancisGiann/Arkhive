package mihon.readingapp.novels.lnreader

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.coroutineContext

/**
 * Fresh, serialized AndroidX JavaScript isolate for one approved LNReader plugin call.
 * There is deliberately no in-process JavaScript fallback or app-object bridge.
 */
class LnReaderSandboxRuntime(
    context: Context,
    private val fetcher: LnReaderHostFetch,
    helperBundle: ByteArray? = null,
    bootstrap: String? = null,
) {
    private val appContext = context.applicationContext
    private val helpers by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        helperBundle ?: appContext.assets.open(HELPER_ASSET).use { it.readBytes() }
    }
    private val bootstrapSource by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        bootstrap ?: appContext.assets.open(BOOTSTRAP_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    suspend fun supportStatus(): LnReaderRuntimeSupport = globalLock.withLock {
        if (!JavaScriptSandbox.isSupported()) {
            return@withLock LnReaderRuntimeSupport(false, REQUIRED_FEATURES, "The installed WebView provider does not support AndroidX JavaScriptSandbox.")
        }
        val sandboxLease = try { withTimeout(SUPPORT_TIMEOUT_MS) { connectSandbox() } } catch (failure: CancellationException) {
            currentCoroutineContext().ensureActive()
            return@withLock LnReaderRuntimeSupport(false, REQUIRED_FEATURES, "The isolated JavaScript provider did not connect before the support check timed out.")
        } catch (failure: Throwable) {
            return@withLock LnReaderRuntimeSupport(false, REQUIRED_FEATURES, "The installed provider could not start an isolated JavaScript process: ${failure.rootMessage()}")
        }
        var support: LnReaderRuntimeSupport? = null
        var inspectionFailure: Throwable? = null
        try {
            val sandbox = sandboxLease.resource
            val missing = REQUIRED_FEATURES.filterNot(sandbox::isFeatureSupported)
            support = LnReaderRuntimeSupport(missing.isEmpty(), missing, if (missing.isEmpty()) null else "The installed provider is missing required isolation features.")
        } catch (failure: Throwable) {
            inspectionFailure = failure
        }
        val closeFailure = try {
            sandboxLease.close()
            null
        } catch (failure: Throwable) {
            failure
        }
        val failure = inspectionFailure ?: closeFailure
        if (failure != null) {
            if (failure is CancellationException) currentCoroutineContext().ensureActive()
            return@withLock LnReaderRuntimeSupport(
                false,
                REQUIRED_FEATURES,
                "The installed provider could not complete its isolated JavaScript support check: ${failure.rootMessage()}",
            )
        }
        checkNotNull(support)
    }

    suspend fun execute(
        pluginId: String,
        pluginName: String,
        pluginSite: String,
        pluginVersion: String,
        bundle: ByteArray,
        operation: String,
        arguments: List<JsonElement>,
        approvedOrigins: Set<String>,
        storage: Map<String, JsonElement> = emptyMap(),
        webStorageUtilized: Boolean = false,
        userAgent: String = DEFAULT_USER_AGENT,
        authorizationCheck: suspend () -> Boolean = { true },
    ): LnReaderExecutionResult = globalLock.withLock {
        require(pluginId.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "The plugin identity is invalid." }
        require(operation in SUPPORTED_OPERATIONS) { "Unsupported LNReader operation: $operation" }
        require(bundle.isNotEmpty() && bundle.size <= MAX_BUNDLE_BYTES) { "The plugin bundle is empty or exceeds its 2 MiB limit." }
        require(approvedOrigins.isNotEmpty() && approvedOrigins.size <= MAX_APPROVED_ORIGINS) { "The approved source origin list is invalid." }
        require(!webStorageUtilized) { "Unsupported capability: this source requires browser Web Storage capture." }
        require(storageJsonSize(storage) <= MAX_STORAGE_BYTES) { "Plugin storage exceeds its 64 KiB limit." }
        require(userAgent.length in 1..256 && userAgent.none { it == '\r' || it == '\n' }) { "The source User-Agent is invalid." }
        check(JavaScriptSandbox.isSupported()) { "Unsupported capability: the installed WebView provider cannot run an isolated JavaScript sandbox." }

        withTimeout(OPERATION_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val sandboxLease = connectSandbox()
                val sandbox = sandboxLease.resource
                var isolate: JavaScriptIsolate? = null
                try {
                    val missing = REQUIRED_FEATURES.filterNot(sandbox::isFeatureSupported)
                    check(missing.isEmpty()) { "Unsupported JavaScript provider features: ${missing.joinToString()}" }
                    val runtime = sandbox.createIsolate(IsolateStartupParameters().apply {
                        setMaxHeapSizeBytes(MAX_HEAP_BYTES)
                        setMaxEvaluationReturnSizeBytes(MAX_EVALUATION_RESULT_BYTES)
                    })
                    isolate = runtime
                    val operationInput = buildJsonObject {
                        put("operation", JsonPrimitive(operation))
                        put("pluginId", JsonPrimitive(pluginId))
                        put("pluginName", JsonPrimitive(pluginName))
                        put("pluginSite", JsonPrimitive(pluginSite))
                        put("pluginVersion", JsonPrimitive(pluginVersion))
                        put("args", JsonArray(arguments))
                        put("storage", JsonObject(storage))
                        put("webStorageUtilized", JsonPrimitive(webStorageUtilized))
                        put("userAgent", JsonPrimitive(userAgent))
                    }
                    runtime.provideNamedData("lr-helper-bundle", helpers)
                    runtime.provideNamedData("lr-plugin-bundle", bundle)
                    runtime.provideNamedData("lr-operation-input", operationInput.toString().toByteArray(Charsets.UTF_8))
                    val ready = runtime.evaluateJavaScriptAsync(bootstrapSource).awaitCancellable()
                    check(ready == "ready") { "The isolated plugin bootstrap did not initialize." }

                    var requestCount = 0
                    var totalRequestBytes = 0L
                    var totalRequestBodyBytes = 0L
                    var totalResponseBytes = 0L
                    val requestIds = mutableSetOf<String>()
                    while (true) {
                        coroutineContext.ensureActive()
                        val poll = runtime.evaluateJavaScriptAsync("globalThis.__lnReaderPoll()").awaitCancellable()
                        require(poll.toByteArray(Charsets.UTF_8).size <= MAX_EVALUATION_RESULT_BYTES) { "The plugin produced an oversized result." }
                        val report = Json.parseToJsonElement(poll).jsonObject
                        val requests = report["requests"]?.jsonArray.orEmpty()
                        require(requests.size <= MAX_REQUESTS_PER_OPERATION - requestCount) {
                            "The plugin exceeded its native request-count limit."
                        }
                        for (entry in requests) {
                            coroutineContext.ensureActive()
                            check(authorizationCheck()) {
                                "This LNReader source was disabled or removed while the operation was running."
                            }
                            val request = parseRequest(entry)
                            require(requestIds.add(request.id)) { "The plugin reused an HTTP request id." }
                            requestCount++
                            totalRequestBytes += entry.toString().toByteArray(Charsets.UTF_8).size
                            require(totalRequestBytes <= MAX_TOTAL_REQUEST_BYTES) { "The plugin exceeded its aggregate request-size limit." }
                            totalRequestBodyBytes += request.body?.size ?: 0
                            require(totalRequestBodyBytes <= MAX_TOTAL_REQUEST_BODY_BYTES) { "The plugin exceeded its aggregate request-body limit." }
                            try {
                                val remaining = (MAX_TOTAL_RESPONSE_BYTES - totalResponseBytes).toInt()
                                require(remaining > 0) { "The plugin exceeded its aggregate response limit." }
                                val response = fetcher.fetch(request, approvedOrigins, minOf(MAX_HTTP_BODY_BYTES, remaining))
                                require(response.body.size <= MAX_HTTP_BODY_BYTES && response.body.size <= remaining) {
                                    "The plugin response exceeded its native byte limit."
                                }
                                require(response.status in 100..599 && response.statusText.none { it == '\r' || it == '\n' }) {
                                    "The plugin response metadata is invalid."
                                }
                                require(response.url.length <= 4096 &&
                                    parsePublicHttps(response.url).originKey() in approvedOrigins) {
                                    "The plugin response left its reviewed public origins."
                                }
                                require(response.headers.size <= MAX_RESPONSE_HEADERS) { "The plugin response has too many headers." }
                                val checkedHeaders = response.headers.mapKeys { (name, _) ->
                                    val normalized = name.lowercase()
                                    require(normalized.matches(Regex("[a-z0-9!#$%&'*+.^_`|~-]{1,64}"))) {
                                        "The plugin response has an invalid header name."
                                    }
                                    normalized
                                }.mapValues { (_, value) ->
                                    require(value.length <= MAX_RESPONSE_HEADER_VALUE_BYTES && value.none { it == '\r' || it == '\n' }) {
                                        "The plugin response has an invalid header value."
                                    }
                                    value
                                }
                                val responseMeta = buildJsonObject {
                                    put("status", JsonPrimitive(response.status))
                                    put("statusText", JsonPrimitive(response.statusText.take(128)))
                                    put("headers", JsonObject(checkedHeaders.mapValues { JsonPrimitive(it.value) }))
                                    put("url", JsonPrimitive(response.url))
                                    put("requestedUrl", JsonPrimitive(request.url))
                                }.toString()
                                val responseBytes = response.body.size + responseMeta.toByteArray(Charsets.UTF_8).size
                                require(responseBytes <= remaining) { "The plugin exceeded its aggregate response limit." }
                                totalResponseBytes += responseBytes
                                val dataName = "lr-response-${request.id}"
                                runtime.provideNamedData(dataName, response.body)
                                val expression = "globalThis.__lnReaderDeliver(${request.id}, ${JsonPrimitive(responseMeta)}, ${JsonPrimitive(dataName)})"
                                runtime.evaluateJavaScriptAsync(expression).awaitCancellable()
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Throwable) {
                                val expression = "globalThis.__lnReaderReject(${request.id}, ${JsonPrimitive(failure.rootMessage().take(1000))})"
                                runtime.evaluateJavaScriptAsync(expression).awaitCancellable()
                            }
                        }
                        if (report["done"]?.jsonPrimitive?.content == "true") {
                            val error = report["error"]?.jsonPrimitive?.contentOrNull
                            check(error.isNullOrBlank()) { error ?: "The plugin operation failed." }
                            val result = report["result"]?.takeUnless { it == JsonNull }
                            val stored = report["storage"]?.jsonObject.orEmpty()
                            require(storageJsonSize(stored) <= MAX_STORAGE_BYTES) { "Plugin storage exceeds its 64 KiB limit." }
                        return@withContext LnReaderExecutionResult(result, stored, requestCount)
                        }
                        delay(POLL_INTERVAL_MS)
                    }
                    error("Unreachable plugin operation state.")
                } finally {
                    try { isolate?.close() } finally { sandboxLease.close() }
                }
            }
        }
    }

    private fun parseRequest(value: JsonElement): LnReaderHttpRequest {
        val item = value.jsonObject
        val id = item["id"]?.jsonPrimitive?.contentOrNull ?: error("The plugin issued a malformed request.")
        require(id.matches(Regex("[0-9]{1,6}"))) { "The plugin issued a malformed request id." }
        val headers = item["headers"]?.jsonObject.orEmpty().entries.associate { (key, header) ->
            key to (header.jsonPrimitive.contentOrNull ?: "")
        }
        val body = item["body"]?.jsonPrimitive?.contentOrNull?.toByteArray(Charsets.UTF_8)
        require(body == null || body.size <= LnReaderHttpBroker.MAX_REQUEST_BODY_BYTES) { "The plugin request body exceeds its limit." }
        return LnReaderHttpRequest(
            id = id,
            url = item["url"]?.jsonPrimitive?.contentOrNull ?: error("The plugin omitted a request URL."),
            method = item["method"]?.jsonPrimitive?.contentOrNull ?: "GET",
            headers = headers,
            body = body,
        )
    }

    private fun storageJsonSize(storage: Map<String, JsonElement>): Int = JsonObject(storage).toString().toByteArray(Charsets.UTF_8).size

    private suspend fun connectSandbox(): SandboxConnectionOwner.Lease<JavaScriptSandbox> {
        // Capture only the process application context so a canceled screen cannot be retained by
        // an AndroidX bind that is still completing in the background.
        val context = appContext
        return connectionOwner.acquire {
            val providerFuture = JavaScriptSandbox.createConnectedInstanceAsync(context)
            CompletableFuture<JavaScriptSandbox>().also { completion ->
                providerFuture.addListener({
                    try {
                        completion.complete(providerFuture.get())
                    } catch (failure: Throwable) {
                        completion.completeExceptionally((failure as? ExecutionException)?.cause ?: failure.cause ?: failure)
                    }
                }, MoreExecutors.directExecutor())
            }
        }.awaitConnectionCancellable()
    }

    private fun Throwable.rootCause(): Throwable = (this as? ExecutionException)?.cause ?: cause ?: this
    private fun Throwable.rootMessage(): String = rootCause().message?.take(240) ?: rootCause().javaClass.simpleName

    private companion object {
        const val HELPER_ASSET = "lnreader/helpers.js"
        const val BOOTSTRAP_ASSET = "lnreader/bootstrap.js"
        const val DEFAULT_USER_AGENT = "Arkhive/0.4.1 isolated LNReader source client"
        const val MAX_HEAP_BYTES = 64L * 1024 * 1024
        const val MAX_EVALUATION_RESULT_BYTES = 2_000_000
        const val MAX_BUNDLE_BYTES = 2_000_000
        const val MAX_HTTP_BODY_BYTES = 2_000_000
        const val MAX_STORAGE_BYTES = 64 * 1024
        const val MAX_APPROVED_ORIGINS = 24
        const val MAX_REQUESTS_PER_OPERATION = 24
        const val MAX_TOTAL_REQUEST_BYTES = 768 * 1024
        const val MAX_TOTAL_REQUEST_BODY_BYTES = 256 * 1024
        const val MAX_TOTAL_RESPONSE_BYTES = 8_000_000
        const val MAX_RESPONSE_HEADERS = 64
        const val MAX_RESPONSE_HEADER_VALUE_BYTES = 4096
        const val OPERATION_TIMEOUT_MS = 20_000L
        const val SUPPORT_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 12L
        val SUPPORTED_OPERATIONS = setOf("describe", "popularNovels", "searchNovels", "parseNovel", "parseChapter", "resolveUrl", "parsePage")
        val REQUIRED_FEATURES = listOf(
            JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN,
            JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER,
            JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE,
            JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION,
            JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT,
        )
        val globalLock = Mutex()
        val connectionOwner = SandboxConnectionOwner<JavaScriptSandbox>()
    }
}

private suspend fun <T : AutoCloseable> CompletableFuture<T>.awaitConnectionCancellable(): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel(false) }
    whenComplete { value, failure ->
        if (failure != null) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        } else {
            continuation.resume(checkNotNull(value)) { _, abandoned, _ -> abandoned.close() }
        }
    }
}

private suspend fun <T> ListenableFuture<T>.awaitCancellable(): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel(true) }
    addListener({
        try {
            continuation.resume(get()) { _, _, _ -> }
        } catch (failure: Throwable) {
            if (continuation.isActive) continuation.resumeWithException((failure as? ExecutionException)?.cause ?: failure)
        }
    }, MoreExecutors.directExecutor())
}

