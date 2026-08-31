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
class ChannelManagementViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun visibilityStreamsAreCreatedLazilyAndCachedPerVisibility() = runBlocking {
        val browse = RecordingBrowseRepository()
        withViewModel(browse, RecordingPreferencesRepository()) { viewModel ->
            assertThat(browse.managementQueries).isEmpty()

            val allRows = viewModel.rowsFor(ChannelManagementVisibility.ALL)
            awaitManagementQueryCount(browse, 1)
            assertThat(viewModel.rowsFor(ChannelManagementVisibility.ALL)).isSameInstanceAs(allRows)

            viewModel.setVisibility(ChannelManagementVisibility.HIDDEN)
            viewModel.rowsFor(viewModel.visibility.value)
            awaitManagementQueryCount(browse, 2)
        }

        assertThat(browse.managementQueries.map { it.visibility })
            .containsExactly(
                ChannelManagementVisibility.ALL,
                ChannelManagementVisibility.HIDDEN,
            ).inOrder()
        assertThat(browse.managementQueries.all { it.profileId == PROFILE_ID }).isTrue()
    }

    @Test
    fun hideActionUsesProfileAndCanonicalIdentityAndClosesPanelAfterApply() = runBlocking {
        val preferences = RecordingPreferencesRepository()
        withViewModel(RecordingBrowseRepository(), preferences) { viewModel ->
            viewModel.openActions(item(isHidden = false))
            viewModel.toggleHidden()
            yield()

            assertThat(preferences.hiddenCalls)
                .containsExactly(HiddenCall(PROFILE_ID, CHANNEL_ID, true))
            assertThat(viewModel.uiState.value.panel).isEqualTo(ChannelManagementPanel.Closed)
            assertThat(viewModel.uiState.value.failure).isNull()
        }
    }

    @Test
    fun renamePassesDraftToRepositoryAndKeepsEditorOpenOnInvalidInput() = runBlocking {
        val preferences = RecordingPreferencesRepository().apply {
            customNameResult = ChannelPreferenceMutationResult.InvalidInput
        }
        withViewModel(RecordingBrowseRepository(), preferences) { viewModel ->
            viewModel.openRename(item())
            viewModel.updateRenameDraft("  Renamed channel  ")
            viewModel.saveRename()
            yield()

            assertThat(preferences.customNameCalls)
                .containsExactly(CustomNameCall(PROFILE_ID, CHANNEL_ID, "  Renamed channel  "))
            assertThat(viewModel.uiState.value.panel).isInstanceOf(ChannelManagementPanel.Rename::class.java)
            assertThat(viewModel.uiState.value.failure).isEqualTo(ChannelManagementFailure.InvalidInput)
        }
    }

    @Test
    fun numberEditorRejectsNonNumericDraftBeforeRepositoryMutation() = runBlocking {
        val preferences = RecordingPreferencesRepository()
        withViewModel(RecordingBrowseRepository(), preferences) { viewModel ->
            viewModel.openNumber(item())
            viewModel.updateNumberDraft("7a")
            viewModel.saveNumber()
            yield()

            assertThat(preferences.channelNumberCalls).isEmpty()
            assertThat(viewModel.uiState.value.failure).isEqualTo(ChannelManagementFailure.InvalidInput)
        }
    }

    @Test
    fun resetCustomizationUsesCanonicalIdentityAndPreservesPanelOnNotFound() = runBlocking {
        val preferences = RecordingPreferencesRepository().apply {
            resetResult = ChannelPreferenceMutationResult.NotFound
        }
        withViewModel(RecordingBrowseRepository(), preferences) { viewModel ->
            viewModel.openActions(item())
            viewModel.resetCustomization()
            yield()

            assertThat(preferences.resetCalls).containsExactly(PROFILE_ID to CHANNEL_ID)
            assertThat(viewModel.uiState.value.panel).isInstanceOf(ChannelManagementPanel.Actions::class.java)
            assertThat(viewModel.uiState.value.failure).isEqualTo(ChannelManagementFailure.NotFound)
        }
    }

    private suspend fun awaitManagementQueryCount(repository: RecordingBrowseRepository, count: Int) {
        repeat(100) {
            if (repository.managementQueries.size >= count) return
            yield()
        }
        error("Expected $count management queries, got ${repository.managementQueries.size}")
    }

    private suspend fun withViewModel(
        browseRepository: ChannelBrowseRepository,
        preferencesRepository: ChannelPreferencesRepository,
        block: suspend (ChannelManagementViewModel) -> Unit,
    ) {
        val store = ViewModelStore()
        val factory = viewModelFactory {
            initializer {
                ChannelManagementViewModel(
                    channelBrowseRepository = browseRepository,
                    channelPreferencesRepository = preferencesRepository,
                    profileId = PROFILE_ID,
                )
            }
        }
        val viewModel = ViewModelProvider.create(store, factory)[ChannelManagementViewModel::class]
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
            return flowOf(PagingData.from(listOf(item())))
        }
    }

    private class RecordingPreferencesRepository : ChannelPreferencesRepository {
        val hiddenCalls = mutableListOf<HiddenCall>()
        val customNameCalls = mutableListOf<CustomNameCall>()
        val channelNumberCalls = mutableListOf<ChannelNumberCall>()
        val resetCalls = mutableListOf<Pair<String, String>>()
        var hiddenResult = ChannelPreferenceMutationResult.Applied
        var customNameResult = ChannelPreferenceMutationResult.Applied
        var channelNumberResult = ChannelPreferenceMutationResult.Applied
        var resetResult = ChannelPreferenceMutationResult.Applied

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
            hiddenCalls += HiddenCall(profileId, channelId, isHidden)
            return hiddenResult
        }

        override suspend fun setCustomName(
            profileId: String,
            channelId: String,
            customName: String?,
        ): ChannelPreferenceMutationResult {
            customNameCalls += CustomNameCall(profileId, channelId, customName)
            return customNameResult
        }

        override suspend fun setChannelNumber(
            profileId: String,
            channelId: String,
            channelNumber: Int?,
        ): ChannelPreferenceMutationResult {
            channelNumberCalls += ChannelNumberCall(profileId, channelId, channelNumber)
            return channelNumberResult
        }

        override suspend fun resetCustomization(
            profileId: String,
            channelId: String,
        ): ChannelPreferenceMutationResult {
            resetCalls += profileId to channelId
            return resetResult
        }
    }

    private data class HiddenCall(val profileId: String, val channelId: String, val isHidden: Boolean)
    private data class CustomNameCall(val profileId: String, val channelId: String, val customName: String?)
    private data class ChannelNumberCall(val profileId: String, val channelId: String, val channelNumber: Int?)

    private companion object {
        const val PROFILE_ID = "profile-main"
        const val CHANNEL_ID = "channel-1"

        fun item(isHidden: Boolean = false) = ChannelManagementItem(
            channelId = CHANNEL_ID,
            canonicalDisplayName = "Original",
            effectiveDisplayName = "Original",
            defaultChannelNumber = "10",
            customChannelNumber = null,
            effectiveChannelNumber = "10",
            isFavorite = false,
            isHidden = isHidden,
            variantCount = 1,
        )
    }
}
