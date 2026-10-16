package mihon.readingapp.novels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import mihon.readingapp.design.Ink
import mihon.readingapp.settings.ArkhiveSettingsHeader
import mihon.readingapp.settings.InkSettingsTheme
import mihon.readingapp.novels.lnreader.LnReaderInstallReview
import mihon.readingapp.novels.lnreader.LnReaderPluginMetadata
import mihon.readingapp.novels.lnreader.normalizePublicHttpsOrigin
import mihon.readingapp.novels.lnreader.normalizePublicHttpsOriginLines

private const val OFFICIAL_PLUGIN_REPOSITORY =
    "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json"

class NovelExtensionsScreen(
    private val suggestedCoverOrigin: String? = null,
    private val suggestedSourceId: String? = null,
) : Screen() {
    @Composable override fun Content() = LnReaderExtensionManagement(
        showHeader = true,
        suggestedCoverOrigin = suggestedCoverOrigin,
        suggestedSourceId = suggestedSourceId,
    )
}

@Composable fun LnReaderExtensionManagement(
    showHeader: Boolean = false,
    suggestedCoverOrigin: String? = null,
    suggestedSourceId: String? = null,
) = InkSettingsTheme {
    val nav = LocalNavigator.currentOrThrow
    val registry = currentNovelSources()
    val manager = remember(registry) { registry.lnReaderExtensions }
    val snapshot by manager.snapshots.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var repositoryUrl by rememberSaveable { mutableStateOf(OFFICIAL_PLUGIN_REPOSITORY) }
    var search by rememberSaveable { mutableStateOf("") }
    var review by remember { mutableStateOf<LnReaderInstallReview?>(null) }
    var extraOrigins by rememberSaveable { mutableStateOf("") }
    var selectedSuggestedOrigins by remember { mutableStateOf<Set<String>>(emptySet()) }
    val safeSuggestedCoverOrigin = remember(suggestedCoverOrigin) {
        suggestedCoverOrigin?.let { runCatching { normalizePublicHttpsOrigin(it) }.getOrNull() }
    }
    val normalizedAdditionalOrigins = remember(extraOrigins) {
        runCatching { normalizePublicHttpsOriginLines(extraOrigins) }
    }
    var runtimeMessage by remember { mutableStateOf("Checking isolated JavaScript support…") }
    var snapshotLoaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var uninstall by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(manager) {
        manager.snapshot()
        snapshotLoaded = true
        val support = manager.runtimeStatus()
        runtimeMessage = support.reason ?: if (support.supported) "Isolated JavaScript extensions are available on this device."
        else "Unsupported JavaScript provider features: ${support.missingFeatures.joinToString()}"
    }

    val installed = snapshot.installedPlugins.associateBy { it.repositoryUrl to it.metadata.id }
    val repositoryEntries = snapshot.repositories.flatMap { repository ->
        repository.metadata.map { metadata -> Triple(repository.url, metadata, true) }
    }
    val listedIdentities = repositoryEntries.mapTo(mutableSetOf()) { (repository, metadata, _) ->
        repository to metadata.id
    }
    // Keep locally installed bundles manageable after upstream removes, renames, or stops
    // listing them. Orphaned rows retain disable/uninstall actions but cannot stage updates.
    val orphanedInstalled = snapshot.installedPlugins
        .filterNot { (it.repositoryUrl to it.metadata.id) in listedIdentities }
        .map { Triple(it.repositoryUrl, it.metadata, false) }
    val catalogEntries = repositoryEntries + orphanedInstalled
    val focusedSourceEntry = suggestedSourceId?.let { sourceId ->
        catalogEntries.firstOrNull { (repository, metadata, _) -> installed[repository to metadata.id]?.sourceId == sourceId }
    }
    val entries = catalogEntries
        .filter { (_, metadata, _) -> search.isBlank() || metadata.name.contains(search, true) || metadata.site.contains(search, true) || metadata.language.contains(search, true) }
        .take(1000)

    Column(Modifier.fillMaxSize().background(Ink.Canvas)) {
        if (showHeader) ArkhiveSettingsHeader("Novel extensions", { nav.pop(); Unit })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Novel repositories use the LNReader JSON plugin manifest format; Mihon manga extension repositories are incompatible. Listings show metadata only. A selected bundle runs only after you review and trust it.", color = Ink.Muted, fontSize = 12.sp)
                Text(runtimeMessage, Modifier.padding(top = 8.dp), color = Ink.Link, fontSize = 12.sp)
            }
            if (suggestedSourceId != null) {
                item {
                    Text("Review the current bundle for this source", color = Ink.Text, style = MaterialTheme.typography.titleMedium)
                    if (focusedSourceEntry == null) {
                        Text(
                            if (snapshotLoaded) "This source has no current bundle in the installed extension list. Return to the title to retry its cover, or manage available extensions from Settings → Browse."
                            else "Looking up the installed source…",
                            color = Ink.Muted,
                            fontSize = 12.sp,
                        )
                        if (!snapshotLoaded) LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        val (repository, metadata, canReview) = focusedSourceEntry
                        val plugin = installed[repository to metadata.id]
                        ExtensionMetadataCard(
                            repository = repository,
                            metadata = metadata,
                            installed = plugin,
                            canReview = canReview,
                            onReview = {
                                scope.launch {
                                    runCatching { manager.stagePlugin(repository, metadata.id) }.onSuccess {
                                        review = it
                                        extraOrigins = ""
                                        selectedSuggestedOrigins = emptySet()
                                    }.onFailure { error = it.message }
                                }
                            },
                            onEnabled = { enabled ->
                                scope.launch { runCatching { manager.setEnabled(requireNotNull(plugin).sourceId, enabled) }.onFailure { error = it.message } }
                            },
                            onUninstall = { uninstall = requireNotNull(plugin).sourceId to metadata.name },
                        )
                    }
                }
            } else {
                item {
                    OutlinedTextField(repositoryUrl, { repositoryUrl = it.take(4096) }, label = { Text("LNReader JSON plugin repository manifest") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { runCatching { manager.addRepository(repositoryUrl.trim()); manager.refreshRepository(repositoryUrl.trim()) }.onFailure { error = it.message } } }) { Text("Add and refresh") }
                        TextButton(onClick = { repositoryUrl = OFFICIAL_PLUGIN_REPOSITORY }) { Text("Official repository") }
                    }
                }
                items(snapshot.repositories, key = { it.url }) { repo ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.Panel).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(repo.url, color = Ink.Text, fontSize = 12.sp)
                            Text(if (repo.refreshedAt > 0) "${repo.metadata.size} metadata entries · refreshed ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(repo.refreshedAt))}" else "Not refreshed", color = Ink.Muted, fontSize = 11.sp)
                            repo.error?.let { Text(it, color = Ink.Muted, fontSize = 11.sp) }
                        }
                        TextButton(onClick = { scope.launch { runCatching { manager.refreshRepository(repo.url) }.onFailure { error = it.message } } }) { Text("Refresh") }
                    }
                }
                item {
                    OutlinedTextField(search, { search = it.take(120) }, label = { Text("Find an extension") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("${entries.size} matching entries · metadata only until a selected bundle is reviewed", color = Ink.Muted, fontSize = 11.sp)
                }
                items(entries, key = { (repo, metadata, _) -> "$repo#${metadata.id}" }) { (repo, metadata, canReview) ->
                    val plugin = installed[repo to metadata.id]
                    ExtensionMetadataCard(
                        repository = repo,
                        metadata = metadata,
                        installed = plugin,
                        canReview = canReview,
                        onReview = {
                            scope.launch {
                                runCatching { manager.stagePlugin(repo, metadata.id) }.onSuccess {
                                    review = it
                                    extraOrigins = ""
                                    selectedSuggestedOrigins = emptySet()
                                }.onFailure { error = it.message }
                            }
                        },
                        onEnabled = { enabled -> scope.launch { runCatching { manager.setEnabled(requireNotNull(plugin).sourceId, enabled) }.onFailure { error = it.message } } },
                        onUninstall = { uninstall = requireNotNull(plugin).sourceId to metadata.name },
                    )
                }
                if (entries.size == 1000) item { Text("Search to narrow the repository list. Showing the first 1,000 matching metadata entries.", color = Ink.Muted, fontSize = 11.sp) }
            }
        }
    }

    review?.let { pending ->
        ExtensionReviewDialog(
            review = pending,
            extraOrigins = extraOrigins,
            onExtraOrigins = { extraOrigins = it.take(4096) },
            normalizedAdditionalOrigins = normalizedAdditionalOrigins.getOrNull(),
            additionalOriginError = normalizedAdditionalOrigins.exceptionOrNull()?.message,
            suggestedOrigins = if (pending.sourceId == suggestedSourceId) {
                listOfNotNull(safeSuggestedCoverOrigin).filterNot { it in pending.requiredOrigins }
            } else emptyList(),
            selectedSuggestedOrigins = selectedSuggestedOrigins,
            onSuggestedOriginToggle = { origin ->
                selectedSuggestedOrigins = if (origin in selectedSuggestedOrigins) {
                    selectedSuggestedOrigins - origin
                } else {
                    selectedSuggestedOrigins + origin
                }
            },
            onDismiss = {
                val token = pending.reviewToken
                review = null
                selectedSuggestedOrigins = emptySet()
                scope.launch { manager.discardReview(token) }
            },
            onTrust = {
                val origins = pending.requiredOrigins + normalizedAdditionalOrigins.getOrThrow() + selectedSuggestedOrigins
                scope.launch {
                    runCatching { manager.installReviewed(pending.reviewToken, pending.sha256, origins) }
                        .onSuccess {
                            review = null
                            selectedSuggestedOrigins = emptySet()
                            error = "${pending.metadata.name} is installed. It can now be opened from novel sources."
                        }
                        .onFailure { failure ->
                            review = null
                            selectedSuggestedOrigins = emptySet()
                            runCatching { manager.discardReview(pending.reviewToken) }
                            error = failure.message
                        }
                }
            },
        )
    }
    uninstall?.let { (sourceId, name) -> AlertDialog(
        onDismissRequest = { uninstall = null },
        title = { Text("Uninstall $name?") },
        text = { Text("The extension and its private settings will be removed. Saved chapters, reading progress, and bookmarks remain in your library and can still be read offline.") },
        confirmButton = { TextButton(onClick = { uninstall = null; scope.launch { runCatching { manager.uninstall(sourceId) }.onFailure { error = it.message } } }) { Text("Uninstall") } },
        dismissButton = { TextButton(onClick = { uninstall = null }) { Text("Keep") } },
    ) }
    error?.let { message -> AlertDialog(
        onDismissRequest = { error = null }, title = { Text("Novel extensions") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("Done") } },
    ) }
}

