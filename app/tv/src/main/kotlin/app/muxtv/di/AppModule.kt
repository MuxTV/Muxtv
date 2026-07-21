package app.muxtv.di

import android.content.Context
import app.muxtv.catalog.CatalogRepository
import app.muxtv.catalog.importer.CatalogRevisionImporter
import app.muxtv.catalog.importer.CatalogRevisionImporterFactory
import app.muxtv.catalog.refresh.RemoteSourceAccessManager
import app.muxtv.catalog.refresh.RemoteSourceRefreshFactory
import app.muxtv.catalog.refresh.RemoteSourceRefresher
import app.muxtv.credentials.CredentialStore
import app.muxtv.database.DatabaseInitializer
import app.muxtv.database.MuxTvDatabaseComponents
import app.muxtv.database.MuxTvDatabaseFactory
import app.muxtv.database.SourceRevisionStore
import app.muxtv.player.PlaybackEngine
import app.muxtv.player.media3.Media3PlaybackEngineFactory
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
    fun provideDatabaseComponents(
        @ApplicationContext context: Context,
    ): MuxTvDatabaseComponents = MuxTvDatabaseFactory.create(context)

    @Provides
    fun provideDatabaseInitializer(
        components: MuxTvDatabaseComponents,
    ): DatabaseInitializer = components.initializer

    @Provides
    fun provideSourceRevisionStore(
        components: MuxTvDatabaseComponents,
    ): SourceRevisionStore = components.sourceRevisionStore

    @Provides
    fun provideCatalogRepository(
        components: MuxTvDatabaseComponents,
    ): CatalogRepository = components.catalogRepository

    @Provides
    @Singleton
    fun provideCatalogRevisionImporter(
        revisionStore: SourceRevisionStore,
    ): CatalogRevisionImporter = CatalogRevisionImporterFactory.create(revisionStore)

    @Provides
    @Singleton
    fun provideRemoteSourceAccessManager(
        credentialStore: CredentialStore,
    ): RemoteSourceAccessManager = RemoteSourceRefreshFactory.createAccessManager(credentialStore)

    @Provides
    @Singleton
    fun provideRemoteSourceRefresher(
        credentialStore: CredentialStore,
        importer: CatalogRevisionImporter,
    ): RemoteSourceRefresher = RemoteSourceRefreshFactory.create(
        credentialStore = credentialStore,
        importer = importer,
    )

    @Provides
    @Singleton
    fun providePlaybackEngine(@ApplicationContext context: Context): PlaybackEngine =
        Media3PlaybackEngineFactory.create(context)
}
