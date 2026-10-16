package mihon.feature.migration.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.core.screen.Screen
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.domain.migration.models.MigrationFlag
import mihon.domain.migration.usecases.MigrateMangaUseCase
import mihon.feature.common.utils.getLabel
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import mihon.readingapp.library.ChapterMappingPlan

@Composable
internal fun Screen.MigrateMangaDialog(
    current: Manga,
    target: Manga,
    onClickTitle: () -> Unit,
    onDismissRequest: () -> Unit,
    onComplete: () -> Unit = onDismissRequest,
) {
    val scope = rememberCoroutineScope()

    val viewModel = metroViewModel<MigrateDialogViewModel>()
    LaunchedEffect(current, target) {
        viewModel.init(current, target)
    }
    val state by viewModel.state.collectAsState()

    if (state.isMigrated) return

    if (state.isMigrating) {
        LoadingScreen(
            modifier = Modifier.background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f)),
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(MR.strings.migration_dialog_what_to_include))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                state.applicableFlags.fastForEach { flag ->
                    LabeledCheckbox(
                        label = stringResource(flag.getLabel()),
                        checked = flag in state.selectedFlags,
                        onCheckedChange = { viewModel.toggleSelection(flag) },
                    )
                }
                if(MigrationFlag.CHAPTER in state.selectedFlags) {
                    state.preview?.let {plan->
                        Text("${plan.verified.size} verified mappings · ${plan.ambiguous.size} candidates needing review · ${plan.unmatched} unmatched chapters")
                        Text("Only mapped chapters transfer read status and bookmarks. Page offsets between different editions remain unverified.",style=MaterialTheme.typography.bodySmall)
                        plan.ambiguous.take(100).forEach {match->LabeledCheckbox(
                            label="${match.previous.name} → ${match.target.name}",checked=match.key in state.reviewed,
                            onCheckedChange={viewModel.toggleMapping(match.key)},
                        )}
                        if(plan.ambiguous.size>100) Text("More than 100 ambiguous candidates. Unreviewed candidates will be skipped.")
                    } ?: Text("Checking chapter mappings…")
                }
                state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            }
        },
        confirmButton = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                TextButton(
                    onClick = {
                        onDismissRequest()
                        onClickTitle()
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_show_manga))
                }

                Spacer(modifier = Modifier.weight(1f))

                TextButton(
                    enabled=state.preview!=null && state.error==null,
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            if(viewModel.migrateManga(replace = false)) withContext(Dispatchers.Main) { onComplete() }
                        }
                    },
                ) {
                    Text(text = stringResource(MR.strings.copy))
                }
                TextButton(
                    enabled=state.preview!=null && state.error==null,
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            if(viewModel.migrateManga(replace = true)) withContext(Dispatchers.Main) { onComplete() }
                        }
                    },
                ) {
                    Text(text = stringResource(MR.strings.migrate))
                }
            }
        },
    )
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class MigrateDialogViewModel(
    private val sourcePreference: SourcePreferences,
    private val coverCache: CoverCache,
    private val downloadManager: DownloadManager,
    private val migrateManga: MigrateMangaUseCase,
) : ViewModel() {

    val state: StateFlow<MigrateDialogViewModel.State>
        field = MutableStateFlow<MigrateDialogViewModel.State>(State())

    fun init(current: Manga, target: Manga) {
        val applicableFlags = buildList {
            MigrationFlag.entries.forEach {
                val applicable = when (it) {
                    MigrationFlag.CHAPTER -> true
                    MigrationFlag.CATEGORY -> true
                    MigrationFlag.CUSTOM_COVER -> current.hasCustomCover(coverCache)
                    MigrationFlag.NOTES -> current.notes.isNotBlank()
                    MigrationFlag.REMOVE_DOWNLOAD -> downloadManager.getDownloadCount(current) > 0
                }
                if (applicable) add(it)
            }
        }
        val selectedFlags = sourcePreference.migrationFlags.get()
        state.update {
            State(
                current = current,
                target = target,
                applicableFlags = applicableFlags,
                selectedFlags = selectedFlags,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {migrateManga.preview(current,target)}.fold(
                {plan->state.update {it.copy(preview=plan)}},
                {problem->state.update {it.copy(error=problem.message ?: "Chapter mappings could not be checked.")}},
            )
        }
    }

    fun toggleSelection(flag: MigrationFlag) {
        state.update {
            val selectedFlags = it.selectedFlags.toMutableSet()
                .apply { if (contains(flag)) remove(flag) else add(flag) }
                .toSet()
            it.copy(selectedFlags = selectedFlags)
        }
    }

    fun toggleMapping(key:String) {state.update {it.copy(reviewed=if(key in it.reviewed) it.reviewed-key else it.reviewed+key,error=null)}}

    suspend fun migrateManga(replace: Boolean):Boolean {
        val currentState = state.value
        val current = currentState.current ?: return false
        val target = currentState.target ?: return false
        sourcePreference.migrationFlags.set(currentState.selectedFlags)
        state.update { it.copy(isMigrating = true) }
        return runCatching {migrateManga(current, target, replace,currentState.reviewed)}.fold(
            {state.update {it.copy(isMigrating=false,isMigrated=true)};true},
            {error->state.update {it.copy(isMigrating=false,error=error.message ?: "Migration did not complete.")};false},
        )
    }

    data class State(
        val current: Manga? = null,
        val target: Manga? = null,
        val applicableFlags: List<MigrationFlag> = emptyList(),
        val selectedFlags: Set<MigrationFlag> = emptySet(),
        val isMigrating: Boolean = false,
        val isMigrated: Boolean = false,
        val preview:ChapterMappingPlan?=null,
        val reviewed:Set<String> = emptySet(),
        val error:String?=null,
    )
}
