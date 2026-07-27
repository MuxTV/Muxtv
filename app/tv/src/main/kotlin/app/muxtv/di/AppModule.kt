package app.muxtv.di

import android.content.Context
import app.muxtv.catalog.CatalogRepository
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.importer.CatalogRevisionImporter
import app.muxtv.catalog.importer.CatalogRevisionImporterFactory
import app.muxtv.catalog.onboarding.DurableRemoteSourceOnboarding
import app.muxtv.catalog.refresh.DefaultRemoteSourceOnboarding
import app.muxtv.catalog.refresh.EncryptedPlaybackAccessPolicyResolver
import app.muxtv.catalog.refresh.RemoteSourceAccessManager
import app.muxtv.catalog.refresh.RemoteSourceActivationCleanup
import app.muxtv.catalog.refresh.RemoteSourceActivationResult
import app.muxtv.catalog.refresh.RemoteSourceActivator
import app.muxtv.catalog.refresh.RemoteSourceCancellationResult
import app.muxtv.catalog.refresh.RemoteSourceMetadataCleanupResult
import app.muxtv.catalog.refresh.RemoteSourceOnboarding
import app.muxtv.catalog.refresh.RemoteSourceOnboardingInput
import app.muxtv.catalog.refresh.RemoteSourcePreparationResult
import app.muxtv.catalog.refresh.RemoteSourcePreparationToken
import app.muxtv.catalog.refresh.RemoteSourceRefresher
import app.muxtv.credentials.CredentialStore
import app.muxtv.database.DatabaseInitializer
import app.muxtv.database.InactiveSourceRemovalResult
import app.muxtv.database.MuxTvDatabaseComponents
import app.muxtv.database.MuxTvDatabaseFactory
import app.muxtv.database.PendingSourcePreparationStore
import app.muxtv.database.SourceRefreshStore
import app.muxtv.database.SourceRevisionStore
import app.muxtv.feature.sources.SourceEntryOnboarding
import app.muxtv.feature.sources.SourcePlaybackApprovalActions
import app.muxtv.feature.sources.SourcePlaybackApprovalResetResult
import app.muxtv.network.MuxTvHttpClients
import app.muxtv.network.MuxTvHttpResources
import app.muxtv.player.PlaybackEngine
import app.muxtv.player.media3.Media3PlaybackEngineFactory
import app.muxtv.player.media3.MuxTvMediaControllerConnector
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
    fun providePlaybackAccessPolicyResolver(
        accessManager: RemoteSourceAccessManager,
    ): PlaybackAccessPolicyResolver = EncryptedPlaybackAccessPolicyResolver(accessManager)

    @Provides
    @Singleton
    fun provideDatabaseComponents(
        @ApplicationContext context: Context,
        playbackAccessPolicyResolver: PlaybackAccessPolicyResolver,
    ): MuxTvDatabaseComponents = MuxTvDatabaseFactory.create(
        context = context,
        playbackAccessPolicyResolver = playbackAccessPolicyResolver,
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
    @Singleton
    fun provideSourcePlaybackApprovalActions(
        sourceRefreshStore: SourceRefreshStore,
        playbackAccessPolicyResolver: PlaybackAccessPolicyResolver,
    ): SourcePlaybackApprovalActions = SourcePlaybackApprovalActions { sourceId ->
        val credentialRef = sourceRefreshStore.getTarget(sourceId)?.credentialRef
            ?: return@SourcePlaybackApprovalActions SourcePlaybackApprovalResetResult.SourceNotFound
        when (playbackAccessPolicyResolver.revokeAll(credentialRef)) {
            PlaybackAccessMutationResult.Applied -> SourcePlaybackApprovalResetResult.Reset
            PlaybackAccessMutationResult.Unchanged -> SourcePlaybackApprovalResetResult.Unchanged
            PlaybackAccessMutationResult.NotFound -> SourcePlaybackApprovalResetResult.SourceNotFound
            PlaybackAccessMutationResult.Corrupted,
            PlaybackAccessMutationResult.Unavailable,
            PlaybackAccessMutationResult.InvalidLocator,
            PlaybackAccessMutationResult.CapacityExceeded,
            -> SourcePlaybackApprovalResetResult.AccessUnavailable
        }
    }

    @Provides
    @Singleton
    fun provideCatalogRevisionImporter(
        revisionStore: SourceRevisionStore,
    ): CatalogRevisionImporter = CatalogRevisionImporterFactory.create(revisionStore)

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
    ): RemoteSourceRefresher = RemoteSourceRefresher(
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
        activationCleanup = RemoteSourceActivationCleanup { sourceId, credentialRef ->
            when (revisionStore.removeInactiveSource(sourceId, credentialRef)) {
                InactiveSourceRemovalResult.Removed -> RemoteSourceMetadataCleanupResult.Removed
                InactiveSourceRemovalResult.NotFound -> RemoteSourceMetadataCleanupResult.NotFound
                InactiveSourceRemovalResult.Active,
                InactiveSourceRemovalResult.CredentialMismatch,
                InactiveSourceRemovalResult.ConcurrentChange,
                -> RemoteSourceMetadataCleanupResult.Retained
            }
        },
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
    fun provideSourceEntryOnboarding(
        durable: DurableRemoteSourceOnboarding,
    ): SourceEntryOnboarding = object : SourceEntryOnboarding {
        override suspend fun prepare(
            input: RemoteSourceOnboardingInput,
        ): RemoteSourcePreparationResult = durable.prepare(input)

        override suspend fun activate(
            token: RemoteSourcePreparationToken,
            sourceName: String,
        ): RemoteSourceActivationResult = durable.activate(token, sourceName)

        override suspend fun cancel(
            token: RemoteSourcePreparationToken,
        ): RemoteSourceCancellationResult = durable.cancel(token)

        override suspend fun restoreLatestPrepared(): RemoteSourcePreparationResult.Prepared? =
            durable.restoreLatestPrepared()
    }

    @Provides
    @Singleton
    fun providePlaybackEngine(@ApplicationContext context: Context): PlaybackEngine =
        Media3PlaybackEngineFactory.create(context)

    @Provides
    @Singleton
    fun provideMediaControllerConnector(
        @ApplicationContext context: Context,
    ): MuxTvMediaControllerConnector = MuxTvMediaControllerConnector(context)
}
