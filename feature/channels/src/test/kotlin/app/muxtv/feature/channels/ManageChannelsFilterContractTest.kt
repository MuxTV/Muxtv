package app.muxtv.feature.channels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ManageChannelsFilterContractTest {
    @Test
    fun managementSurfaceExposesOnlyExplicitVisibilityFilters() {
        assertThat(ManageChannelsFilter.entries)
            .containsExactly(
                ManageChannelsFilter.ALL,
                ManageChannelsFilter.VISIBLE,
                ManageChannelsFilter.HIDDEN,
            ).inOrder()
    }
}
