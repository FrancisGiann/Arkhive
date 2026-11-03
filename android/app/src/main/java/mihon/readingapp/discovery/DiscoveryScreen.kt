package mihon.readingapp.discovery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import mihon.app.di.appGraph
import mihon.readingapp.design.*
import mihon.domain.discovery.*
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.rounded.Search
import mihon.readingapp.fixtures.OriginalDemoSource
import mihon.readingapp.navigation.BuyTab
import tachiyomi.i18n.MR


data class MangaRelatedDiscoveryScreen(
    val originTitle: String,
    val focusName: String,
    val genreName: String,
    val provenance: String,
    val mediumName: String = CatalogMedium.ALL_BOOKS.name,
) : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val graph = LocalContext.current.appGraph
        val scope = rememberCoroutineScope()
        val pager = remember(graph, scope) {
            CatalogPager(scope) { query, page, force -> graph.publicDiscoveryRepository.catalog(query, force, page) }
        }
        val query = CatalogQuery(
            focus = runCatching { CatalogFocus.valueOf(focusName) }.getOrDefault(CatalogFocus.MANGA),
            genre = runCatching { CatalogGenre.valueOf(genreName) }.getOrDefault(CatalogGenre.ANY),
            window = CatalogWindow.SUBJECT,
            search = "",
            medium = runCatching { CatalogMedium.valueOf(mediumName) }.getOrDefault(CatalogMedium.ALL_BOOKS),
        )
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Discover by category") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack, contentDescription = "Back to $originTitle")
                        }
                    },
                )
            },
        ) { padding ->
            DiscoveryContent(
                modifier = Modifier.padding(padding),
                active = true,
                initialQuery = query,
                contextTitle = originTitle,
                contextProvenance = provenance,
                pagerOverride = pager,
                onOpenBuy = { isbn -> StoreSelection.isbn = isbn; navigator.push(mihon.readingapp.navigation.BuyTab) },
            )
        }
    }
}

@Composable fun Screen.discoveryTab(active: Boolean): TabContent = TabContent(titleRes = MR.strings.arkhive_discover,
    content = { padding, _ -> DiscoveryContent(Modifier.padding(padding), active) })

