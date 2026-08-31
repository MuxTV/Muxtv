package app.muxtv.feature.channels

import androidx.paging.PagingData
import app.muxtv.catalog.ChannelBrowseItem
import app.muxtv.catalog.ChannelBrowseQuery
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.catalog.ChannelFavoriteMutationResult
import app.muxtv.catalog.ChannelManagementItem
import app.muxtv.catalog.ChannelManagementQuery
import app.muxtv.catalog.ChannelManagementVisibility
import app.muxtv.catalog.ChannelPreferenceMutationResult
import app.muxtv.catalog.ChannelPreferencesRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManageChannelsViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun managementFiltersCreateIndependentQueriesLazily() = runBlocking {
        val browse = RecordingBrowseRepository()
        val viewModel = ManageChannelsViewModel(
            channelBrowseRepository = browse,
            channelPreferencesRepository = RecordingPreferencesRepository(),
            profileId = PROFILE_ID,
        )

        assertThat(browse.managementQueries).isEmpty()

        val allRows = viewModel.rowsFor(ManageChannelsFilter.ALL)
        awaitQueryCount(browse, 1)
        assertThat(viewModel.rowsFor(ManageChannelsFilter.ALL)).isSameInstanceAs(allRows)

        viewModel.setFilter(ManageChannelsFilter.VISIBLE)
        viewModel.rowsFor(viewModel.filter.value)
        awaitQueryCount(browse, 2)

        viewModel.setFilter(ManageChannelsFilter.HIDDEN)
        viewModel.rowsFor(viewModel.filter.value)
        awaitQueryCount(browse, 3)

        assertThat(browse.managementQueries.map(ChannelManagementQuery::visibility))
            .containsExactly(
                ChannelManagementVisibility.ALL,
                ChannelManagementVisibility.VISIBLE,
                ChannelManagementVisibility.HIDDEN,
            ).inOrder()
        assertThat(browse.managementQueries.all { it.profileId == PROFILE_ID }).isTrue()
    }

    @Test
    fun mutationsUseProfileScopedCanonicalIdentityAndExposeResult() = runBlocking {
        val preferences = RecordingPreferencesRepository()
        val viewModel = ManageChannelsViewModel(
            channelBrowseRepository = RecordingBrowseRepository(),
            channelPreferencesRepository = preferences,
            profileId = PROFILE_ID,
        )

        viewModel.setHidden("channel-1", true)
        awaitMutationCount(preferences, 1)
        assertThat(preferences.calls.last()).isEqualTo("hidden:$PROFILE_ID:channel-1:true")
        assertThat(viewModel.lastMutationResult.value).isEqualTo(ChannelPreferenceMutationResult.Applied)

        preferences.nextResult = ChannelPreferenceMutationResult.InvalidInput
        viewModel.setCustomName("channel-1", "   ")
        awaitMutationCount(preferences, 2)
        assertThat(preferences.calls.last()).isEqualTo("name:$PROFILE_ID:channel-1:   ")
        assertThat(viewModel.lastMutationResult.value).isEqualTo(ChannelPreferenceMutationResult.InvalidInput)

        preferences.nextResult = ChannelPreferenceMutationResult.Applied
        viewModel.setChannelNumber("channel-1", 42)
        awaitMutationCount(preferences, 3)
        assertThat(preferences.calls.last()).isEqualTo("number:$PROFILE_ID:channel-1:42")

        viewModel.resetCustomization("channel-1")
        awaitMutationCount(preferences, 4)
        assertThat(preferences.calls.last()).isEqualTo("reset:$PROFILE_ID:channel-1")
    }

    private suspend fun awaitQueryCount(repository: RecordingBrowseRepository, count: Int) {
        repeat(100) {
            if (repository.managementQueries.size >= count) return
            yield()
        }
        error("Expected $count management queries, got ${repository.managementQueries.size}")
    }

    private suspend fun awaitMutationCount(repository: RecordingPreferencesRepository, count: Int) {
        repeat(100) {
            if (repository.calls.size >= count) return
            yield()
        }
        error("Expected $count preference mutations, got ${repository.calls.size}")
    }

    private class RecordingBrowseRepository : ChannelBrowseRepository {
        val managementQueries = mutableListOf<ChannelManagementQuery>()

        override fun pages(query: ChannelBrowseQuery): Flow<PagingData<ChannelBrowseItem>> =
            flowOf(PagingData.empty())

        override fun managementPages(query: ChannelManagementQuery): Flow<PagingData<ChannelManagementItem>> {
            managementQueries += query
            return flowOf(PagingData.empty())
        }
    }

    private class RecordingPreferencesRepository : ChannelPreferencesRepository {
        val calls = mutableListOf<String>()
        var nextResult: ChannelPreferenceMutationResult = ChannelPreferenceMutationResult.Applied

        override suspend fun setFavorite(
            profileId: String,
            channelId: String,
            isFavorite: Boolean,
        ): ChannelFavoriteMutationResult = ChannelFavoriteMutationResult.Unchanged

        override suspend fun setHidden(
            profileId: String,
            channelId: String,
            isHidden: Boolean,
        ): ChannelPreferenceMutationResult {
            calls += "hidden:$profileId:$channelId:$isHidden"
            return nextResult
        }

        override suspend fun setCustomName(
            profileId: String,
            channelId: String,
            customName: String?,
        ): ChannelPreferenceMutationResult {
            calls += "name:$profileId:$channelId:$customName"
            return nextResult
        }

        override suspend fun setChannelNumber(
            profileId: String,
            channelId: String,
            channelNumber: Int?,
        ): ChannelPreferenceMutationResult {
            calls += "number:$profileId:$channelId:$channelNumber"
            return nextResult
        }

        override suspend fun resetCustomization(
            profileId: String,
            channelId: String,
        ): ChannelPreferenceMutationResult {
            calls += "reset:$profileId:$channelId"
            return nextResult
        }
    }

    private companion object {
        const val PROFILE_ID = "profile-main"
    }
}
