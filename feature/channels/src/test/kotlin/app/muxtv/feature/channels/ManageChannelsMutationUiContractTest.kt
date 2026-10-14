package app.muxtv.feature.channels

import app.muxtv.catalog.ChannelPreferenceMutationResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ManageChannelsMutationUiContractTest {
    @Test
    fun onlyInvalidInputKeepsTheCurrentActionSnapshotOpen() {
        assertThat(ChannelPreferenceMutationResult.Applied.shouldDismissManageChannelActions()).isTrue()
        assertThat(ChannelPreferenceMutationResult.Unchanged.shouldDismissManageChannelActions()).isTrue()
        assertThat(ChannelPreferenceMutationResult.NotFound.shouldDismissManageChannelActions()).isTrue()
        assertThat(ChannelPreferenceMutationResult.InvalidInput.shouldDismissManageChannelActions()).isFalse()
    }
}
