package app.muxtv.database

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

class EpgMatchGuideInvalidationYagniTest {
    @Test
    fun identicalQueryValuesStillInvalidateBecauseUnderlyingRowsMayHaveChanged() = runBlocking {
        val version = listOf(
            EpgGuideDataVersionRow(
                epgSourceId = "epg-1",
                epgRevisionNumber = 1,
                providerSourceId = "source-1",
                catalogRevisionNumber = 1,
                currentMatchCount = 1,
            ),
        )
        val repository = RoomEpgGuideRepository(
            FakeGuideDao(flowOf(version, version)),
        )

        val emissions = repository.observeDataChanges().toList()

        assertThat(emissions).containsExactly(Unit, Unit).inOrder()
    }

    private class FakeGuideDao(
        private val versions: Flow<List<EpgGuideDataVersionRow>>,
    ) : EpgGuideDao() {
        override fun observeDataVersion(
            matchPolicyVersion: Int,
        ): Flow<List<EpgGuideDataVersionRow>> {
            require(matchPolicyVersion == CURRENT_EPG_MATCH_POLICY_VERSION)
            return versions
        }

        override suspend fun activeMatchCounts(
            profileId: String,
            canonicalChannelIds: List<String>,
            matchPolicyVersion: Int,
        ): List<EpgGuideMatchCountRow> {
            require(matchPolicyVersion == CURRENT_EPG_MATCH_POLICY_VERSION)
            return emptyList()
        }

        override suspend fun programmeCandidates(
            profileId: String,
            canonicalChannelIds: List<String>,
            nowEpochMillis: Long,
            matchPolicyVersion: Int,
        ): List<EpgGuideProgrammeCandidateRow> {
            require(matchPolicyVersion == CURRENT_EPG_MATCH_POLICY_VERSION)
            return emptyList()
        }
    }
}
