package app.muxtv.player.media3

import app.muxtv.player.PlaybackObservation
import app.muxtv.player.PlaybackObservationReader
import app.muxtv.player.PlaybackObservationRecorder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Collections
import javax.inject.Singleton

internal class PlaybackObservationBuffer(
    private val capacity: Int,
) : PlaybackObservationRecorder, PlaybackObservationReader {
    private val observations = ArrayDeque<PlaybackObservation>(capacity.coerceAtLeast(1))

    init {
        require(capacity > 0)
    }

    @Synchronized
    override fun record(observation: PlaybackObservation) {
        if (observations.size == capacity) observations.removeFirst()
        observations.addLast(observation)
    }

    @Synchronized
    override fun snapshot(): List<PlaybackObservation> =
        Collections.unmodifiableList(ArrayList(observations))
}

@Module
@InstallIn(SingletonComponent::class)
internal object PlaybackObservationModule {
    @Provides
    @Singleton
    fun provideBuffer(): PlaybackObservationBuffer = PlaybackObservationBuffer(BUFFER_CAPACITY)

    @Provides
    fun provideRecorder(buffer: PlaybackObservationBuffer): PlaybackObservationRecorder = buffer

    @Provides
    fun provideReader(buffer: PlaybackObservationBuffer): PlaybackObservationReader = buffer

    private const val BUFFER_CAPACITY = 64
}
