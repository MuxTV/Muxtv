package app.muxtv

import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Repository-owned U0 characterization probe.
 *
 * IMPORTANT: this source is copied byte-for-byte into each immutable A/B/C worktree by
 * Invoke-TvUiCharacterization.ps1. Keep it dependent only on APIs already present in all three
 * comparison refs. The probe records observations; it intentionally does not encode the expected
 * Lounge geometry so current regressions remain measurable instead of being hidden by assertions.
 */
@RunWith(AndroidJUnit4::class)
class UiCharacterizationProbeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments: Bundle = InstrumentationRegistry.getArguments()

    /**
     * Focus discovery deliberately uses only stable product test tags plus assertIsFocused().
     * This is the lowest-common-denominator API already exercised by the immutable A/B/C tests;
     * it avoids newer SemanticsConfiguration and single-node fetch extensions.
     */
    private val knownFocusTags = listOf(
        "nav-home",
        "nav-channels",
        "nav-guide",
        "nav-search",
        "nav-settings",
        "home-add-source",
        "home-hero",
        "channels-filter-all",
        "channels-filter-favorites",
        "channels-filter-recent",
        "channel-row-0",
        "settings-section-sources",
        "settings-section-doctor",
    )

    @Test
    fun capturesSharedShellGeometryAndFocusTrace() {
        val outputDirectory = requireNotNull(
            instrumentation.targetContext.getExternalFilesDir("ui-characterization"),
        ).apply {
            deleteRecursively()
            mkdirs()
        }

        val root = JSONObject()
            .put("schemaVersion", 2)
            .put("sourceCommit", arguments.getString("sourceCommit") ?: "unknown")
            .put("displayProfile", arguments.getString("displayProfile") ?: "unknown")
            .put("displayWidthPx", arguments.getString("displayWidthPx")?.toIntOrNull())
            .put("displayHeightPx", arguments.getString("displayHeightPx")?.toIntOrNull())
            .put("displayDensityDpi", arguments.getString("displayDensityDpi")?.toIntOrNull())
            .put("destinations", JSONArray())

        composeRule.waitForIdle()
        val homePrimary = awaitHomePrimary()

        captureDestination(
            outputDirectory = outputDirectory,
            root = root,
            destination = "home",
            navTag = "nav-home",
            anchor = Anchor.Tag(homePrimary),
            navigate = null,
        )
        captureDestination(
            outputDirectory = outputDirectory,
            root = root,
            destination = "channels",
            navTag = "nav-channels",
            anchor = Anchor.Title("Все каналы"),
            navigate = { openRailDestination("nav-channels") },
        )
        captureDestination(
            outputDirectory = outputDirectory,
            root = root,
            destination = "guide",
            navTag = "nav-guide",
            anchor = Anchor.Title("Телепрограмма"),
            navigate = { openRailDestination("nav-guide") },
        )
        captureDestination(
            outputDirectory = outputDirectory,
            root = root,
            destination = "search",
            navTag = "nav-search",
            anchor = Anchor.Title("Поиск"),
            navigate = { openRailDestination("nav-search") },
        )
        captureDestination(
            outputDirectory = outputDirectory,
            root = root,
            destination = "settings",
            navTag = "nav-settings",
            anchor = Anchor.Tag("settings-section-sources"),
            navigate = { openRailDestination("nav-settings") },
        )

        File(outputDirectory, "semantics-tree.txt").writeText(
            composeRule.onRoot(useUnmergedTree = true).printToString(maxDepth = 100),
        )
        File(outputDirectory, "probe-result.json").writeText(root.toString(2))
    }

    private fun captureDestination(
        outputDirectory: File,
        root: JSONObject,
        destination: String,
        navTag: String,
        anchor: Anchor,
        navigate: (() -> Unit)?,
    ) {
        navigate?.invoke()
        composeRule.waitForIdle()

        val anchorInteraction = anchor.resolve()
        val beforeBounds = anchorInteraction.bounds()
        val beforeFocus = focusedNodeDescription()
        screenshot(outputDirectory, "$destination-before")

        // RequestFocus is only a deterministic setup seam. Movement itself goes through the
        // Android framework Instrumentation input path, so historical refs need no UiAutomator
        // dependency or build-file mutation.
        runCatching {
            anchorInteraction.requestFocus()
            composeRule.waitForIdle()
        }
        val focusBeforeLeft = focusedNodeDescription()

        // Sequence 1: content -> rail -> Back. This characterizes AppNavigation's BackHandler
        // contract independently from the ordinary Right movement path.
        pressKey(KeyEvent.KEYCODE_DPAD_LEFT)
        composeRule.waitForIdle()
        val duringBackRailBounds = anchorInteraction.bounds()
        val railBounds = boundsForUniqueTag(navTag)
        val focusOnRailBeforeBack = focusedNodeDescription()
        screenshot(outputDirectory, "$destination-rail-before-back")

        pressKey(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
        val afterBackBounds = anchorInteraction.bounds()
        val focusAfterBack = focusedNodeDescription()
        screenshot(outputDirectory, "$destination-after-back")

        // Sequence 2: content -> rail -> Right. Re-seed the content anchor when it is explicitly
        // focusable so the Right trace cannot inherit accidental state from the Back sequence.
        runCatching {
            anchorInteraction.requestFocus()
            composeRule.waitForIdle()
        }
        val focusBeforeSecondLeft = focusedNodeDescription()
        pressKey(KeyEvent.KEYCODE_DPAD_LEFT)
        composeRule.waitForIdle()
        val duringRightRailBounds = anchorInteraction.bounds()
        val focusOnRailBeforeRight = focusedNodeDescription()
        screenshot(outputDirectory, "$destination-rail-before-right")

        pressKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.waitForIdle()
        val afterRightBounds = anchorInteraction.bounds()
        val focusAfterRight = focusedNodeDescription()
        screenshot(outputDirectory, "$destination-after-right")

        val entry = JSONObject()
            .put("destination", destination)
            .put("navTag", navTag)
            .put("anchor", anchor.description)
            .put("anchorHasExplicitFocusAction", anchorInteraction.hasFocusAction)
            .put("beforeBounds", beforeBounds.toJson())
            .put("duringRailBounds", duringRightRailBounds.toJson())
            .put("duringBackRailBounds", duringBackRailBounds.toJson())
            .put("duringRightRailBounds", duringRightRailBounds.toJson())
            .put("afterBounds", afterRightBounds.toJson())
            .put("afterBackBounds", afterBackBounds.toJson())
            .put("afterRightBounds", afterRightBounds.toJson())
            .put("railBounds", railBounds.toJson())
            .put("focusInitial", beforeFocus)
            .put("focusBeforeLeft", focusBeforeLeft)
            .put("focusOnRail", focusOnRailBeforeRight)
            .put("focusBeforeBack", focusOnRailBeforeBack)
            .put("focusAfterBack", focusAfterBack)
            .put("focusBeforeSecondLeft", focusBeforeSecondLeft)
            .put("focusOnRailBeforeRight", focusOnRailBeforeRight)
            .put("focusAfterRight", focusAfterRight)
            .put("backReachedExpectedRailItem", focusOnRailBeforeBack == navTag)
            .put("backMovedFocusAwayFromRail", focusOnRailBeforeBack == navTag && focusAfterBack != navTag)
            .put("rightReachedExpectedRailItem", focusOnRailBeforeRight == navTag)
            .put("rightMovedFocusAwayFromRail", focusOnRailBeforeRight == navTag && focusAfterRight != navTag)
            .put("contentOriginStableDuringRail", beforeBounds.left == duringRightRailBounds.left)
            .put("contentOriginStableDuringBackRail", beforeBounds.left == duringBackRailBounds.left)
            .put("contentOriginRestored", beforeBounds.left == afterRightBounds.left)
            .put("contentOriginRestoredAfterBack", beforeBounds.left == afterBackBounds.left)
            .put("contentOriginRestoredAfterRight", beforeBounds.left == afterRightBounds.left)

        root.getJSONArray("destinations").put(entry)
    }

    private fun openRailDestination(navTag: String) {
        composeRule.onNodeWithTag(navTag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        pressKey(KeyEvent.KEYCODE_ENTER)
        composeRule.waitForIdle()
    }

    private fun pressKey(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
    }

    private fun awaitHomePrimary(): String {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            nodeExists("home-add-source") || nodeExists("home-hero")
        }
        composeRule.waitForIdle()
        return if (nodeExists("home-add-source")) "home-add-source" else "home-hero"
    }

    private fun nodeExists(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    private fun boundsForUniqueTag(tag: String): Rect {
        val nodes = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        check(nodes.size == 1) { "Expected one semantics node for tag '$tag', got ${nodes.size}." }
        return nodes.single().boundsInRoot
    }

    private fun Anchor.resolve(): NodeHandle = when (this) {
        is Anchor.Tag -> NodeHandle(
            description = "tag:$tag",
            boundsProvider = { boundsForUniqueTag(tag) },
            focusAction = {
                composeRule.onNodeWithTag(tag, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.RequestFocus)
            },
        )

        is Anchor.Title -> NodeHandle(
            description = "title:$text",
            boundsProvider = {
                val nodes = composeRule.onAllNodesWithText(
                    text = text,
                    substring = false,
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                check(nodes.isNotEmpty()) { "No semantics node found for title '$text'." }
                // Rail labels can share the route name. The actual content title is the
                // right-most match, independent of whether the rail label is currently visible.
                nodes.maxBy { it.boundsInRoot.left }.boundsInRoot
            },
            focusAction = null,
        )
    }

    private fun focusedNodeDescription(): String? = knownFocusTags.firstOrNull { tag ->
        if (!nodeExists(tag)) {
            false
        } else {
            runCatching {
                composeRule.onNodeWithTag(tag, useUnmergedTree = true).assertIsFocused()
                true
            }.getOrDefault(false)
        }
    }

    private fun screenshot(directory: File, name: String) {
        val file = File(directory, "$name.png")
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Unable to capture screenshot $name"
        }
        try {
            FileOutputStream(file).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Unable to encode screenshot $name"
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private sealed interface Anchor {
        val description: String

        data class Tag(val tag: String) : Anchor {
            override val description: String = "tag:$tag"
        }

        data class Title(val text: String) : Anchor {
            override val description: String = "title:$text"
        }
    }

    private data class NodeHandle(
        val description: String,
        val boundsProvider: () -> Rect,
        val focusAction: (() -> Unit)?,
    ) {
        val hasFocusAction: Boolean get() = focusAction != null
        fun bounds(): Rect = boundsProvider()
        fun requestFocus() {
            focusAction?.invoke()
        }
    }

    private fun Rect.toJson(): JSONObject = JSONObject()
        .put("left", left.toDouble())
        .put("top", top.toDouble())
        .put("right", right.toDouble())
        .put("bottom", bottom.toDouble())
        .put("width", width.toDouble())
        .put("height", height.toDouble())
}
