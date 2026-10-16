package mihon.readingapp.novels

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.booleanOrNull
import mihon.readingapp.novels.lnreader.LnReaderExtensionManager
import mihon.readingapp.novels.lnreader.LnReaderInstalledPlugin
import mihon.readingapp.novels.lnreader.LnReaderExecutionResult
import mihon.readingapp.novels.lnreader.LnReaderImageRequestInit
import mihon.readingapp.novels.lnreader.parsePublicHttps
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

data class LnReaderFilterOption(val label: String, val value: String)
data class LnReaderFilterDefinition(
    val key: String,
    val label: String,
    val type: String,
    val defaultValue: JsonElement,
    val options: List<LnReaderFilterOption>,
)
data class LnReaderSourceDescriptor(
    val hasParsePage: Boolean,
    val hasResolveUrl: Boolean,
    val filters: List<LnReaderFilterDefinition>,
    val imageRequestInit: LnReaderImageRequestInit = LnReaderImageRequestInit(),
    val error: String? = null,
)

/** LNReader Plugin contract adapter; every call is still executed by the isolated, reviewed host. */
class LnReaderNovelSource internal constructor(
    private val manager: LnReaderExtensionManager,
    initial: LnReaderInstalledPlugin,
) : NovelSource {
    private val initialRecord = initial
    private val descriptorMutable = MutableStateFlow<LnReaderSourceDescriptor?>(null)
    val descriptor: StateFlow<LnReaderSourceDescriptor?> = descriptorMutable.asStateFlow()
    private val descriptorMutex = Mutex()
    @Volatile
    private var descriptorHash: String? = null
    @Volatile
    private var filterValues: Map<String, JsonElement> = emptyMap()

    override val id: String = initial.sourceId
    override val name: String get() = manager.currentInstalled(id)?.metadata?.name ?: initialRecord.metadata.name
    override val site: String get() = manager.currentInstalled(id)?.metadata?.site ?: initialRecord.metadata.site

    fun setFilterValues(values: Map<String, JsonElement>) { filterValues = values.toMap() }

    suspend fun describe(): LnReaderSourceDescriptor = descriptorMutex.withLock {
        val installed = manager.requireEnabled(id)
        if (descriptorHash == installed.sha256) return@withLock requireNotNull(descriptorMutable.value)
        val result = manager.execute(id, "describe", emptyList())
        val root = result.value?.jsonObject ?: error("The extension did not return its source capabilities.")
        val rawDefinitions = root["filters"]
        val definitions = when (rawDefinitions) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> rawDefinitions
            else -> error("The extension declared invalid source filters.")
        }
        require(definitions.size <= MAX_FILTERS) { "The extension declared too many source filters." }
        val filters = definitions.entries.map { (key, value) ->
            parseFilter(key, value) ?: error("The extension declared an invalid source filter: ${key.take(80)}.")
        }
        val imageRequestInit = parseImageRequestInit(root["imageRequestInit"])
        val parsed = LnReaderSourceDescriptor(
            hasParsePage = root["hasParsePage"]?.jsonPrimitive?.content == "true",
            hasResolveUrl = root["hasResolveUrl"]?.jsonPrimitive?.content == "true",
            filters = filters,
            imageRequestInit = imageRequestInit,
        )
        descriptorHash = installed.sha256
        descriptorMutable.value = parsed
        filterValues = filters.associate { it.key to it.defaultValue }
        parsed
    }

    override suspend fun catalog(page: Int, search: String, genre: String?): NovelCatalogPage {
        require(page in 1..MAX_CATALOG_PAGES) { "This extension reached the catalog page limit." }
        val description = describe()
        val result = if (search.isBlank()) {
            val selected = description.filters.associate { filter ->
                val value = filterValues[filter.key] ?: filter.defaultValue
                filter.key to buildJsonObject { put("type", JsonPrimitive(filter.type)); put("value", value) }
            }
            manager.execute(id, "popularNovels", listOf(
                JsonPrimitive(page),
                buildJsonObject { put("showLatestNovels", JsonPrimitive(false)); put("filters", JsonObject(selected)) },
            ))
        } else {
            manager.execute(id, "searchNovels", listOf(JsonPrimitive(search.take(120)), JsonPrimitive(page)))
        }
        val rows = result.value?.jsonArray.orEmpty()
        require(rows.size <= MAX_CATALOG_ITEMS) { "The extension returned too many catalog rows in one page." }
        val items = rows.mapNotNull(::parseNovelItem)
        return NovelCatalogPage(items, if (items.isNotEmpty() && page < MAX_CATALOG_PAGES) page + 1 else null,
            "${name} · source order")
    }

    override suspend fun detail(path: String): SourceNovel = withTimeout(60_000) {
        validatePath(path)
        val info = describe()
        val result = manager.execute(id, "parseNovel", listOf(JsonPrimitive(path)))
        val root = result.value?.jsonObject ?: error("The extension returned an invalid novel detail.")
        val chapters = parseChapters(root["chapters"]?.jsonArray ?: JsonArray(emptyList()))
        val totalPagesValue = root["totalPages"]
        val totalPages = if (totalPagesValue == null || totalPagesValue == JsonNull) 1
            else totalPagesValue.jsonPrimitive.takeUnless { it.isString }?.intOrNull
                ?: error("Unsupported chapter pagination: totalPages must be a numeric value.")
        require(totalPages in 1..MAX_CHAPTER_PAGES) { "The extension's chapter pagination exceeds the supported numeric page limit." }
        if (totalPages > 1) {
            check(info.hasParsePage) { "Unsupported chapter pagination: the extension reports multiple pages without parsePage." }
            for (number in 2..totalPages) {
                val page = manager.execute(id, "parsePage", listOf(JsonPrimitive(path), JsonPrimitive(number.toString())))
                val pageChapters = page.value?.jsonObject?.get("chapters")?.jsonArray
                    ?: error("Unsupported chapter pagination: parsePage did not return a chapter list for numeric page $number.")
                val knownPaths = chapters.mapTo(hashSetOf()) { it.path }
                val next = parseChapters(pageChapters)
                val novelCount = next.count { it.path !in knownPaths }
                require(next.isNotEmpty() && novelCount > 0) {
                    "Unsupported chapter pagination: numeric page $number repeated chapter paths or returned no chapters before the declared final page."
                }
                chapters += next
                require(chapters.size <= MAX_CHAPTERS) { "This novel has too many chapters." }
            }
        }
        val unique = linkedMapOf<String, NovelChapter>()
        chapters.forEach { chapter -> unique.putIfAbsent(chapter.path, chapter) }
        require(unique.isNotEmpty()) { "The extension did not return any chapters." }
        require(unique.size <= MAX_CHAPTERS) { "This novel has too many chapters." }
        val provenance = resolveUrl(path, true, info)
        val genres = root["genres"]?.jsonPrimitive?.contentOrNull.orEmpty()
            .split(',', '|').map(String::trim).filter(String::isNotEmpty).take(24)
        return@withTimeout SourceNovel(
            path = path,
            name = root["name"]?.jsonPrimitive?.contentOrNull?.take(500)?.takeIf(String::isNotBlank)
                ?: error("The extension returned a novel without a title."),
            author = root["author"]?.jsonPrimitive?.contentOrNull?.take(300),
            cover = normalizeCover(root["cover"]?.jsonPrimitive?.contentOrNull),
            genres = genres,
            summary = root["summary"]?.jsonPrimitive?.contentOrNull?.take(20_000),
            chapters = unique.values.toList(),
            status = root["status"]?.jsonPrimitive?.contentOrNull?.take(80),
            provenanceUrl = provenance,
            rights = "Provided by ${name}; review the publisher's and source site's terms before downloading or redistributing.",
        )
    }

    override suspend fun chapter(path: String): String {
        validatePath(path)
        val result = manager.execute(id, "parseChapter", listOf(JsonPrimitive(path)))
        val html = result.value?.jsonPrimitive?.contentOrNull ?: error("The extension returned no chapter text.")
        require(html.toByteArray().size <= 2_000_000) { "The chapter is larger than the supported limit." }
        return html
    }

    suspend fun coverFile(url: String): File? {
        val base = site.toHttpUrlOrNull() ?: error("This extension's site URL cannot resolve its cover.")
        val resolved = base.resolve(url)?.toString() ?: error("This extension returned an invalid cover URL.")
        manager.cachedCoverFile(id, resolved)?.let { return it }
        return manager.coverFile(id, resolved, describe().imageRequestInit)
    }

    private fun parseImageRequestInit(value: JsonElement?): LnReaderImageRequestInit {
        val row = when (value) {
            null, JsonNull -> return LnReaderImageRequestInit()
            is JsonObject -> value
            else -> error("Unsupported capability: imageRequestInit must be a plain object.")
        }
        val rawMethod = row["method"]
        val method = if (rawMethod == null || rawMethod == JsonNull) "GET" else
            (rawMethod as? JsonPrimitive)?.takeIf { it.isString }?.content?.uppercase()
                ?: error("Unsupported capability: imageRequestInit method must be a string.")
        require(method in setOf("GET", "HEAD", "POST")) {
            "Unsupported capability: imageRequestInit supports only GET, HEAD, or POST."
        }
        val rawHeaders = row["headers"] as? JsonObject ?: error("Unsupported capability: imageRequestInit headers must be a plain string map.")
        require(rawHeaders.size <= 40) { "The extension declared too many image request headers." }
        val headers = rawHeaders.entries.associate { (name, rawValue) ->
            val normalized = name.trim().lowercase()
            require(normalized.matches(Regex("[a-z0-9!#$%&'*+.^_`|~-]{1,64}"))) { "The extension declared an invalid image request header." }
            require(normalized !in setOf("cookie", "cookie2", "proxy-authorization", "host", "connection", "content-length", "origin") && "authorization" !in normalized) {
                "Unsupported capability: imageRequestInit cannot contain credentials or browser session headers."
            }
            val headerValue = (rawValue as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: error("Unsupported capability: imageRequestInit header values must be strings.")
            require(headerValue.length <= 2048 && headerValue.none { it == '\r' || it == '\n' }) {
                "An image request header exceeds its limit."
            }
            normalized to headerValue
        }
        val body = row["body"]?.let { rawBody ->
            if (rawBody == JsonNull) null
            else (rawBody as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: error("Unsupported capability: imageRequestInit body must be a string.")
        }
        require(body == null || (method == "POST" && body.toByteArray().size <= 64 * 1024)) {
            "Unsupported capability: imageRequestInit supports at most a 64 KiB POST body."
        }
        return LnReaderImageRequestInit(method, headers, body)
    }

    private suspend fun resolveUrl(path: String, isNovel: Boolean, info: LnReaderSourceDescriptor): String {
        val candidate = if (info.hasResolveUrl) {
            val value = manager.execute(id, "resolveUrl", listOf(JsonPrimitive(path), JsonPrimitive(isNovel)))
                .value?.jsonPrimitive?.contentOrNull ?: error("The extension returned an invalid source URL.")
            value
        } else {
            val base = site.toHttpUrlOrNull() ?: error("The extension site URL is invalid.")
            base.resolve(path)?.toString() ?: error("The extension path cannot be resolved safely.")
        }
        return manager.requireApprovedHttpsUrl(id, candidate)
    }

    private fun parseFilter(key: String, value: JsonElement): LnReaderFilterDefinition? {
        if (!key.matches(Regex("[A-Za-z0-9._ -]{1,80}"))) return null
        val row = value as? JsonObject ?: return null
        val label = row["label"]?.jsonPrimitive?.contentOrNull?.take(100)?.takeIf(String::isNotBlank) ?: key
        val type = row["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length in 1..80 } ?: return null
        val default = row["value"] ?: when (type) {
            "Switch" -> JsonPrimitive(false)
            "Checkbox" -> JsonArray(emptyList())
            "XCheckbox" -> buildJsonObject { put("include", JsonArray(emptyList())); put("exclude", JsonArray(emptyList())) }
            else -> JsonPrimitive("")
        }
        val optionRows = row["options"]?.jsonArray.orEmpty()
        require(optionRows.size <= MAX_FILTER_OPTIONS) { "The extension declared too many filter options." }
        val options = optionRows.mapNotNull { option ->
            val item = option as? JsonObject ?: return@mapNotNull null
            val optionLabel = item["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val optionValue = item["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (optionLabel.length > 100 || optionValue.length > 200) null else LnReaderFilterOption(optionLabel, optionValue)
        }
        if (type in SUPPORTED_FILTERS) {
            when (type) {
                "Text", "Picker" -> if ((default as? JsonPrimitive)?.isString != true) return null
                "Switch" -> if ((default as? JsonPrimitive)?.booleanOrNull == null) return null
                "Checkbox" -> if ((default as? JsonArray)?.all { (it as? JsonPrimitive)?.isString == true } != true) return null
                "XCheckbox" -> {
                    val groups = default as? JsonObject ?: return null
                    if (groups.keys.any { it !in setOf("include", "exclude") }) return null
                    if (listOf("include", "exclude").any { key ->
                            val group = groups[key] ?: return@any false
                            (group as? JsonArray)?.all { (it as? JsonPrimitive)?.isString == true } != true
                        }) return null
                }
            }
            if (type in setOf("Picker", "Checkbox", "XCheckbox") && options.isEmpty()) return null
        }
        return LnReaderFilterDefinition(key, label, type, default, options)
    }

    private fun parseNovelItem(value: JsonElement): NovelItem? {
        val item = value as? JsonObject ?: return null
        val path = item["path"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!validPath(path)) return null
        val title = item["name"]?.jsonPrimitive?.contentOrNull?.take(500)?.takeIf(String::isNotBlank) ?: return null
        val cover = normalizeCover(item["cover"]?.jsonPrimitive?.contentOrNull)
        return NovelItem(path, title, cover)
    }

    /** Persist safe absolute HTTPS image metadata so cache keys survive source removal/restart. */
    private fun normalizeCover(value: String?): String? {
        val raw = value?.takeIf { it.length <= 4096 }?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val base = runCatching { parsePublicHttps(site) }.getOrNull() ?: return null
        val resolved = base.resolve(raw) ?: return null
        // This validates scheme, public-host syntax, and absence of credentials. Approval is
        // intentionally checked by the broker later so a CDN can be shown as an exact-origin
        // review request rather than silently dropped from the source metadata.
        return runCatching { parsePublicHttps(resolved.toString()).toString() }.getOrNull()
    }

    private fun parseChapters(values: JsonArray): MutableList<NovelChapter> {
        require(values.size <= MAX_CHAPTERS_PER_PAGE) { "The extension returned too many chapters in one page." }
        return values.mapNotNull { value ->
        val item = value as? JsonObject ?: return@mapNotNull null
        val path = item["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        if (!validPath(path)) return@mapNotNull null
        val name = item["name"]?.jsonPrimitive?.contentOrNull?.take(500)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val page = item["page"]?.jsonPrimitive?.contentOrNull
        if (!page.isNullOrBlank() && page.toIntOrNull() == null) {
            error("Unsupported chapter pagination: opaque page cursors are not supported in this version.")
        }
        val number = item["chapterNumber"]?.jsonPrimitive?.doubleOrNull?.takeIf(Double::isFinite)
        val release = item["releaseTime"]?.jsonPrimitive?.contentOrNull?.take(120)
        NovelChapter(path, name, number, release)
        }.toMutableList()
    }

    private fun validatePath(path: String) { require(validPath(path)) { "The extension returned an invalid source path." } }

    private fun validPath(path: String) = path.isNotBlank() && path.length <= 4096 && path.none { it.code < 32 }

    private companion object {
        const val MAX_CATALOG_PAGES = 40
        const val MAX_CATALOG_ITEMS = 50
        const val MAX_CHAPTER_PAGES = 40
        const val MAX_CHAPTERS_PER_PAGE = 300
        const val MAX_FILTERS = 64
        const val MAX_FILTER_OPTIONS = 60
        const val MAX_CHAPTERS = 5000
        val SUPPORTED_FILTERS = setOf("Text", "Picker", "Checkbox", "Switch", "XCheckbox")
    }
}
