package app.muxtv.player.media3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.player.DevicePlaybackProfile
import app.muxtv.player.DevicePlaybackProfileSummaryReader
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDevicePlaybackProfileSummaryReaderTest {
    @Test
    fun realAndroidProbeProjectsAValidBoundedSummary() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val implementationClass = runCatching {
            Class.forName("app.muxtv.player.media3.DevicePlaybackProfileSummaryReaderImpl")
        }.getOrNull()
        assertThat(implementationClass).isNotNull()
        implementationClass!!

        val constructor = implementationClass.declaredConstructors.single()
        constructor.isAccessible = true
        val capture: () -> DevicePlaybackProfile = {
            AndroidDevicePlaybackProfileProbe(context).capture()
        }
        val reader = constructor.newInstance(capture) as DevicePlaybackProfileSummaryReader
        val summary = reader.snapshot()

        assertThat(summary).isNotNull()
        summary!!
        assertThat(summary.videoDecoders.size).isAtMost(4)
        assertThat(summary.videoDecoders.map { capability -> capability.codec }.distinct())
            .hasSize(summary.videoDecoders.size)
        assertThat(summary.supportedDisplayModeCount).isAtLeast(0)
        assertThat(summary.supportedDisplayModeCount).isAtMost(64)
        assertThat(summary.memoryClassMb).isGreaterThan(0)
        summary.currentDisplayMode?.let { mode ->
            assertThat(mode.widthPixels).isGreaterThan(0)
            assertThat(mode.heightPixels).isGreaterThan(0)
            assertThat(mode.refreshRateMilliHz).isGreaterThan(0)
        }
    }
}
