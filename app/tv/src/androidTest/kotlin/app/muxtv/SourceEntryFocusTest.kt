package app.muxtv

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.muxtv.catalog.refresh.RemoteSourceActivationFailure
import app.muxtv.catalog.refresh.RemoteSourceActivationResult
import app.muxtv.catalog.refresh.RemoteSourceCancellationResult
import app.muxtv.catalog.refresh.RemoteSourceOnboardingInput
import app.muxtv.catalog.refresh.RemoteSourcePreparationResult
import app.muxtv.catalog.refresh.RemoteSourcePreparationToken
import app.muxtv.catalog.sync.SourceRefreshScheduler
import app.muxtv.database.SourceRefreshAttempt
import app.muxtv.database.SourceRefreshCompletion
import app.muxtv.database.SourceRefreshOverview
import app.muxtv.database.SourceRefreshPolicy
import app.muxtv.database.SourceRefreshStatus
import app.muxtv.database.SourceRefreshStore
import app.muxtv.database.SourceRefreshTarget
import app.muxtv.database.SourceRefreshTrigger
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.feature.sources.AddSourceRoute
import app.muxtv.feature.sources.SourceEntryOnboarding
import app.muxtv.feature.sources.SourcesRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

class SourceEntryFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptySourcesStartsOnAddSource() {
        val scheduler = SourceRefreshScheduler(
            context = ApplicationProvider.getApplicationContext(),
            refreshStore = EmptySourceRefreshStore,
        )
        composeRule.setContent {
            MuxTvTheme {
                SourcesRoute(
                    refreshStore = EmptySourceRefreshStore,
                    refreshScheduler = scheduler,
                    onAddSource = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("sources-add").assertIsFocused()
    }

    @Test
    fun editingStartsOnSourceName() {
        composeRule.setContent {
            MuxTvTheme {
                AddSourceRoute(
                    onboarding = HttpApprovalOnboarding,
                    onCompleted = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("source-name").assertIsFocused()
    }

    @Test
    fun httpWarningFocusesSafeCancelAction() {
        composeRule.setContent {
            MuxTvTheme {
                AddSourceRoute(
                    onboarding = HttpApprovalOnboarding,
                    onCompleted = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("source-locator")
            .performClick()
            .performTextInput("http://192.168.1.10/list.m3u")
        composeRule.onNodeWithText("Проверить").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("source-http-cancel").assertIsFocused()
    }
}

private object HttpApprovalOnboarding : SourceEntryOnboarding {
    override suspend fun prepare(
        input: RemoteSourceOnboardingInput,
    ): RemoteSourcePreparationResult =
        RemoteSourcePreparationResult.InsecureTransportApprovalRequired

    override suspend fun activate(
        token: RemoteSourcePreparationToken,
        sourceName: String,
    ): RemoteSourceActivationResult = RemoteSourceActivationResult.Failed(
        failure = RemoteSourceActivationFailure.Unexpected,
        credentialCleanupFailure = null,
        sourceCleanupFailure = null,
    )

    override suspend fun cancel(
        token: RemoteSourcePreparationToken,
    ): RemoteSourceCancellationResult = RemoteSourceCancellationResult.NotFound

    override suspend fun restoreLatestPrepared(): RemoteSourcePreparationResult.Prepared? = null
}

private object EmptySourceRefreshStore : SourceRefreshStore {
    override suspend fun getTarget(sourceId: String): SourceRefreshTarget? = null

    override fun observeOverviews(): Flow<List<SourceRefreshOverview>> = flowOf(emptyList())

    override suspend fun getPolicies(): List<SourceRefreshPolicy> = emptyList()

    override suspend fun upsertPolicy(policy: SourceRefreshPolicy) = Unit

    override suspend fun removePolicy(sourceId: String) = Unit

    override fun observeStatus(sourceId: String): Flow<SourceRefreshStatus?> = flowOf(null)

    override suspend fun getRecentAttempts(
        sourceId: String,
        limit: Int,
    ): List<SourceRefreshAttempt> = emptyList()

    override suspend fun tryAcquire(
        sourceId: String,
        runToken: String,
        startedAtEpochMillis: Long,
        staleBeforeEpochMillis: Long,
    ): Boolean = false

    override suspend fun complete(
        sourceId: String,
        runToken: String,
        trigger: SourceRefreshTrigger,
        completion: SourceRefreshCompletion,
    ) = Unit
}
