package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayIsolationTest {
    private lateinit var database: MuxTvDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun deletingAdditionalProfileRemovesOnlyItsOverlay() = runTest {
        DatabaseInitializer(database).initialize()
        database.profileDao().insert(ProfileEntity("profile-office", "Кабинет", false))
        database.catalogDao().insertCanonicalChannel(CanonicalChannelEntity("channel-news", "Новости"))
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = "profile-office",
                canonicalChannelId = "channel-news",
                isFavorite = true,
            ),
        )

        assertThat(database.profileDao().deleteAdditional("profile-office")).isEqualTo(1)
        assertThat(database.catalogDao().countCanonicalChannels()).isEqualTo(1)
        assertThat(database.catalogDao().countOverlays("profile-office")).isEqualTo(0)
    }
}
