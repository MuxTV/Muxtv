package app.muxtv.database

import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgChannelMatchSchemaContractTest {
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
    fun currentSchemaProvidesRevisionPairMatchTableAndIndices() = runTest {
        database.useReaderConnection { connection ->
            assertSchemaObjectExists(connection, "table", "epg_channel_matches")
            assertSchemaObjectExists(
                connection,
                "index",
                "index_epg_channel_matches_epgSourceId_epgRevisionNumber_epgExternalChannelId",
            )
            assertSchemaObjectExists(
                connection,
                "index",
                "index_epg_channel_matches_providerSourceId_catalogRevisionNumber",
            )
            assertSchemaObjectExists(
                connection,
                "index",
                "index_epg_channel_matches_canonicalChannelId",
            )
        }
    }

    private suspend fun assertSchemaObjectExists(
        connection: androidx.room3.Transactor,
        type: String,
        name: String,
    ) {
        connection.usePrepared(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = ? AND name = ?",
        ) { statement ->
            statement.bindText(1, type)
            statement.bindText(2, name)
            assertThat(statement.step()).isTrue()
            assertThat(statement.getLong(0)).isEqualTo(1)
            assertThat(statement.step()).isFalse()
        }
    }
}
