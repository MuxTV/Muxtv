package app.muxtv.database

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgActiveQueryBoundsContractTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var dao: EpgRevisionDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MuxTvDatabase::class.java,
        ).build()
        dao = database.epgRevisionDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun emptyChannelSelectionReturnsEmptyWithoutUnboundedSql() = runBlocking {
        assertThat(
            dao.activeProgrammes(
                sourceId = "epg-source",
                externalChannelIds = emptyList(),
                fromEpochMillis = 0,
                toEpochMillis = 10_000,
                limit = 20,
            ),
        ).isEmpty()
    }

    @Test
    fun invalidWindowAndLimitAreRejectedBeforeQuery() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                dao.activeProgrammes(
                    sourceId = "epg-source",
                    externalChannelIds = listOf("channel"),
                    fromEpochMillis = 10_000,
                    toEpochMillis = 10_000,
                    limit = 20,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                dao.activeProgrammes(
                    sourceId = "epg-source",
                    externalChannelIds = listOf("channel"),
                    fromEpochMillis = 0,
                    toEpochMillis = MAX_ACTIVE_WINDOW_MILLIS + 1,
                    limit = 20,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                dao.activeProgrammes(
                    sourceId = "epg-source",
                    externalChannelIds = listOf("channel"),
                    fromEpochMillis = 0,
                    toEpochMillis = 10_000,
                    limit = -1,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                dao.activeProgrammes(
                    sourceId = "epg-source",
                    externalChannelIds = List(257) { "channel-$it" },
                    fromEpochMillis = 0,
                    toEpochMillis = 10_000,
                    limit = 20,
                )
            }
        }
    }

    private companion object {
        const val MAX_ACTIVE_WINDOW_MILLIS = 31L * 24 * 60 * 60 * 1_000
    }
}
