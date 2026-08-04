package app.muxtv.di

import app.muxtv.player.PlaybackEngine
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackOwnershipBindingTest {
    @Test
    fun `application DI does not provide an alternative PlaybackEngine owner`() {
        val playbackEngineProviders = AppModule::class.java.declaredMethods
            .filter { method -> PlaybackEngine::class.java.isAssignableFrom(method.returnType) }
            .map { method -> method.name }

        assertThat(playbackEngineProviders).isEmpty()
    }
}
