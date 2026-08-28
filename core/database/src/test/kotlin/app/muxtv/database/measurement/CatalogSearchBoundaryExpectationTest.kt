package app.muxtv.database.measurement

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CatalogSearchBoundaryExpectationTest {
    @Test
    fun selectivePublishedChannelUsesItsOwnProgrammeBoundary() {
        assertThat(
            expectedCatalogSearchBoundaryEpochMillis(
                canonicalChannelIds = listOf("canonical-49999"),
                firstBoundaryEpochMillis = FIRST_BOUNDARY,
            ),
        ).isEqualTo(FIRST_BOUNDARY + 49_999L)
    }

    @Test
    fun broadPublishedSetUsesEarliestBoundaryAmongPublishedChannels() {
        assertThat(
            expectedCatalogSearchBoundaryEpochMillis(
                canonicalChannelIds = listOf(
                    "canonical-00017",
                    "canonical-00000",
                    "canonical-00042",
                ),
                firstBoundaryEpochMillis = FIRST_BOUNDARY,
            ),
        ).isEqualTo(FIRST_BOUNDARY)
    }

    private companion object {
        const val FIRST_BOUNDARY = 1_700_000_060_000L
    }
}
