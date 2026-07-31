package app.muxtv.database

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgRevisionAllocationContractTest {
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
    fun concurrentRevisionAllocationIsAtomic() = runBlocking {
        dao.insertSource(
            EpgSourceEntity(
                id = SOURCE_ID,
                name = "Synthetic EPG",
                providerSourceId = null,
                accessRef = null,
                defaultZoneId = null,
            ),
        )

        val revisions = listOf(
            async(Dispatchers.Default) {
                dao.beginNextRevision(SOURCE_ID, startedAtEpochMillis = 10)
            },
            async(Dispatchers.Default) {
                dao.beginNextRevision(SOURCE_ID, startedAtEpochMillis = 20)
            },
        ).awaitAll()

        assertThat(revisions).containsExactly(1L, 2L)
        assertThat(dao.revisionNumbers(SOURCE_ID)).containsExactly(1L, 2L).inOrder()
        Unit
    }

    private companion object {
        const val SOURCE_ID = "epg-source-allocation"
    }
}
