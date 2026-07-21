package app.muxtv

import android.content.Context
import app.muxtv.credentials.AndroidCredentialStoreFactory
import app.muxtv.credentials.CredentialStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationIoScope

@Module
@InstallIn(SingletonComponent::class)
object AppInfrastructureModule {
    @Provides
    @Singleton
    @ApplicationIoScope
    fun provideApplicationIoScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideCredentialStore(
        @ApplicationContext context: Context,
        @ApplicationIoScope scope: CoroutineScope,
    ): CredentialStore = AndroidCredentialStoreFactory(
        context = context,
        scope = scope,
    ).get()
}
