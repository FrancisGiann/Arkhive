package mihon.readingapp.discovery

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import mihon.app.di.appGraph
import mihon.domain.discovery.*
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Search
import mihon.readingapp.design.*
import mihon.readingapp.settings.InkSettingsTheme

object StoreSelection { var isbn by mutableStateOf("") }
private data class PriceRequest(val serial: Int, val isbn: String, val country: String)

/** Title search returns catalogue matches; only verified store fields become prices/offers. */
@Composable fun RetailLookupContent() = InkSettingsTheme {
    val graph = LocalContext.current.appGraph
    val uri = LocalUriHandler.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val pager = remember { CatalogPager(scope) { query, page, force -> graph.publicDiscoveryRepository.catalog(query, force, page) } }
    val paging by pager.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(StoreSelection.isbn) }
    var country by rememberSaveable { mutableStateOf(java.util.Locale.getDefault().country.uppercase()) }
    var submitted by rememberSaveable { mutableStateOf("") }
    var request by remember { mutableStateOf<PriceRequest?>(null) }
    var serial by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    var lookup by remember { mutableStateOf<StoreLookup?>(null) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var regionOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CatalogTitle?>(null) }
    var editions by remember { mutableStateOf<List<CatalogEdition>?>(null) }
    var editionError by remember { mutableStateOf<String?>(null) }
    var editionAttempt by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    fun price(isbn: String) {
        lookup = null; inputError = null
        request = PriceRequest(++serial, isbn, country)
    }
    fun search(value: String = text) {
        val q = value.trim(); focus.clearFocus(); request = null; lookup = null; inputError = null; selected = null
        if (q.isBlank()) { inputError = "Enter a title, author or ISBN."; return }
        val isbn = DiscoveryDataPolicy.validIsbn(q)
        if (isbn != null) { pager.pause(); submitted = ""; price(isbn); return }
        if (q.all { it.isDigit() || it.isWhitespace() || it == '-' || it == 'X' || it == 'x' }) {
            inputError = "That ISBN is not valid. Check its digits and checksum."; submitted = ""; return
        }
        submitted = q
        pager.select(CatalogQuery(focus = CatalogFocus.ALL, search = q), force = true)
    }
    LaunchedEffect(StoreSelection.isbn) { if (StoreSelection.isbn.isNotBlank()) { text = StoreSelection.isbn; search(text) } }
    LaunchedEffect(request) {
        val target = request
        if (target == null) { checking = false; return@LaunchedEffect }
        checking = true
        try {
            val result = graph.publicDiscoveryRepository.store(target.isbn, target.country)
            if (request == target) lookup = result
        } finally { if (request == target) checking = false }
    }
    LaunchedEffect(selected?.key, editionAttempt) {
        val title = selected ?: return@LaunchedEffect
        editions = null; editionError = null
        graph.publicDiscoveryRepository.editions(title).fold({ editions = it }, { editionError = it.message ?: "Editions could not be loaded." })
    }
    DisposableEffect(pager) { onDispose { pager.pause() } }
    val nearEnd by remember { derivedStateOf { list.layoutInfo.let { info ->
        info.totalItemsCount > 1 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 5
    } } }
    LaunchedEffect(nearEnd, paging.nextPage, paging.loading, paging.error, submitted) {
        if (nearEnd && submitted.isNotBlank() && paging.titles.isNotEmpty() && !paging.loading && paging.error == null) pager.loadNext()
    }
    LazyColumn(Modifier.fillMaxSize().background(Ink.Canvas).statusBarsPadding(), state = list,
        contentPadding = PaddingValues(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            InkKicker("Find your edition")
            InkHeading("Buy", Modifier.padding(top = 8.dp, bottom = 16.dp))
            OutlinedTextField(text, { value -> text = value.take(120); inputError = null; request = null; lookup = null; submitted = ""; pager.pause() },
                placeholder = { Text("Title, author or ISBN") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                trailingIcon = { IconButton(onClick = { search() }, enabled = text.isNotBlank()) { Icon(MaterialSymbols.Rounded.Search, "Search editions") } })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Edition search · Open Library", color = Ink.Muted, fontSize = 11.sp)
                TextButton(onClick = { regionOpen = true }) { Text(if (country in java.util.Locale.getISOCountries()) "Region $country" else "Choose region") }
            }
            if (checking || (paging.loading && submitted.isNotBlank())) LinearProgressIndicator(Modifier.fillMaxWidth())
            inputError?.let { Text(it, color = Ink.Muted) }
        }
        lookup?.let { result ->
            item {
                InkKicker("Edition check")
                result.message?.let { Text(it, color = Ink.Text, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp)) }
                Text("Lookup attempted ${timeLabel(result.checkedAt)} · ${result.country.ifBlank { "region not selected" }}", color = Ink.Muted, fontSize = 11.sp)
                if (result.providerFailed || result.state == StoreLookupState.PROVIDER_UNAVAILABLE) TextButton(onClick = { request?.let { price(it.isbn) } }) { Text("Retry provider") }
                if (result.state == StoreLookupState.INVALID_INPUT) TextButton(onClick = { regionOpen = true }) { Text("Choose region") }
                if (result.state == StoreLookupState.EMPTY) Text("No exact edition was returned by these providers. Try a title search.", color = Ink.Muted)
            }
            result.catalogue?.let { book -> item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        book.coverUrl?.let { AsyncImage(it, null, modifier = Modifier.width(60.dp).height(86.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop) }
                        Column(Modifier.weight(1f)) {
                            Text(book.title, color = Ink.Text, style = MaterialTheme.typography.titleMedium)
                            Text(book.authors.joinToString(), color = Ink.Muted, fontSize = 12.sp)
                            Text("Open Library · catalogue record", color = Ink.Link, fontSize = 11.sp)
                        }
                    }
                    Text(listOfNotNull(book.publishers.joinToString().ifBlank { null }, book.published).joinToString(" · "), color = Ink.Muted, fontSize = 12.sp)
                    Text("Price and store stock are not supplied by this catalogue.", color = Ink.Muted, fontSize = 12.sp)
                    result.catalogueUrl?.let { url -> TextButton(onClick = { uri.openUri(url) }) { Text("View catalogue edition") } }
                }
            } }
            items(result.offers, key = { listOf(it.provider, it.isbn, it.editionTitle, it.country, it.amount.orEmpty(), it.currency.orEmpty()).joinToString("|") }) { offer ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(offer.editionTitle, color = Ink.Text, style = MaterialTheme.typography.titleMedium)
                    Text("${offer.provider} · ISBN ${offer.isbn}", color = Ink.Muted, fontSize = 11.sp)
                    Text(when (offer.availability) {
                        "FOR_SALE" -> "Provider marks this edition for sale in ${offer.country}"; "FOR_PREORDER" -> "Provider marks this edition available for preorder"
                        "FREE" -> "Provider marks this edition free"; "NOT_FOR_SALE" -> "Not for sale in ${offer.country}"
                        "REGION_UNCONFIRMED" -> "Requested region is not confirmed${offer.providerCountry?.let { "; provider returned $it" }.orEmpty()}"
                        else -> "Store availability not returned"
                    }, color = Ink.Muted, fontSize = 12.sp)
                    Text(if (offer.amount != null && offer.currency != null) "${offer.amount} ${offer.currency}" else "Price unavailable", color = Ink.Text, style = MaterialTheme.typography.titleLarge)
                    Text("Source checked ${timeLabel(offer.fetchedAt)}. Physical stock and shipping are not connected.", color = Ink.Muted, fontSize = 11.sp)
                    offer.buyUrl?.let { url -> TextButton(onClick = { uri.openUri(url) }) { Text("View Google Books") } }
                }
            }
        }
        if (submitted.isNotBlank()) {
            item {
                InkKicker("Matches for $submitted")
                paging.result?.feed?.provenance?.let { Text("Open Library · checked ${timeLabel(it.fetchedAt)} · provider relevance", color = Ink.Muted, fontSize = 11.sp) }
            }
            if (paging.titles.isEmpty() && !paging.loading) item {
                if (paging.error != null) { Text(paging.error!!, color = Ink.Muted); TextButton(onClick = pager::retry) { Text("Retry catalogue") } }
                else Text("No catalogue matches for this query. Try fewer words, the author, or an ISBN.", color = Ink.Muted)
            }
            items(paging.titles, key = { it.key }) { title ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.Panel).inkPress { selected = title; editionAttempt = 0 }
                    .padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(60.dp).height(86.dp).clip(RoundedCornerShape(5.dp)).background(Ink.Raised)) {
                        title.coverId?.let { AsyncImage("https://covers.openlibrary.org/b/id/$it-M.jpg?default=false", null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(title.title, color = Ink.Text, style = MaterialTheme.typography.titleSmall)
                        Text(title.authors.joinToString().ifBlank { "Author not returned" }, color = Ink.Muted, fontSize = 11.sp)
                        Text("Choose edition · price not checked", color = Ink.Link, fontSize = 11.sp)
                    }
                    Text("›", color = Ink.Muted, fontSize = 22.sp)
                }
            }
            if (paging.titles.isNotEmpty()) item {
                when {
                    paging.loading -> Text("Loading more editions…", color = Ink.Muted)
                    paging.error != null -> { Text(paging.error!!, color = Ink.Muted); TextButton(onClick = pager::retry) { Text("Retry next page") } }
                    paging.nextPage != null -> TextButton(onClick = { pager.loadNext() }) { Text("Load more") }
                    else -> Text(paging.endReason.orEmpty(), color = Ink.Muted, fontSize = 11.sp)
                }
            }
        }
        item { Text("Read in Library and Sources. Buying is optional; only returned provider prices are shown.", color = Ink.Muted, fontSize = 11.sp, lineHeight = 16.sp) }
    }
    if (regionOpen) {
        var draft by remember { mutableStateOf(country) }
        AlertDialog(onDismissRequest = { regionOpen = false }, title = { Text("Store region") }, text = {
            OutlinedTextField(draft, { draft = it.uppercase().filter(Char::isLetter).take(2) }, label = { Text("Country code") },
                supportingText = { Text("US, GB, CA, PH or another valid two-letter country code. Availability can differ by region.") }, singleLine = true)
        }, confirmButton = { TextButton(enabled = draft in java.util.Locale.getISOCountries(), onClick = {
            country = draft; regionOpen = false; request?.let { price(it.isbn) }
        }) { Text("Apply") } }, dismissButton = { TextButton(onClick = { regionOpen = false }) { Text("Cancel") } })
    }
    selected?.let { title -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("Editions · ${title.title}") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (editions == null && editionError == null) CircularProgressIndicator()
            editionError?.let { Text(it); TextButton(onClick = { editionAttempt++ }) { Text("Retry editions") } }
            if (editions?.isEmpty() == true) Text("This catalogue returned no edition metadata.")
            editions.orEmpty().forEach { edition ->
                Text(edition.title, style = MaterialTheme.typography.titleSmall)
                Text(listOfNotNull(edition.publishers.joinToString().ifBlank { null }, edition.published, edition.format).joinToString(" · "), fontSize = 12.sp)
                if (edition.isbn.isEmpty()) Text("No ISBN returned for this edition; no price lookup is available.", fontSize = 12.sp)
                edition.isbn.take(2).forEach { isbn -> TextButton(onClick = { selected = null; price(isbn) }) { Text("Check ISBN $isbn") } }
            }
            TextButton(onClick = { uri.openUri("https://openlibrary.org${title.key}") }) { Text("View provider details") }
        }
    }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } }) }
}
