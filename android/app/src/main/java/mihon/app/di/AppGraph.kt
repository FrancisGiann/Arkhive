package mihon.app.di

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.service.DelayedTrackingUpdateWorker
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.backup.create.BackupCreateWorker
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreWorker
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadWorker
import eu.kanade.tachiyomi.data.library.LibraryUpdateWorker
import eu.kanade.tachiyomi.data.library.MetadataUpdateWorker
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.util.ExtensionInstallActivity
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegateImpl
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.setting.track.BaseOAuthLoginActivity
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.CrashLogUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.core.metro.IsDebugBuild
import mihon.domain.extension.interactor.GetExtensionStoreCountAsFlow
import mihon.domain.extension.repository.ExtensionStoreRepository
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.ResetCategoryFlags
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.ResetViewerFlags
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.track.interactor.UpsertTrack

@DependencyGraph(
    scope = AppScope::class,
    bindingContainers = [AppBindings::class],
)
interface AppGraph : ViewModelGraph {
    fun inject(app: App)
    fun inject(mainActivity: MainActivity)
    fun inject(readerActivity: ReaderActivity)
    fun inject(webViewActivity: WebViewActivity)
    fun inject(baseOAuthLoginActivity: BaseOAuthLoginActivity)
    fun inject(libraryUpdateWorker: LibraryUpdateWorker)
    fun inject(metadataUpdateWorker: MetadataUpdateWorker)
    fun inject(backupRestoreWorker: BackupRestoreWorker)
    fun inject(backupCreateWorker: BackupCreateWorker)
    fun inject(delayedTrackingUpdateWorker: DelayedTrackingUpdateWorker)
    fun inject(downloadWorker: DownloadWorker)
    fun inject(notificationReceiver: NotificationReceiver)
    fun inject(notificationReceiver: SecureActivityDelegateImpl)
    fun inject(extensionInstallActivity: ExtensionInstallActivity)

    val bookProgressWriter: mihon.readingapp.books.BookProgressWriter

    val localBookImporter: mihon.readingapp.books.LocalBookImporter

    val bookImportCoordinator: mihon.readingapp.books.BookImportCoordinator

    val localBookRepository: mihon.domain.book.LocalBookRepository
    val unifiedLocalBackup: mihon.readingapp.backup.UnifiedLocalBackupService
    val mangaBackupCreator: eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
    val categoriesBackupCreator: eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
    val mangaRestorer: eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
    val categoriesRestorer: eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer

    val libraryShelfMembership: mihon.readingapp.library.LibraryShelfMembership

    val libraryToolsStore: mihon.readingapp.library.LibraryToolsStore
    val smartOfflineStore: mihon.readingapp.library.SmartOfflineStore
    val smartOfflineDispatcher: mihon.readingapp.library.SmartOfflineDispatcher
    val readerForegroundSignal: mihon.readingapp.library.ReaderForegroundSignal
    val libraryVisibility: tachiyomi.domain.library.service.LibraryVisibility
    val seriesToolsRepository: mihon.readingapp.library.SeriesToolsRepository
    val chapterRepository: tachiyomi.domain.chapter.repository.ChapterRepository
    val getUpdates: tachiyomi.domain.updates.interactor.GetUpdates

    val novelRepository: mihon.readingapp.novels.NovelRepository

    val publicDiscoveryRepository: mihon.readingapp.discovery.PublicDiscoveryRepository

    val context: Context

    val viewModelFactory: MetroViewModelFactory

    val basePreferences: BasePreferences
    val uiPreferences: UiPreferences
    val readerPreferences: ReaderPreferences
    val networkPreferences: NetworkPreferences
    val libraryPreferences: LibraryPreferences
    val sourcePreferences: SourcePreferences
    val trackPreferences: TrackPreferences
    val backupPreferences: BackupPreferences
    val storagePreferences: StoragePreferences
    val privacyPreferences: PrivacyPreferences
    val securityPreferences: SecurityPreferences
    val downloadPreferences: DownloadPreferences

    val crashLogUtil: CrashLogUtil

    val downloadManager: DownloadManager

    val downloader: eu.kanade.tachiyomi.data.download.Downloader

    val downloadProvider: eu.kanade.tachiyomi.data.download.DownloadProvider

    val updateChecker: AppUpdateChecker

    val trustExtension: TrustExtension
    val extensionStoreRepository: ExtensionStoreRepository

    val sourceManager: SourceManager
    val trackerManager: TrackerManager
    val extensionManager: ExtensionManager
    val chapterCache: ChapterCache
    val coverCache: CoverCache
    val downloadCache: DownloadCache

    val json: Json
    val protoBuf: ProtoBuf
    val xml: XML
    val networkHelper: NetworkHelper
    val javaScriptEngine: JavaScriptEngine

    val getLibraryManga: tachiyomi.domain.manga.interactor.GetLibraryManga
    val widgetHistory: tachiyomi.domain.history.interactor.GetHistory
    val getNextChapters: tachiyomi.domain.history.interactor.GetNextChapters

    val mangaRepository: tachiyomi.domain.manga.repository.MangaRepository

    val getFavorites: GetFavorites
    val getCategories: GetCategories
    val resetViewerFlags: ResetViewerFlags
    val resetCategoryFlags: ResetCategoryFlags
    val addTracks: AddTracks
    val upsertTrack: UpsertTrack

    val getExtensionStoreCountAsFlow: GetExtensionStoreCountAsFlow

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context, @Provides @IsDebugBuild isDebugBuild: Boolean): AppGraph
    }
}
