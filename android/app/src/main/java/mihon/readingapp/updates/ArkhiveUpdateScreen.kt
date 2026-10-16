package mihon.readingapp.updates

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.readingapp.design.Ink
import mihon.readingapp.settings.ArkhiveSettingsHeader
import mihon.readingapp.settings.InkSettingsTheme
import java.util.Locale

/** Internal dependency seam for self-authored UI/runtime fixtures; production uses the real transport. */
internal val LocalArkhiveUpdateService = staticCompositionLocalOf<ArkhiveUpdateService?> { null }

class ArkhiveUpdateScreen : Screen() {
    @Composable
    override fun Content() = InkSettingsTheme {
        val context = LocalContext.current
        val graph = context.appGraph
        val nav = LocalNavigator.currentOrThrow
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val testService = LocalArkhiveUpdateService.current
        val service = testService ?: remember(context.applicationContext, graph.networkHelper) {
            ArkhiveUpdateService(context.applicationContext, graph.networkHelper)
        }
        val versionName = remember(service) { service.installedVersionName() }
        var canInstall by remember(service) { mutableStateOf(service.canInstallUnknownSources()) }
        var includePreviews by remember { mutableStateOf(true) }
        var checked by remember { mutableStateOf(false) }
        var checking by remember { mutableStateOf(false) }
        var downloading by remember { mutableStateOf(false) }
        var releases by remember { mutableStateOf<List<ArkhiveRelease>>(emptyList()) }
        var selected by remember { mutableStateOf<ArkhiveRelease?>(null) }
        var prepared by remember { mutableStateOf<PreparedArkhiveUpdate?>(null) }
        var progress by remember { mutableFloatStateOf(0f) }
        var progressLabel by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var status by remember { mutableStateOf<String?>(null) }
        var operation by remember { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()
        val busy = operation?.isActive == true

        DisposableEffect(lifecycle, service) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) canInstall = service.canInstallUnknownSources()
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        val ownedOperation = operation
        DisposableEffect(ownedOperation) { onDispose { ownedOperation?.cancel() } }
        DisposableEffect(prepared) { val file = prepared?.file; onDispose { file?.delete() } }

        Column(Modifier.fillMaxSize().background(Ink.Canvas)) {
            ArkhiveSettingsHeader("Arkhive updates", { nav.pop(); Unit })
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .background(Ink.Panel, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Current version", color = Ink.Muted, fontSize = 12.sp)
                Text(versionName, color = Ink.Text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Updates are checked manually against Arkhive’s public GitHub releases. Downloads are verified against the published SHA-256 digest and the installed signing certificate before Android is asked to install them.",
                    color = Ink.Muted, fontSize = 12.sp, lineHeight = 18.sp,
                )
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Include preview releases", color = Ink.Text, fontSize = 14.sp)
                        Text("On by default for this 0.x preview app", color = Ink.Muted, fontSize = 11.sp)
                    }
                    Switch(includePreviews, onCheckedChange = {
                        prepared?.file?.delete(); prepared = null
                        includePreviews = it; checked = false; releases = emptyList(); selected = null; status = null; error = null
                    }, enabled = !busy)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            error = null; status = null; checked = false; releases = emptyList(); selected = null
                            prepared?.file?.delete(); prepared = null
                            operation = scope.launch {
                                checking = true
                                try {
                                    releases = service.check(includePreviews)
                                    selected = releases.firstOrNull()
                                    checked = true
                                    status = "Release information loaded from GitHub."
                                } catch (timeout: TimeoutCancellationException) {
                                    error = "GitHub did not respond within 45 seconds. Check your connection and retry."
                                } catch (cancelled: CancellationException) {
                                    status = "The update check was canceled."
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: "GitHub could not be reached. Try again when you are online."
                                } finally {
                                    checking = false
                                    operation = null
                                }
                            }
                        },
                    ) { Text(if (checking) "Checking…" else "Check for updates") }
                    if (busy) {
                        OutlinedButton(onClick = { operation?.cancel() }) { Text("Cancel") }
                    }
                }
                if (checking) CircularProgressIndicator()
                status?.let { Text(it, color = Ink.Muted, fontSize = 12.sp) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }

                if (releases.isNotEmpty()) {
                    Text("Available releases", color = Ink.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    releases.forEach { release ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.Raised).selectable(
                                selected = selected?.tag == release.tag,
                                role = Role.RadioButton,
                                onClick = { selected = release; prepared?.file?.delete(); prepared = null; error = null; status = null },
                                enabled = !busy,
                            ).padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(release.title, color = Ink.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("${release.tag} · ${if (release.preview) "Preview" else "Stable"}", color = Ink.Muted, fontSize = 11.sp)
                                Text(release.apkName, color = Ink.Muted, fontSize = 11.sp)
                            }
                            RadioButton(selected = selected?.tag == release.tag, onClick = null)
                        }
                    }
                    selected?.let { release ->
                        Text("Release notes", color = Ink.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(release.changelog.ifBlank { "This release has no changelog." }, color = Ink.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                        Text("APK size: ${String.format(Locale.ROOT, "%.1f", release.apkSize / (1024f * 1024f))} MiB · SHA-256 required", color = Ink.Muted, fontSize = 11.sp)
                        Button(
                            enabled = !busy && prepared == null,
                            onClick = {
                                error = null; status = null; progress = 0f; progressLabel = ""
                                operation = scope.launch {
                                    downloading = true
                                    try {
                                        prepared = service.prepare(release) { done, total ->
                                            scope.launch {
                                                progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                                                progressLabel = "${done / (1024 * 1024)} / ${total / (1024 * 1024)} MiB downloaded"
                                            }
                                        }
                                        status = "SHA-256, package name, version and signing certificate verified."
                                    } catch (cancelled: CancellationException) {
                                        status = "The download was canceled and its partial file was removed."
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "The update download or verification failed."
                                    } finally {
                                        downloading = false
                                        operation = null
                                    }
                                }
                            },
                        ) { Text(if (downloading) "Downloading…" else "Download and verify") }
                        if (downloading) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            Text(progressLabel, color = Ink.Muted, fontSize = 11.sp)
                        }
                    }
                } else if (checked && error == null) {
                    Text("Checked ${if (includePreviews) "preview and stable" else "stable"} releases. The installed version is already current.", color = Ink.Muted, fontSize = 12.sp)
                }

                prepared?.let { update ->
                    Text("Verified ${update.release.apkName}", color = Ink.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("SHA-256 ${update.sha256}", color = Ink.Muted, fontSize = 10.sp, lineHeight = 14.sp)
                    if (canInstall) {
                        Button(enabled = !busy, onClick = {
                            error = null
                            operation = scope.launch {
                                try {
                                    status = "Rechecking the verified APK…"
                                    val intent = service.installerIntent(update)
                                    withContext(Dispatchers.Main.immediate) { context.startActivity(intent) }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: "The verified update could not be handed to Android."
                                } finally {
                                    status = null
                                    operation = null
                                }
                            }
                        }) { Text(if (busy) "Verifying…" else "Install verified update") }
                    } else {
                        Text("Android requires Arkhive’s package-install permission before continuing. This setting remains under your control.", color = Ink.Muted, fontSize = 12.sp)
                        OutlinedButton(enabled = !busy, onClick = {
                            runCatching { context.startActivity(service.unknownSourcesSettingsIntent()) }
                                .onFailure { error = it.message ?: "Android settings could not be opened." }
                        }) { Text("Open Android install permission") }
                    }
                    TextButton(enabled = !busy, onClick = { prepared?.file?.delete(); prepared = null; status = "The verified download was discarded." }) { Text("Discard download") }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

