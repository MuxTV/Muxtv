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
class PendingSourcePreparationStoreTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var store: PendingSourcePreparationStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        store = RoomPendingSourcePreparationStore(database.pendingSourcePreparationDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun roundTripsOnlyOpaqueIdSanitizedEndpointAndTimestamps() = runTest {
        val preparation = pending(
            id = "00000000-0000-4000-8000-000000000001",
            createdAt = 1_000L,
            expiresAt = 2_000L,
        )

        store.upsert(preparation)

        assertThat(store.get(preparation.preparationId)).isEqualTo(preparation)
        assertThat(preparation.toString()).doesNotContain(preparation.preparationId)
        assertThat(preparation.toString()).doesNotContain("token=")
    }

    @Test
    fun latestActiveIgnoresExpiredRowsAndUsesDeterministicOrdering() = runTest {
        store.upsert(pending("00000000-0000-4000-8000-000000000001", 100L, 900L))
        store.upsert(pending("00000000-0000-4000-8000-000000000002", 200L, 2_000L))
        store.upsert(pending("00000000-0000-4000-8000-000000000003", 300L, 2_000L))

        val latest = store.getLatestActive(nowEpochMillis = 1_000L)

        assertThat(latest?.preparationId)
            .isEqualTo("00000000-0000-4000-8000-000000000003")
    }

    @Test
    fun expiredQueryIsOrderedAndBounded() = runTest {
        store.upsert(pending("00000000-0000-4000-8000-000000000003", 300L, 900L))
        store.upsert(pending("00000000-0000-4000-8000-000000000001", 100L, 700L))
        store.upsert(pending("00000000-0000-4000-8000-000000000002", 200L, 800L))
        store.upsert(pending("00000000-0000-4000-8000-000000000004", 400L, 2_000L))

        val expired = store.getExpired(nowEpochMillis = 1_000L, limit = 2)

        assertThat(expired.map(PendingSourcePreparation::preparationId)).containsExactly(
            "00000000-0000-4000-8000-000000000001",
            "00000000-0000-4000-8000-000000000002",
        ).inOrder()
    }

    @Test
    fun upsertIsIdempotentAndRemoveReportsExistence() = runTest {
        val id = "00000000-0000-4000-8000-000000000001"
        store.upsert(pending(id, 100L, 1_000L))
        store.upsert(pending(id, 200L, 2_000L))

        assertThat(store.get(id)?.createdAtEpochMillis).isEqualTo(200L)
        assertThat(store.remove(id)).isTrue()
        assertThat(store.remove(id)).isFalse()
        assertThat(store.get(id)).isNull()
    }

    private fun pending(
        id: String,
        createdAt: Long,
        expiresAt: Long,
    ): PendingSourcePreparation = PendingSourcePreparation(
        preparationId = id,
        scheme = "https",
        host = "example.com",
        createdAtEpochMillis = createdAt,
        expiresAtEpochMillis = expiresAt,
    )
}