@Composable private fun ExtensionMetadataCard(
    repository: String,
    metadata: LnReaderPluginMetadata,
    installed: mihon.readingapp.novels.lnreader.LnReaderInstalledPlugin?,
    canReview: Boolean,
    onReview: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onUninstall: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.Panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(metadata.name, color = Ink.Text, style = MaterialTheme.typography.titleMedium)
        Text("${metadata.language.ifBlank { "Language not listed" }} · ${metadata.version} · ${metadata.id}", color = Ink.Muted, fontSize = 11.sp)
        Text(metadata.site, color = Ink.Muted, fontSize = 11.sp)
        Text("Repository: $repository", color = Ink.Muted, fontSize = 10.sp)
        metadata.compatibilityError?.let { Text(it, color = Ink.Link, fontSize = 11.sp) }
        if (installed != null) {
            Text(if (installed.enabled) "Installed · ${installed.sha256.take(12)}" else "Disabled · saved chapters stay available", color = Ink.Link, fontSize = 11.sp)
            // Keep destructive and trust-changing actions in their own full-width targets.
            // This remains readable at large system font scales and avoids compressed actions.
            TextButton(onClick = { onEnabled(!installed.enabled) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (installed.enabled) "Disable extension" else "Enable extension")
            }
            TextButton(onClick = onReview, enabled = canReview, modifier = Modifier.fillMaxWidth()) {
                Text("Review update")
            }
            TextButton(onClick = onUninstall, modifier = Modifier.fillMaxWidth()) { Text("Remove extension") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Not installed", Modifier.weight(1f), color = Ink.Muted, fontSize = 11.sp)
                TextButton(onClick = onReview, enabled = metadata.compatibilityError == null) { Text("Review") }
            }
        }
        if (installed != null && !canReview) Text("No longer listed by this repository. Remove is available; updates require a listed current bundle.", color = Ink.Muted, fontSize = 10.sp)
    }
}

