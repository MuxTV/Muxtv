package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuideWindowInvalidationTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var repository: RoomGuideWindowRepository

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        DatabaseInitializer(database).initialize()
        database.catalogDao().insertCanonicalChannel(
            CanonicalChannelEntity(
                id = CHANNEL_ID,
                displayName = "Channel",
            ),
        )
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = CHANNEL_ID,
                customName = "Before",
            ),
        )
        repository = RoomGuideWindowRepository(
            dao = database.guideWindowDao(),
            invalidationDao = database.guideWindowInvalidationDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun overlayUpdateEmitsPayloadFreeGuideInvalidation() = runTest {
        val firstEmission = CompletableDeferred<Unit>()
        val emissions = async {
            repository.observeDataChanges()
                .onEach { firstEmission.complete(Unit) }
                .take(2)
                .toList()
        }
        runCurrent()
        firstEmission.await()

        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = CHANNEL_ID,
                customName = "After",
                channelNumber = 7,
            ),
        )

        assertThat(emissions.await()).containsExactly(Unit, Unit).inOrder()
    }

    private companion object {
        const val PROFILE_ID = DatabaseDefaults.PRIMARY_PROFILE_ID
        const val CHANNEL_ID = "guide-invalidation-channel"
    }
}