@OptIn(ExperimentalLayoutApi::class)
@Composable fun DiscoveryContent(modifier: Modifier = Modifier, active: Boolean = true, initialQuery: CatalogQuery? = null, contextTitle: String? = null, contextProvenance: String? = null, pagerOverride: CatalogPager? = null, onOpenBuy: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val graph = context.appGraph
    val navigator = LocalNavigator.currentOrThrow
    val tab = if (onOpenBuy == null) LocalTabNavigator.current else null
    val uri = LocalUriHandler.current
    val focusManager = LocalFocusManager.current
    val pager = pagerOverride ?: graph.publicDiscoveryRepository.browsing
    val paging by pager.state.collectAsStateWithLifecycle()
    val startingQuery = CatalogFilterPolicy.normalize(initialQuery ?: paging.query)
    var focus by rememberSaveable(initialQuery) { mutableStateOf(startingQuery.focus) }
    var genre by rememberSaveable(initialQuery) { mutableStateOf(startingQuery.genre) }
    var window by rememberSaveable(initialQuery) { mutableStateOf(startingQuery.window) }
    var medium by rememberSaveable(initialQuery) { mutableStateOf(startingQuery.medium) }
    var text by rememberSaveable(initialQuery) { mutableStateOf(startingQuery.search) }
    var submitted by rememberSaveable(initialQuery) { mutableStateOf(startingQuery.search) }
    var refresh by remember { mutableIntStateOf(0) }
    var consumedRefresh by rememberSaveable { mutableIntStateOf(0) }
    var editionRequest by remember { mutableIntStateOf(0) }
    val loading = paging.loading
    val result = paging.result
    val gridState = rememberLazyGridState(pager.scrollIndex, pager.scrollOffset)
    var sources by remember { mutableStateOf(emptyList<HttpSource>()) }
    var selected by remember { mutableStateOf<CatalogTitle?>(null) }
    var filtersOpen by remember { mutableStateOf(false) }
    var sourceInfoOpen by remember { mutableStateOf(false) }
    var editions by remember { mutableStateOf<List<CatalogEdition>?>(null) }
    var editionError by remember { mutableStateOf<String?>(null) }
    var findingEditions by remember { mutableStateOf(false) }
    val query = CatalogFilterPolicy.normalize(CatalogQuery(focus, genre, window, submitted, medium))
    LaunchedEffect(active, query, refresh) {
        if (!active) { pager.pause(); return@LaunchedEffect }
        if (focus != query.focus) focus = query.focus
        if (genre != query.genre) genre = query.genre
        if (window != query.window) window = query.window
        if (medium != query.medium) medium = query.medium
        if (submitted != query.search) submitted = query.search
        val changed = query != paging.query
        val force = refresh != consumedRefresh
        consumedRefresh = refresh
        pager.select(query, force)
        if (changed || force) gridState.scrollToItem(0)
    }
    DisposableEffect(pager) { onDispose { pager.pause() } }
    LaunchedEffect(gridState, pager) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged().collect { (index, offset) -> pager.scrollIndex = index; pager.scrollOffset = offset }
    }
    val nearEnd by remember { derivedStateOf { gridState.layoutInfo.let { info ->
        info.totalItemsCount > 1 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 8
    } } }
    LaunchedEffect(active, nearEnd, paging.nextPage, paging.loading, paging.error) {
        if (active && nearEnd && paging.titles.isNotEmpty() && !paging.loading && paging.error == null) pager.loadNext()
    }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val allowed = graph.sourcePreferences.enabledLanguages.get()
        val hidden = graph.sourcePreferences.disabledSources.get()
        sources = graph.sourceManager.getOnlineSources().filter { it.id.toString() !in hidden && it.lang in allowed }.sortedBy { it.name }
    }
    LaunchedEffect(selected?.key, editionRequest) {
        val title = selected ?: return@LaunchedEffect
        if (editionRequest == 0) return@LaunchedEffect
        findingEditions = true
        editions = null
        editionError = null
        try { graph.publicDiscoveryRepository.editions(title).fold({ editions = it }, { editionError = it.message }) }
        finally { findingEditions = false }
    }
    val feed = result?.feed
    val hiddenSeries by graph.libraryVisibility.hiddenSeries.changes().collectAsStateWithLifecycle(graph.libraryVisibility.hiddenSeries.get())
    var concealedTitles by remember {mutableStateOf(emptySet<String>())}
    var concealedResolvedFor by remember {mutableStateOf<Set<String>?>(null)}
    LaunchedEffect(hiddenSeries) {
        concealedResolvedFor = null
        concealedTitles=hiddenSeries.mapNotNull {key->runCatching {graph.seriesToolsRepository.get(key).title.trim().lowercase()}.getOrNull()}.toSet()
        concealedResolvedFor = hiddenSeries
    }
    // Do not render the default discovery feed against an old or still-loading hidden identity set.
    // Explicit searches are intentionally unaffected by library hiding.
    val hiddenIdentitySetReady = submitted.isNotBlank() || concealedResolvedFor == hiddenSeries
    val titles = if (hiddenIdentitySetReady) feed?.titles.orEmpty().filterNot {
        submitted.isBlank() && it.title.trim().lowercase() in concealedTitles
    } else emptyList()
    val submitSearch: () -> Unit = {
        val next = text.trim()
        window = CatalogWindow.SUBJECT
        if (next == submitted) refresh++ else submitted = next
        focusManager.clearFocus()
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(105.dp), state = gridState,
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                if (contextTitle != null) Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Text("From $contextTitle", color = Ink.Text, style = MaterialTheme.typography.titleMedium)
                    Text(contextProvenance.orEmpty(), color = Ink.Muted, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(text, { text = it.take(120) }, placeholder = { Text("Search stories") }, singleLine = true,
                        modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                        trailingIcon = { IconButton(onClick = submitSearch, enabled = !loading) { Icon(MaterialSymbols.Rounded.Search, "Search stories") } })
                    Spacer(Modifier.width(8.dp))
                    InkPill("Filters") { focusManager.clearFocus(); filtersOpen = true }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(CatalogFilterPolicy.appliedSummary(query), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { sourceInfoOpen = true }) { Text("Source") }
                }
                if (result?.stale == true) Text("Saved results · updates unavailable", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val real = sources.filterNot { it is OriginalDemoSource }
                if (real.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    real.take(8).forEach { source -> AssistChip(onClick = { navigator.push(BrowseSourceScreen(source.id, null)) }, label = { Text(source.name) }) }
                }
            }
        }
        if (titles.isEmpty() && !loading) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (feed != null) "No stories found." else "Discovery is unavailable right now.")
                TextButton(onClick = { refresh++ }) { Text("Try again") }
            }
        }
        items(titles, key = { it.key }) { title ->
            Column(Modifier.fillMaxWidth().inkPress { selected = title; editionRequest = 0; findingEditions = false; editions = null; editionError = null }) {
                Box(Modifier.fillMaxWidth().aspectRatio(.68f).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(contentAlignment = Alignment.Center) { Text(title.title, Modifier.padding(12.dp), maxLines = 5) }
                    }
                    if (title.coverId != null) AsyncImage("https://covers.openlibrary.org/b/id/${title.coverId}-M.jpg?default=false",
                        "Cover for ${title.title}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Text(title.title, Modifier.padding(8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(title.authors.joinToString().ifBlank { "Author not returned" }, Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (titles.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    loading -> { CircularProgressIndicator(Modifier.size(24.dp)); Text("Loading more…", color = Ink.Muted) }
                    paging.error != null -> { Text(paging.error!!, color = Ink.Muted); TextButton(onClick = pager::retry) { Text("Retry next page") } }
                    paging.nextPage == null -> Text(paging.endReason.orEmpty(), color = Ink.Muted)
                    else -> TextButton(onClick = { pager.loadNext() }) { Text("Load more") }
                }
            }
        }

    }
    if (filtersOpen) {
        var editor by remember { mutableStateOf(CatalogFilterPolicy.beginDraft(query)) }
        val draft = editor.query
        val dialogSurface = MaterialTheme.colorScheme.surfaceContainerHigh
        val dialogText = inkReadableForeground(Ink.Text, dialogSurface)
        val dialogMuted = inkReadableForeground(Ink.Muted, dialogSurface)
        val dialogLink = inkReadableForeground(Ink.Link, dialogSurface)
        AlertDialog(
            onDismissRequest = { filtersOpen = false },
            containerColor = dialogSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Filters", Modifier.weight(1f))
                    TextButton(
                        onClick = { editor = CatalogFilterPolicy.resetDraft(editor) },
                        colors = ButtonDefaults.textButtonColors(contentColor = dialogText),
                    ) { Text("Reset") }
                }
            },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val savedFacetSummary = CatalogFilterPolicy.savedSubjectFacetSummary(draft)
                    Text(
                        if (draft.window == CatalogWindow.SUBJECT) {
                            "Selected: Genres · $savedFacetSummary"
                        } else {
                            "Selected: ${draft.window.label} trends · saved for Genres: $savedFacetSummary"
                        },
                        color = dialogText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Swipe each filter row to see more choices.",
                        color = dialogMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FilterOptionGroup("Feed", dialogSurface) {
                        CatalogWindow.entries.forEach { choice ->
                            FilterChip(
                                selected = draft.window == choice,
                                onClick = {
                                    editor = CatalogFilterPolicy.draftWindow(editor, choice, text.trim())
                                },
                                enabled = CatalogFilterPolicy.canSelectWindow(choice, text.trim()),
                                label = { Text(choice.label) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                    if (draft.window == CatalogWindow.SUBJECT) {
                        FilterOptionGroup("Category", dialogSurface) {
                            CatalogMedium.entries.forEach { choice ->
                                FilterChip(
                                    selected = draft.medium == choice,
                                    onClick = {
                                        editor = CatalogFilterPolicy.draftMedium(editor, choice)
                                    },
                                    label = { Text(choice.label) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                        FilterOptionGroup("Type", dialogSurface) {
                            FilterChip(
                                selected = draft.focus == CatalogFocus.ALL || draft.focus == CatalogFocus.COMICS,
                                onClick = { editor = CatalogFilterPolicy.draftFocus(editor, CatalogFocus.ALL) },
                                label = { Text("Any type") },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                            CatalogFocus.entries.filterNot { it == CatalogFocus.ALL || it == CatalogFocus.COMICS }.forEach { choice ->
                                FilterChip(
                                    selected = draft.focus == choice,
                                    onClick = { editor = CatalogFilterPolicy.draftFocus(editor, choice) },
                                    enabled = CatalogFilterPolicy.canSelectFocus(draft.medium, choice),
                                    label = { Text(choice.label) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                        FilterOptionGroup("Genre", dialogSurface) {
                            CatalogGenre.entries.forEach { choice ->
                                FilterChip(
                                    selected = draft.genre == choice,
                                    onClick = { editor = CatalogFilterPolicy.draftGenre(editor, choice) },
                                    label = { Text(choice.label) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                    } else {
                        Text(
                            "Trending rankings cover all categories. Type and genre choices stay saved but are not applied; selecting Genres restores them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = dialogMuted,
                        )
                        if (text.trim().isNotEmpty()) {
                            Text(
                                "A title search cannot be combined with trends. Applying the search switches this query to Genres.",
                                style = MaterialTheme.typography.bodySmall,
                                color = dialogLink,
                            )
                        }
                    }
                    editor.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = dialogLink) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val applied = CatalogFilterPolicy.applyDraft(editor, text)
                    focus = applied.focus
                    genre = applied.genre
                    window = applied.window
                    medium = applied.medium
                    submitted = applied.search
                    filtersOpen = false
                }, colors = ButtonDefaults.textButtonColors(contentColor = dialogText)) { Text("Apply") }
            },
            dismissButton = {
                TextButton(
                    onClick = { editor = CatalogFilterPolicy.cancelDraft(editor); filtersOpen = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = dialogText),
                ) { Text("Cancel") }
            },
        )
    }
    if (sourceInfoOpen) AlertDialog(onDismissRequest = { sourceInfoOpen = false }, title = { Text("Open Library") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            feed?.provenance?.let { provenance ->
                Text("Checked ${timeLabel(provenance.fetchedAt)}${if (result?.cached == true) " · saved copy" else ""}")
                Text(provenance.basis, style = MaterialTheme.typography.bodySmall)
                Text("${paging.pages.size} pages · ${titles.size} unique titles", style = MaterialTheme.typography.bodySmall)
                paging.pages.takeLast(3).forEach { page ->
                    Text("Page ${page.page} checked ${timeLabel(page.provenance.fetchedAt)}", style = MaterialTheme.typography.bodySmall)
                }
            } ?: Text("No provider feed is available for this selection.")
            result?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Catalog listings. Read through your sources or local imports.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uri.openUri("https://openlibrary.org") }) { Text("Visit provider") }
        }
    }, confirmButton = { TextButton(onClick = { sourceInfoOpen = false; refresh++ }, enabled = !loading) { Text("Refresh") } },
        dismissButton = { TextButton(onClick = { sourceInfoOpen = false }) { Text("Close") } })
    selected?.let { title -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(title.title) }, text = {
        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
            Text(title.authors.joinToString().ifBlank { "Author not returned" })
            title.firstYear?.let { Text("First publication: $it", style = MaterialTheme.typography.bodySmall) }
            if (title.subjects.isNotEmpty()) Text(title.subjects.take(8).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            Text("Open Library listing. Read through your sources or local imports.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            TextButton(onClick = { navigator.push(GlobalSearchScreen(title.title)); selected = null }) { Text("Find in my sources") }
            TextButton(onClick = { uri.openUri("https://openlibrary.org${title.key}") }) { Text("Provider details") }
            TextButton(enabled = !findingEditions, onClick = { editionRequest++ }) { Text(if (findingEditions) "Checking editions…" else "Editions & buying options") }
            editionError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            editions?.let { list ->
                if (list.isEmpty()) Text("No edition metadata was returned.")
                list.forEach { edition ->
                    Text(edition.title, style = MaterialTheme.typography.titleSmall)
                    Text(listOfNotNull(edition.publishers.joinToString().ifBlank { null }, edition.published, edition.format).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    if (edition.isbn.isEmpty()) Text("No valid ISBN was returned", style = MaterialTheme.typography.bodySmall)
                    edition.isbn.take(2).forEach { isbn -> TextButton(onClick = {
                        if (onOpenBuy != null) onOpenBuy(isbn) else { StoreSelection.isbn = isbn; selected = null; tab?.current = BuyTab }
                    }) { Text("Check ISBN $isbn") } }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } }) }
}

internal fun timeLabel(time: Long): String = java.text.SimpleDateFormat("MMM d, HH:mm 'UTC'", java.util.Locale.US)
    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(time))

@Composable
private fun FilterOptionGroup(label: String, surface: Color, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.widthIn(min = 84.dp, max = 96.dp).semantics { heading() },
            color = inkReadableForeground(Ink.Text, surface),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
        )
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
