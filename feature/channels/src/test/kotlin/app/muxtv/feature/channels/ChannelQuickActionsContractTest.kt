package app.muxtv.feature.channels

import app.muxtv.catalog.ChannelFavoriteMutationResult
import app.muxtv.catalog.ChannelPreferenceMutationResult
import app.muxtv.catalog.ChannelPreferencesRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ChannelQuickActionsContractTest {
    @Test
    fun `visible channel exposes bounded quick actions in stable order`() {
        assertThat(quickActionsFor(isFavorite = false).map(ChannelQuickAction::kind))
            .containsExactly(
                ChannelQuickActionKind.FAVORITE,
                ChannelQuickActionKind.HIDE,
                ChannelQuickActionKind.RENAME,
                ChannelQuickActionKind.CHANNEL_NUMBER,
                ChannelQuickActionKind.RESET,
            )
            .inOrder()
        assertThat(quickActionsFor(isFavorite = false).first().label).isEqualTo("В избранное")
        assertThat(quickActionsFor(isFavorite = true).first().label).isEqualTo("Убрать из избранного")
    }

    @Test
    fun `quick action mutations remain profile scoped`() = runBlocking {
        val preferences = RecordingPreferencesRepository()
        val controller = ChannelQuickActionsController(
            channelPreferencesRepository = preferences,
            profileId = PROFILE_ID,
        )

        controller.setFavorite(CHANNEL_ID, true)
        controller.hide(CHANNEL_ID)
        controller.setCustomName(CHANNEL_ID, " News ")
        controller.setChannelNumber(CHANNEL_ID, 77)
        controller.resetCustomization(CHANNEL_ID)

        assertThat(preferences.calls)
            .containsExactly(
                "favorite:$PROFILE_ID:$CHANNEL_ID:true",
                "hidden:$PROFILE_ID:$CHANNEL_ID:true",
                "name:$PROFILE_ID:$CHANNEL_ID: News ",
                "number:$PROFILE_ID:$CHANNEL_ID:77",
                "reset:$PROFILE_ID:$CHANNEL_ID",
            )
            .inOrder()
    }

    private class RecordingPreferencesRepository : ChannelPreferencesRepository {
        val calls = mutableListOf<String>()

        override suspend fun setFavorite(
            profileId: String,
            channelId: String,
            isFavorite: Boolean,
        ): ChannelFavoriteMutationResult {
            calls += "favorite:$profileId:$channelId:$isFavorite"
            return ChannelFavoriteMutationResult.Applied
        }

        override suspend fun setHidden(
            profileId: String,
            channelId: String,
            isHidden: Boolean,
        ): ChannelPreferenceMutationResult {
            calls += "hidden:$profileId:$channelId:$isHidden"
            return ChannelPreferenceMutationResult.Applied
        }

        override suspend fun setCustomName(
            profileId: String,
            channelId: String,
            customName: String?,
        ): ChannelPreferenceMutationResult {
            calls += "name:$profileId:$channelId:$customName"
            return ChannelPreferenceMutationResult.Applied
        }

        override suspend fun setChannelNumber(
            profileId: String,
            channelId: String,
            channelNumber: Int?,
        ): ChannelPreferenceMutationResult {
            calls += "number:$profileId:$channelId:$channelNumber"
            return ChannelPreferenceMutationResult.Applied
        }

        override suspend fun resetCustomization(
            profileId: String,
            channelId: String,
        ): ChannelPreferenceMutationResult {
            calls += "reset:$profileId:$channelId"
            return ChannelPreferenceMutationResult.Applied
        }
    }

    private companion object {
        const val PROFILE_ID = "profile-main"
        const val CHANNEL_ID = "channel-a"
    }
}
