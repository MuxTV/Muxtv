package app.muxtv.player.media3

import android.content.Context
import app.muxtv.player.DevicePlaybackProfile
import app.muxtv.player.DevicePlaybackProfileSummary
import app.muxtv.player.DevicePlaybackProfileSummaryReader
import app.muxtv.player.toDevicePlaybackProfileSummary
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

internal class DevicePlaybackProfileSummaryReaderImpl(
    private val capture: () -> DevicePlaybackProfile,
) : DevicePlaybackProfileSummaryReader {
    override fun snapshot(): DevicePlaybackProfileSummary? =
        runCatching { capture().toDevicePlaybackProfileSummary() }.getOrNull()
}

@Module
@InstallIn(SingletonComponent::class)
internal object DevicePlaybackProfileSummaryReaderModule {
    @Provides
    @Singleton
    fun provideDevicePlaybackProfileSummaryReader(
        @ApplicationContext context: Context,
    ): DevicePlaybackProfileSummaryReader {
        val probe = AndroidDevicePlaybackProfileProbe(context.applicationContext)
        return DevicePlaybackProfileSummaryReaderImpl(probe::capture)
    }
}
