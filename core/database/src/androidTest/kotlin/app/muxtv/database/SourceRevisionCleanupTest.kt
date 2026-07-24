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
class SourceRevisionCleanupTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var store: SourceRevisionStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        store = RoomSourceRevisionStore(database.sourceRevisionDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun removesOnlyInactiveSourceWithMatchingCredentialReference() = runTest {
        store.upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "Pending",
                credentialRef = CREDENTIAL_REF,
            ),
        )

        val result = store.removeInactiveSource(SOURCE_ID, CREDENTIAL_REF)

        assertThat(result).isEqualTo(InactiveSourceRemovalResult.Removed)
        assertThat(database.sourceRevisionDao().activeRevision(SOURCE_ID)).isNull()
    }

    @Test
    fun credentialMismatchRetainsInactiveSource() = runTest {
        store.upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "Pending",
                credentialRef = CREDENTIAL_REF,
            ),
        )

        val result = store.removeInactiveSource(SOURCE_ID, "different-credential")

        assertThat(result).isEqualTo(InactiveSourceRemovalResult.CredentialMismatch)
        assertThat(database.sourceRevisionDao().activeRevision(SOURCE_ID)).isEqualTo(0)
    }

    @Test
    fun activeSourceIsNeverRemoved() = runTest {
        store.upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "Active",
                credentialRef = CREDENTIAL_REF,
            ),
        )
        store.beginRevision(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            startedAtEpochMillis = 1_000,
        )
        store.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            entries = listOf(
                StagedCatalogEntry(
                    providerChannelId = "provider-1",
                    providerKey = "tvg:channel-1",
                    rawName = "Channel",
                    canonicalChannelId = "canonical-1",
                    canonicalDisplayName = "Channel",
                    streamVariantId = "variant-1",
                    locator = "https://stream.example/live",
                ),
            ),
        )
        store.activate(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            activatedAtEpochMillis = 2_000,
            statistics = SourceRevisionStatistics(
                parsedEntries = 1,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )

        val result = store.removeInactiveSource(SOURCE_ID, CREDENTIAL_REF)

        assertThat(result).isEqualTo(InactiveSourceRemovalResult.Active)
        assertThat(database.sourceRevisionDao().activeRevision(SOURCE_ID)).isEqualTo(1)
    }

    @Test
    fun missingSourceReportsNotFound() = runTest {
        assertThat(store.removeInactiveSource(SOURCE_ID, CREDENTIAL_REF))
            .isEqualTo(InactiveSourceRemovalResult.NotFound)
    }

    private companion object {
        const val SOURCE_ID = "source-pending"
        const val CREDENTIAL_REF = "11111111-1111-4111-8111-111111111111"
    }
}
