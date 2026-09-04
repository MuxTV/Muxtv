package app.muxtv

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.muxtv.navigation.AppDestination
import org.junit.Rule
import org.junit.Test

class Navigation3TransitionRegressionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rapidRepeatedPopWhileFirstTransitionIsInFlightReachesHome() {
        var popOne: (() -> Unit)? = null

        composeRule.setContent {
            val backStack = rememberNavBackStack(
                AppDestination.Home,
                AppDestination.Settings,
                AppDestination.Sources,
            )
            popOne = {
                if (backStack.size > 1) {
                    backStack.removeLastOrNull()
                }
            }
            NavDisplay(
                modifier = Modifier.fillMaxSize(),
                backStack = backStack,
                onBack = { popOne?.invoke() },
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                entryProvider = { key ->
                    NavEntry(key) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag(key.transitionRegressionTag()),
                        )
                    }
                },
            )
        }

        composeRule.onNodeWithTag(SOURCES_TAG).assertExists()
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.runOnUiThread { checkNotNull(popOne).invoke() }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnUiThread { checkNotNull(popOne).invoke() }
            composeRule.mainClock.advanceTimeBy(1_000L)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HOME_TAG).assertExists()
    }

    @Test
    fun backDuringUnfinishedForwardTransitionReturnsToHome() {
        var pushSettings: (() -> Unit)? = null
        var popOne: (() -> Unit)? = null

        composeRule.setContent {
            val backStack = rememberNavBackStack(AppDestination.Home)
            pushSettings = {
                if (backStack.lastOrNull() != AppDestination.Settings) {
                    backStack.add(AppDestination.Settings)
                }
            }
            popOne = {
                if (backStack.size > 1) {
                    backStack.removeLastOrNull()
                }
            }
            NavDisplay(
                modifier = Modifier.fillMaxSize(),
                backStack = backStack,
                onBack = { popOne?.invoke() },
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                entryProvider = { key ->
                    NavEntry(key) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag(key.transitionRegressionTag()),
                        )
                    }
                },
            )
        }

        composeRule.onNodeWithTag(HOME_TAG).assertExists()
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.runOnUiThread { checkNotNull(pushSettings).invoke() }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnUiThread { checkNotNull(popOne).invoke() }
            composeRule.mainClock.advanceTimeBy(1_000L)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HOME_TAG).assertExists()
    }
}

private fun NavKey.transitionRegressionTag(): String = when (this) {
    AppDestination.Home -> HOME_TAG
    AppDestination.Settings -> SETTINGS_TAG
    AppDestination.Sources -> SOURCES_TAG
    else -> "navigation3-transition-other"
}

private const val HOME_TAG = "navigation3-transition-home"
private const val SETTINGS_TAG = "navigation3-transition-settings"
private const val SOURCES_TAG = "navigation3-transition-sources"
