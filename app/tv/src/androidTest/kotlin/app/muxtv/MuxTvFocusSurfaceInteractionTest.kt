package app.muxtv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.unit.dp
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.designsystem.component.MuxTvFocusSurface
import org.junit.Rule
import org.junit.Test

class MuxTvFocusSurfaceInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dpadCenterShortPressInvokesExactlyOneClick() {
        var clickCount = 0
        val requester = FocusRequester()

        composeRule.setContent {
            MuxTvTheme {
                MuxTvFocusSurface(
                    onClick = { clickCount += 1 },
                    modifier = Modifier
                        .testTag(FIRST_TAG)
                        .focusRequester(requester),
                ) { }
            }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                requester.requestFocus()
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(FIRST_TAG)
            .assertIsFocused()
            .performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }

        composeRule.runOnIdle {
            check(clickCount == 1) { "Expected one click, got $clickCount" }
        }
    }

    @Test
    fun rapidDirectionalInputEndsOnLatestRequestedSurfaceWithoutActivation() {
        var clickCount = 0
        val requester = FocusRequester()

        composeRule.setContent {
            MuxTvTheme {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    repeat(3) { index ->
                        MuxTvFocusSurface(
                            onClick = { clickCount += 1 },
                            modifier = Modifier
                                .testTag("$ITEM_TAG_PREFIX$index")
                                .then(
                                    if (index == 0) Modifier.focusRequester(requester) else Modifier,
                                ),
                        ) { }
                    }
                }
            }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                requester.requestFocus()
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("${ITEM_TAG_PREFIX}0")
            .assertIsFocused()
            .performKeyInput {
                keyDown(Key.DirectionRight)
                keyUp(Key.DirectionRight)
                keyDown(Key.DirectionRight)
                keyUp(Key.DirectionRight)
            }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("${ITEM_TAG_PREFIX}2").assertIsFocused()
        composeRule.runOnIdle {
            check(clickCount == 0) { "Directional focus unexpectedly activated $clickCount actions" }
        }
    }

    private companion object {
        const val FIRST_TAG = "shared-focus-first"
        const val ITEM_TAG_PREFIX = "shared-focus-item-"
    }
}
