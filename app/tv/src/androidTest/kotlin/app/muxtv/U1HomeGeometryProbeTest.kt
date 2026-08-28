package app.muxtv

import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Post-U1 Home measurement probe.
 *
 * This intentionally reuses the U0 evidence vocabulary for the actual empty-state MainActivity:
 * before/during/after bounds plus framework Left/Back/Right focus movement. It records absolute
 * geometry for comparison with the immutable U0 corpus instead of encoding historical A geometry
 * as a product assertion.
 */
@RunWith(AndroidJUnit4::class)
class U1HomeGeometryProbeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments: Bundle = InstrumentationRegistry.getArguments()

    @Test
    fun capturesPostShellHomeGeometryWithFrameworkFocusTrace() {
        val outputDirectory = requireNotNull(
            instrumentation.targetContext.getExternalFilesDir("u1-home-geometry"),
        ).apply {
            deleteRecursively()
            mkdirs()
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("home-add-source", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size == 1
        }
        composeRule.waitForIdle()

        val anchor = composeRule.onNodeWithTag("home-add-source", useUnmergedTree = true)
        requestFocus(anchor)
        val beforeBounds = anchor.bounds()
        val focusBeforeLeft = focusedTag()

        pressFrameworkKey(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitFocus("nav-home")
        val duringBackRailBounds = anchor.bounds()
        val railBoundsDuringBack = composeRule.onNodeWithTag("nav-home", useUnmergedTree = true).bounds()
        val focusBeforeBack = focusedTag()

        pressFrameworkKey(KeyEvent.KEYCODE_BACK)
        awaitFocus("home-add-source")
        val afterBackBounds = anchor.bounds()
        val focusAfterBack = focusedTag()

        requestFocus(anchor)
        val focusBeforeSecondLeft = focusedTag()
        pressFrameworkKey(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitFocus("nav-home")
        val duringRightRailBounds = anchor.bounds()
        val railBounds = composeRule.onNodeWithTag("nav-home", useUnmergedTree = true).bounds()
        val focusOnRailBeforeRight = focusedTag()

        pressFrameworkKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus("home-add-source")
        val afterRightBounds = anchor.bounds()
        val focusAfterRight = focusedTag()

        val contentOriginStableDuringBackRail = beforeBounds.left == duringBackRailBounds.left
        val contentOriginStableDuringRail = beforeBounds.left == duringRightRailBounds.left
        val contentOriginRestoredAfterBack = beforeBounds.left == afterBackBounds.left
        val contentOriginRestoredAfterRight = beforeBounds.left == afterRightBounds.left

        val result = JSONObject()
            .put("schemaVersion", 2)
            .put("sourceCommit", arguments.getString("sourceCommit") ?: "unknown")
            .put("displayProfile", arguments.getString("displayProfile") ?: "unknown")
            .put("displayWidthPx", arguments.getString("displayWidthPx")?.toIntOrNull())
            .put("displayHeightPx", arguments.getString("displayHeightPx")?.toIntOrNull())
            .put("displayDensityDpi", arguments.getString("displayDensityDpi")?.toIntOrNull())
            .put("destination", "home")
            .put("anchor", "tag:home-add-source")
            .put("beforeBounds", beforeBounds.toJson())
            .put("duringRailBounds", duringRightRailBounds.toJson())
            .put("duringBackRailBounds", duringBackRailBounds.toJson())
            .put("duringRightRailBounds", duringRightRailBounds.toJson())
            .put("afterBackBounds", afterBackBounds.toJson())
            .put("afterRightBounds", afterRightBounds.toJson())
            .put("railBounds", railBounds.toJson())
            .put("railBoundsDuringBack", railBoundsDuringBack.toJson())
            .put("focusBeforeLeft", focusBeforeLeft)
            .put("focusBeforeBack", focusBeforeBack)
            .put("focusAfterBack", focusAfterBack)
            .put("focusBeforeSecondLeft", focusBeforeSecondLeft)
            .put("focusOnRailBeforeRight", focusOnRailBeforeRight)
            .put("focusAfterRight", focusAfterRight)
            .put("backReachedExpectedRailItem", focusBeforeBack == "nav-home")
            .put("backMovedFocusAwayFromRail", focusBeforeBack == "nav-home" && focusAfterBack == "home-add-source")
            .put("rightReachedExpectedRailItem", focusOnRailBeforeRight == "nav-home")
            .put("rightMovedFocusAwayFromRail", focusOnRailBeforeRight == "nav-home" && focusAfterRight == "home-add-source")
            .put("contentOriginStableDuringRail", contentOriginStableDuringRail)
            .put("contentOriginStableDuringBackRail", contentOriginStableDuringBackRail)
            .put("contentOriginRestoredAfterBack", contentOriginRestoredAfterBack)
            .put("contentOriginRestoredAfterRight", contentOriginRestoredAfterRight)

        File(outputDirectory, "probe-result.json").writeText(result.toString(2))

        check(contentOriginStableDuringRail) {
            "Home content origin changed while rail owned focus: before=${beforeBounds.left}, during=${duringRightRailBounds.left}"
        }
        check(contentOriginStableDuringBackRail) {
            "Home content origin changed in the measured Left/Back trace: before=${beforeBounds.left}, during=${duringBackRailBounds.left}"
        }
        check(contentOriginRestoredAfterBack) {
            "Home content origin did not restore after framework Back: before=${beforeBounds.left}, after=${afterBackBounds.left}"
        }
        check(contentOriginRestoredAfterRight) {
            "Home content origin did not restore after framework Right: before=${beforeBounds.left}, after=${afterRightBounds.left}"
        }
    }

    private fun requestFocus(node: SemanticsNodeInteraction) {
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        node.assertIsFocused()
    }

    private fun pressFrameworkKey(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        composeRule.waitForIdle()
    }

    private fun awaitFocus(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag(tag, useUnmergedTree = true).assertIsFocused()
                true
            }.getOrDefault(false)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).assertIsFocused()
    }

    private fun focusedTag(): String? =
        listOf("home-add-source", "nav-home").firstOrNull { tag ->
            runCatching {
                composeRule.onNodeWithTag(tag, useUnmergedTree = true).assertIsFocused()
                true
            }.getOrDefault(false)
        }

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    private fun Rect.toJson(): JSONObject = JSONObject()
        .put("left", left)
        .put("top", top)
        .put("right", right)
        .put("bottom", bottom)
        .put("width", width)
        .put("height", height)
}
