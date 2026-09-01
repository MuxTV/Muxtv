package app.muxtv.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.muxtv.catalog.CatalogRepository
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.catalog.PlaybackArchiveResolver
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.PlaybackCandidateResolver
import app.muxtv.catalog.PlaybackReferenceResolver
import app.muxtv.catalog.SourceManagement
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.catalog.importer.CatalogRevisionImporter
import app.muxtv.catalog.importer.CatalogRevisionImporterFactory
import app.muxtv.catalog.importer.EpgRevisionImporter
import app.muxtv.catalog.importer.EpgRevisionImporterFactory
import app.muxtv.catalog.ingest.StreamingXtreamParser
import app.muxtv.catalog.onboarding.DurableRemoteSourceOnboarding
import app.muxtv.catalog.refresh.DefaultRemoteSourceOnboarding
import app.muxtv.catalog.refresh.EncryptedPlaybackAccessPolicyResolver
import app.muxtv.catalog.refresh.M3uPlaybackArchiveResolver
import app.muxtv.catalog.refresh.RemoteEpgRefresher
import app.muxtv.catalog.refresh.RemoteSourceAccessManager
import app.muxtv.catalog.refresh.RemoteSourceActivationCleanup
import app.muxtv.catalog.refresh.RemoteSourceActivator
import app.muxtv.catalog.refresh.RemoteSourceMetadataCleanupResult
import app.muxtv.catalog.refresh.RemoteSourceOnboarding
import app.muxtv.catalog.refresh.RemoteSourceRefresher
import app.muxtv.catalog.refresh.XtreamLiveRefresher
import app.muxtv.catalog.refresh.XtreamPlaybackReferenceResolver
import app.muxtv.catalog.refresh.XtreamSourceAccessManager
import app.muxtv.catalog.refresh.XtreamSourceActivator
import app.muxtv.catalog.refresh.XtreamSourceLifecycle
import app.muxtv.catalog.refresh.XtreamSourcePreparer
import app.muxtv.catalog.sync.SourceRefreshScheduler
import app.muxtv.credentials.CredentialStore
import app.muxtv.database.DatabaseInitializer
import app.muxtv.database.EpgMatchingStore
import app.muxtv.database.EpgRevisionStore
import app.muxtv.database.InactiveSourceRemovalResult
import app.muxtv.database.MuxTvDatabaseComponents
import app.muxtv.database.MuxTvDatabaseFactory
import app.muxtv.database.PendingSourcePreparationStore
import app.muxtv.database.SourceRefreshStore
import app.muxtv.database.SourceRevisionStore
import app.muxtv.external.ExternalPlaybackOriginGrantStore
import app.muxtv.external.LocalNetworkPermissionGate
import app.muxtv.external.LocalNetworkSourcePreflight
import app.muxtv.external.SharedPreferencesExternalPlaybackOriginGrantStore
import app.muxtv.network.MuxTvHttpClients
import app.muxtv.network.MuxTvHttpResources
import app.muxtv.player.ExternalPlaybackLeaseRegistry
import app.muxtv.player.InMemoryExternalPlaybackLeaseRegistry
import app.muxtv.player.PlaybackSessionGateway
import app.muxtv.player.media3.Media3PlaybackSessionGateway
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import app.muxtv.sources.AppSourceManagement
import app.muxtv.sources.AppSourceOnboarding
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideRemoteSourceAccessManager(
        credentialStore: CredentialStore,
    ): RemoteSourceAccessManager = RemoteSourceAccessManager(credentialStore)

    @Provides
    @Singleton
    fun provideXtreamSourceAccessManager(
        credentialStore: CredentialStore,
    ): XtreamSourceAccessManager = XtreamSourceAccessManager(credentialStore)

    @Provides
    @Singleton
    fun provideXtreamSourcePreparer(
        accessManager: XtreamSourceAccessManager,
    ): XtreamSourcePreparer = XtreamSourcePreparer(accessManager)

    @Provides
    @Singleton
    fun providePlaybackAccessPolicyResolver(
        accessManager: RemoteSourceAccessManager,
    ): PlaybackAccessPolicyResolver = EncryptedPlaybackAccessPolicyResolver(accessManager)

    @Provides
    @Singleton
    fun providePlaybackReferenceResolver(
        accessManager: XtreamSourceAccessManager,
    ): PlaybackReferenceResolver = XtreamPlaybackReferenceResolver(accessManager)

    @Provides
    @Singleton
    fun providePlaybackArchiveResolver(): PlaybackArchiveResolver =
        M3uPlaybackArchiveResolver()

    @Provides
    @Singleton
    fun provideLocalNetworkSourcePreflight(
        @ApplicationContext context: Context,
    ): LocalNetworkSourcePreflight = LocalNetworkSourcePreflight(
        apiLevel = Build.VERSION.SDK_INT,
        permissionGranted = {
            Build.VERSION.SDK_INT < LocalNetworkPermissionGate.ANDROID_17_API ||
                context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
                PackageManager.PERMISSION_GRANTED
        },
    )

    @Provides
    @Singleton
    fun provideDatabaseComponents(
        @ApplicationContext context: Context,
        playbackAccessPolicyResolver: PlaybackAccessPolicyResolver,
        playbackReferenceResolver: PlaybackReferenceResolver,
        playbackArchiveResolver: PlaybackArchiveResolver,
    ): MuxTvDatabaseComponents = MuxTvDatabaseFactory.create(
        context = context,
        playbackAccessPolicyResolver = playbackAccessPolicyResolver,
        playbackReferenceResolver = playbackReferenceResolver,
        playbackArchiveResolver = playbackArchiveResolver,
    )

    @Provides
    fun provideDatabaseInitializer(
        components: MuxTvDatabaseComponents,
    ): DatabaseInitializer = components.initializer

    @Provides
    fun provideSourceRevisionStore(
        components: MuxTvDatabaseComponents,
    ): SourceRevisionStore = components.sourceRevisionStore

    @Provides
    fun provideEpgRevisionStore(
        components: MuxTvDatabaseComponents,
    ): EpgRevisionStore = components.epgRevisionStore

    @Provides
    fun provideEpgMatchingStore(
        components: MuxTvDatabaseComponents,
    ): EpgMatchingStore = components.epgMatchingStore

    @Provides
    fun provideEpgGuideRepository(
        components: MuxTvDatabaseComponents,
    ): EpgGuideRepository = components.epgGuideRepository

    @Provides
    fun provideSourceRefreshStore(
        components: MuxTvDatabaseComponents,
    ): SourceRefreshStore = components.sourceRefreshStore

    @Provides
    fun providePendingSourcePreparationStore(
        components: MuxTvDatabaseComponents,
    ): PendingSourcePreparationStore = components.pendingSourcePreparationStore

    @Provides
    fun provideCatalogRepository(
        components: MuxTvDatabaseComponents,
    ): CatalogRepository = components.catalogRepository

    @Provides
    fun providePlaybackCatalog(
        components: MuxTvDatabaseComponents,
    ): PlaybackCatalog = components.playbackCatalog

    @Provides
    fun provideChannelBrowseRepository(
        components: MuxTvDatabaseComponents,
    ): ChannelBrowseRepository = components.channelBrowseRepository

    @Provides
    fun providePlaybackCandidateResolver(
        components: MuxTvDatabaseComponents,
    ): PlaybackCandidateResolver = components.playbackCandidateResolver

    @Provides
    @Singleton
    fun provideSourceManagement(
        sourceRefreshStore: SourceRefreshStore,
        sourceRefreshScheduler: SourceRefreshScheduler,
        playbackAccessPolicyResolver: PlaybackAccessPolicyResolver,
    ): SourceManagement = AppSourceManagement(
        refreshStore = sourceRefreshStore,
        refreshScheduler = sourceRefreshScheduler,
        playbackAccessPolicyResolver = playbackAccessPolicyResolver,
    )

    @Provides
    @Singleton
    fun provideCatalogRevisionImporter(
        revisionStore: SourceRevisionStore,
    ): CatalogRevisionImporter = CatalogRevisionImporterFactory.create(revisionStore)

    @Provides
    @Singleton
    fun provideEpgRevisionImporter(
        revisionStore: EpgRevisionStore,
    ): EpgRevisionImporter = EpgRevisionImporterFactory.create(revisionStore)

    @Provides
    @Singleton
    fun provideHttpResources(): MuxTvHttpResources = MuxTvHttpResources()

    @Provides
    @Singleton
    fun provideHttpClients(
        resources: MuxTvHttpResources,
    ): MuxTvHttpClients = MuxTvHttpClients(resources)

    @Provides
    @Singleton
    fun provideRemoteSourceRefresher(
        accessManager: RemoteSourceAccessManager,
        importer: CatalogRevisionImporter,
        clients: MuxTvHttpClients,
        localNetworkPreflight: LocalNetworkSourcePreflight,
    ): RemoteSourceRefresher = RemoteSourceRefresher(
        accessManager = accessManager,
        importer = importer,
        sourceClient = clients.source,
        localNetworkAccessRequired = localNetworkPreflight::accessRequired,
    )

    @Provides
    @Singleton
    fun provideXtreamLiveRefresher(
        accessManager: XtreamSourceAccessManager,
        importer: CatalogRevisionImporter,
        clients: MuxTvHttpClients,
        localNetworkPreflight: LocalNetworkSourcePreflight,
    ): XtreamLiveRefresher = XtreamLiveRefresher(
        accessManager = accessManager,
        importer = importer,
        sourceClient = clients.source,
        parser = StreamingXtreamParser(),
        localNetworkAccessRequired = localNetworkPreflight::accessRequired,
    )

    @Provides
    @Singleton
    fun provideRemoteEpgRefresher(
        accessManager: RemoteSourceAccessManager,
        importer: EpgRevisionImporter,
        clients: MuxTvHttpClients,
    ): RemoteEpgRefresher = RemoteEpgRefresher(
        accessManager = accessManager,
        importer = importer,
        sourceClient = clients.source,
    )

    @Provides
    @Singleton
    fun provideDefaultRemoteSourceOnboarding(
        accessManager: RemoteSourceAccessManager,
        refresher: RemoteSourceRefresher,
        revisionStore: SourceRevisionStore,
    ): DefaultRemoteSourceOnboarding = DefaultRemoteSourceOnboarding(
        accessManager = accessManager,
        activator = RemoteSourceActivator { request -> refresher.refresh(request) },
        activationCleanup = remoteSourceActivationCleanup(revisionStore),
    )

    @Provides
    @Singleton
    fun provideXtreamSourceLifecycle(
        accessManager: XtreamSourceAccessManager,
        refresher: XtreamLiveRefresher,
        revisionStore: SourceRevisionStore,
    ): XtreamSourceLifecycle = XtreamSourceLifecycle(
        accessManager = accessManager,
        activator = XtreamSourceActivator { request -> refresher.refresh(request) },
        activationCleanup = remoteSourceActivationCleanup(revisionStore),
    )

    @Provides
    @Singleton
    fun provideDurableRemoteSourceOnboarding(
        delegate: DefaultRemoteSourceOnboarding,
        pendingStore: PendingSourcePreparationStore,
    ): DurableRemoteSourceOnboarding = DurableRemoteSourceOnboarding(
        delegate = delegate,
        registry = pendingStore,
    )

    @Provides
    fun provideRemoteSourceOnboarding(
        durable: DurableRemoteSourceOnboarding,
    ): RemoteSourceOnboarding = durable

    @Provides
    @Singleton
    fun provideSourceOnboarding(
        durable: DurableRemoteSourceOnboarding,
        xtreamPreparer: XtreamSourcePreparer,
        xtreamLifecycle: XtreamSourceLifecycle,
        localNetworkPreflight: LocalNetworkSourcePreflight,
    ): SourceOnboarding = AppSourceOnboarding(
        delegate = durable,
        xtreamPreparer = xtreamPreparer,
        xtreamLifecycle = xtreamLifecycle,
        localNetworkAccessRequired = localNetworkPreflight::accessRequired,
    )

    @Provides
    @Singleton
    fun provideMediaControllerConnector(
        @ApplicationContext context: Context,
    ): MuxTvMediaControllerConnector = MuxTvMediaControllerConnector(context)

    @Provides
    @Singleton
    fun provideMedia3PlaybackSessionGateway(
        connector: MuxTvMediaControllerConnector,
    ): Media3PlaybackSessionGateway = Media3PlaybackSessionGateway(connector)

    @Provides
    fun providePlaybackSessionGateway(
        gateway: Media3PlaybackSessionGateway,
    ): PlaybackSessionGateway = gateway

    @Provides
    @Singleton
    fun provideExternalPlaybackLeaseRegistry(): ExternalPlaybackLeaseRegistry =
        InMemoryExternalPlaybackLeaseRegistry()

    @Provides
    @Singleton
    fun provideExternalPlaybackOriginGrantStore(
        @ApplicationContext context: Context,
    ): ExternalPlaybackOriginGrantStore = SharedPreferencesExternalPlaybackOriginGrantStore(
        context.getSharedPreferences(
            EXTERNAL_PLAYBACK_PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ),
    )

    private fun remoteSourceActivationCleanup(
        revisionStore: SourceRevisionStore,
    ): RemoteSourceActivationCleanup = RemoteSourceActivationCleanup { sourceId, credentialRef ->
        when (revisionStore.removeInactiveSource(sourceId, credentialRef)) {
            InactiveSourceRemovalResult.Removed -> RemoteSourceMetadataCleanupResult.Removed
            InactiveSourceRemovalResult.NotFound -> RemoteSourceMetadataCleanupResult.NotFound
            InactiveSourceRemovalResult.Active,
            InactiveSourceRemovalResult.CredentialMismatch,
            InactiveSourceRemovalResult.ConcurrentChange,
            -> RemoteSourceMetadataCleanupResult.Retained
        }
    }

    private const val EXTERNAL_PLAYBACK_PREFERENCES_NAME = "muxtv_external_playback"
}
