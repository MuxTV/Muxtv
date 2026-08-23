package app.muxtv

import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.fetchSemanticsNodes
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
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
    private val device = UiDevice.getInstance(instrumentation)
    private val arguments: Bundle = InstrumentationRegistry.getArguments()

    @Test
    fun capturesSharedShellGeometryAndFocusTrace() {
        val outputDirectory = requireNotNull(
            instrumentation.targetContext.getExternalFilesDir("ui-characterization"),
        ).apply {
            deleteRecursively()
            mkdirs()
        }

        val root = JSONObject()
            .put("schemaVersion", 1)
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
            anchor = Anchor.Title("Настройки"),
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

        // RequestFocus is only a deterministic setup seam. The transition into/out of the rail
        // itself uses the native UiDevice DPAD path that the product receives on Android TV.
        runCatching {
            anchorInteraction.requestFocus()
            composeRule.waitForIdle()
        }
        val focusBeforeLeft = focusedNodeDescription()
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_LEFT)
        composeRule.waitForIdle()
        val duringBounds = anchorInteraction.bounds()
        val railBounds = composeRule.onNodeWithTag(navTag, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val focusOnRail = focusedNodeDescription()
        screenshot(outputDirectory, "$destination-rail")

        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.waitForIdle()
        val afterBounds = anchorInteraction.bounds()
        val focusAfterRight = focusedNodeDescription()
        screenshot(outputDirectory, "$destination-after")

        val entry = JSONObject()
            .put("destination", destination)
            .put("navTag", navTag)
            .put("anchor", anchor.description)
            .put("beforeBounds", beforeBounds.toJson())
            .put("duringRailBounds", duringBounds.toJson())
            .put("afterBounds", afterBounds.toJson())
            .put("railBounds", railBounds.toJson())
            .put("focusInitial", beforeFocus)
            .put("focusBeforeLeft", focusBeforeLeft)
            .put("focusOnRail", focusOnRail)
            .put("focusAfterRight", focusAfterRight)
            .put("contentOriginStableDuringRail", beforeBounds.left == duringBounds.left)
            .put("contentOriginRestored", beforeBounds.left == afterBounds.left)

        root.getJSONArray("destinations").put(entry)
    }

    private fun openRailDestination(navTag: String) {
        composeRule.onNodeWithTag(navTag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        device.pressKeyCode(KeyEvent.KEYCODE_ENTER)
        composeRule.waitForIdle()
    }

    private fun awaitHomePrimary(): String {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            nodeExists("home-add-source") || nodeExists("home-hero")
        }
        composeRule.waitForIdle()
        return if (nodeExists("home-add-source")) "home-add-source" else "home-hero"
    }

    private fun nodeExists(tag: String): Boolean = runCatching {
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        true
    }.getOrDefault(false)

    private fun Anchor.resolve(): NodeHandle = when (this) {
        is Anchor.Tag -> NodeHandle(
            description = "tag:$tag",
            boundsProvider = {
                composeRule.onNodeWithTag(tag, useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
            },
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

    private fun focusedNodeDescription(): String? {
        val rootNode = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val focused = rootNode.depthFirst()
            .firstOrNull { node -> node.config.getOrNull(SemanticsProperties.Focused) == true }
            ?: return null
        return focused.config.getOrNull(SemanticsProperties.TestTag)
            ?: focused.config.toString()
    }

    private fun SemanticsNode.depthFirst(): Sequence<SemanticsNode> = sequence {
        yield(this@depthFirst)
        children.forEach { child -> yieldAll(child.depthFirst()) }
    }

    private fun screenshot(directory: File, name: String) {
        val file = File(directory, "$name.png")
        check(device.takeScreenshot(file)) { "Unable to capture screenshot $name" }
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
