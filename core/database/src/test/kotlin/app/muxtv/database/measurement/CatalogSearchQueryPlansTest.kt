package app.muxtv.database.measurement

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CatalogSearchQueryPlansTest {
    @Test
    fun `candidate plans preserve the measured query arguments`() {
        val plans = CatalogSearchQueryPlans.queries(
            profileId = "measurement-profile",
            nowEpochMillis = 1_700_000_000_000L,
            candidateProbes = listOf(
                CatalogSearchCandidatePlanProbe("\"50000*\"", fetchLimit = 7),
                CatalogSearchCandidatePlanProbe(
                    ftsExpression = "\"Synthetic*\"",
                    fetchLimit = 1,
                    restrictedCanonicalIds = listOf("canonical-49999"),
                ),
            ),
            publishedCanonicalChannelIds = listOf("canonical-49999"),
        ).first { it.first == "search-candidate-resolution" }.second

        assertThat(plans).hasSize(2)
        assertThat(plans[0]).contains("MATCH '\"50000*\"'")
        assertThat(plans[0]).contains("0 = 0")
        assertThat(plans[0]).contains("LIMIT 7")
        assertThat(plans[1]).contains("MATCH '\"Synthetic*\"'")
        assertThat(plans[1]).contains("1 = 0")
        assertThat(plans[1]).contains("'canonical-49999'")
        assertThat(plans[1]).contains("LIMIT 1")
    }
}
