package app.muxtv.feature.channels

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
    fun managementFiltersCreateExplicitHiddenAwareQueriesLazily() = runBlocking {
        val browse = RecordingBrowseRepository()
        val preferences = RecordingPreferencesRepository()

        withViewModel(browse, preferences) { viewModel ->
            assertThat(browse.managementQueries).isEmpty()

            val allRows = viewModel.rowsFor(ManageChannelsFilter.ALL)
            awaitManagementQueryCount(browse, 1)
            assertThat(viewModel.rowsFor(ManageChannelsFilter.ALL)).isSameInstanceAs(allRows)

            viewModel.setFilter(ManageChannelsFilter.VISIBLE)
            viewModel.rowsFor(viewModel.filter.value)
            awaitManagementQueryCount(browse, 2)

            viewModel.setFilter(ManageChannelsFilter.HIDDEN)
            viewModel.rowsFor(viewModel.filter.value)
            awaitManagementQueryCount(browse, 3)
        }

        assertThat(browse.managementQueries.map { it.visibility })
            .containsExactly(
                ChannelManagementVisibility.ALL,
                ChannelManagementVisibility.VISIBLE,
                ChannelManagementVisibility.HIDDEN,
            ).inOrder()
        assertThat(browse.managementQueries.all { it.profileId == PROFILE_ID }).isTrue()
    }

    @Test
    fun mutationsDelegateToProfileScopedPreferencesRepository() = runBlocking {
        val browse = RecordingBrowseRepository()
        val preferences = RecordingPreferencesRepository()

        withViewModel(browse, preferences) { viewModel ->
            viewModel.setHidden("channel-a", true)
            viewModel.setCustomName("channel-a", " News HD ")
            viewModel.setChannelNumber("channel-a", 42)
            viewModel.resetCustomization("channel-a")
            repeat(20) {
                if (preferences.calls.size >= 4) return@repeat
                yield()
            }
        }

        assertThat(preferences.calls).containsExactly(
            "hidden:$PROFILE_ID:channel-a:true",
            "name:$PROFILE_ID:channel-a: News HD ",
            "number:$PROFILE_ID:channel-a:42",
            "reset:$PROFILE_ID:channel-a",
        ).inOrder()
    }

    private suspend fun awaitManagementQueryCount(repository: RecordingBrowseRepository, count: Int) {
        repeat(100) {
            if (repository.managementQueries.size >= count) return
            yield()
        }
        error("Expected $count management queries, got ${repository.managementQueries.size}")
    }

    private suspend fun withViewModel(
        browse: ChannelBrowseRepository,
        preferences: ChannelPreferencesRepository,
        block: suspend (ManageChannelsViewModel) -> Unit,
    ) {
        val store = ViewModelStore()
        val factory = viewModelFactory {
            initializer {
                ManageChannelsViewModel(
                    channelBrowseRepository = browse,
                    channelPreferencesRepository = preferences,
                    profileId = PROFILE_ID,
                )
            }
        }
        val viewModel = ViewModelProvider.create(store, factory)[ManageChannelsViewModel::class]
        try {
            block(viewModel)
        } finally {
            store.clear()
        }
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
    }
}
