package app.muxtv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
    fun launchesHomeShellWithPrimaryNavigation() {
        composeRule.onNodeWithText("Главная", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Каналы")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun opensGuideFromPrimaryNavigationWithDpad() {
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav-home")
            .assertIsFocused()
            .press(Key.DirectionRight, count = 2)
        composeRule.onNodeWithTag("nav-guide")
            .assertIsFocused()
            .press(Key.Enter)

        composeRule.onNodeWithText("Телепрограмма")
            .assertIsDisplayed()
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