@Composable private fun ExtensionReviewDialog(
    review: LnReaderInstallReview,
    extraOrigins: String,
    onExtraOrigins: (String) -> Unit,
    normalizedAdditionalOrigins: Set<String>?,
    additionalOriginError: String?,
    suggestedOrigins: List<String>,
    selectedSuggestedOrigins: Set<String>,
    onSuggestedOriginToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onTrust: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (review.existingSha256 == null) "Review extension bundle" else "Review extension update") },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${review.metadata.name} · ${review.metadata.id} · ${review.metadata.version}")
            Text("Site: ${review.metadata.site}")
            Text("Repository bundle: ${review.metadata.bundleUrl}", fontSize = 11.sp)
            Text("Size: ${review.bundleSize} bytes\nSHA-256: ${review.sha256}", fontSize = 11.sp)
            if (review.existingSha256 != null) Text("Currently installed SHA-256: ${review.existingSha256}", fontSize = 11.sp)
            Text("Required public HTTPS origins", style = MaterialTheme.typography.titleSmall)
            review.requiredOrigins.sorted().forEach { Text("• $it", fontSize = 12.sp) }
            if (suggestedOrigins.isNotEmpty()) {
                Text("Observed blocked cover host", style = MaterialTheme.typography.titleSmall)
                suggestedOrigins.forEach { origin ->
                    val hostAndPort = origin.removePrefix("https://")
                    FilterChip(
                        selected = origin in selectedSuggestedOrigins,
                        onClick = { onSuggestedOriginToggle(origin) },
                        label = { Text("Add $hostAndPort") },
                    )
                }
                Text("Select this host only if you trust this extension to load images from it.", fontSize = 11.sp)
            }
            Text("Paste an HTTPS page or source URL. Only its exact host and port are added; HTTP cannot be trusted.", fontSize = 11.sp)
            OutlinedTextField(extraOrigins, onExtraOrigins, label = { Text("Additional HTTPS URLs (one per line)") }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4)
            if (additionalOriginError != null) {
                Text(additionalOriginError, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            } else if (!normalizedAdditionalOrigins.isNullOrEmpty()) {
                Text("Will add these exact origins:", style = MaterialTheme.typography.titleSmall)
                normalizedAdditionalOrigins.sorted().forEach { Text("• $it", fontSize = 12.sp) }
            }
            Text("No extension code ran while listing or staging this bundle. Trusting it allows its code to run only inside the isolated host with these origins.", fontSize = 11.sp)
        } },
        confirmButton = { TextButton(onClick = onTrust, enabled = normalizedAdditionalOrigins != null) { Text(if (review.existingSha256 == null) "Trust and install" else "Trust and update") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
