package app.muxtv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesHomeWithRailAndContentFocus() {
        composeRule.onNodeWithTag("nav-home").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("home-hero").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("home-add-source").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun opensGuideFromRailWithDpad() {
        composeRule.waitForIdle()
        val homePrimary = awaitHomePrimary()

        composeRule.onNodeWithTag(homePrimary)
            .assertIsFocused()
            .press(Key.DirectionLeft)
        composeRule.onNodeWithTag("nav-home")
            .assertIsFocused()
            .press(Key.DirectionDown, count = 2)
        composeRule.onNodeWithTag("nav-guide")
            .assertIsFocused()
            .press(Key.Enter)

        composeRule.onNodeWithText("Телепрограмма").assertIsDisplayed()
    }

    @Test
    fun opensDoctorThroughSettingsWithDpad() {
        composeRule.waitForIdle()
        val homePrimary = awaitHomePrimary()

        composeRule.onNodeWithTag(homePrimary)
            .assertIsFocused()
            .press(Key.DirectionLeft)
        composeRule.onNodeWithTag("nav-home")
            .assertIsFocused()
            .press(Key.DirectionDown, count = 4)
        composeRule.onNodeWithTag("nav-settings")
            .assertIsFocused()
            .press(Key.Enter)

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings-section-sources").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("settings-section-sources")
            .assertIsFocused()
            .press(Key.DirectionDown)
        composeRule.onNodeWithTag("settings-section-doctor")
            .assertIsFocused()
            .press(Key.Enter)

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("doctor-refresh").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("doctor-title").assertIsDisplayed()
        composeRule.onNodeWithTag("doctor-refresh").assertIsFocused()
    }

    private fun awaitHomePrimary(): String {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("home-hero").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("home-add-source").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        return if (composeRule.onAllNodesWithTag("home-add-source").fetchSemanticsNodes().isNotEmpty()) {
            "home-add-source"
        } else {
            "home-hero"
        }
    }
}

private fun SemanticsNodeInteraction.press(
    key: Key,
    count: Int = 1,
): SemanticsNodeInteraction = apply {
    performKeyInput {
        repeat(count) {
            keyDown(key)
            keyUp(key)
        }
    }
}
