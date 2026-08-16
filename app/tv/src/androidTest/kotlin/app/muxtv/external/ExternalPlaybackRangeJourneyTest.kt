package app.muxtv.external

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.testing.http.RangeMediaServer
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * EP-08 journey evidence: a TorrServe-style `ACTION_VIEW` intent against a real byte-range HTTP
 * origin, through the real [ExternalPlaybackActivity] and the real playback service.
 *
 * First rendered frame requires decodable video, so the media is encoded on the device itself
 * ([OnDeviceVideoFixture]); on images without an H.264 codec the test skips with an explicit
 * message instead of silently passing. The `external-surface` is causally post-first-frame rather
 * than a bare Compose-presence proxy: [ExternalPlaybackActivity] renders its Playing surface only
 * after `awaitExternalPlaybackStart()` returns `Started`, while the playback service completes that
 * result only from its guarded `onRenderedFirstFrame()` for the active external setup/media id.
 * A second observer controller is deliberately not used because it would introduce an unrelated
 * MediaSession-event race instead of testing the production authority path.
 *
 * ActivityScenario is intentionally given an explicit component while preserving ACTION_VIEW,
 * data and MIME. That isolates activity/parser/service behavior from the test harness's implicit
 * intent resolver. A separate manifest-resolution test below proves that the exported HTTP/HTTPS
 * video entry point remains discoverable by Android.
 *
 * Evidence produced: intent accepted -> cleartext exact-origin approval -> service-gated first
 * frame -> hidden-surface D-pad seek burst accepted (transient HUD) -> clean Back -> activity
 * destroyed, playback stopped (no HTTP traffic after destroy). Network range/rebuffer semantics
 * are not claimed here: the 4-second fixture is fully buffered long before the D-pad input, so
 * HTTP request deltas around the seek are not causally observable at the app level. Byte-range and
 * resilience evidence lives in `ProgressiveResilienceEvidenceTest` (player:media3).
 *
 * No locator path, query or media identity is ever logged or persisted.
 */
@RunWith(AndroidJUnit4::class)
class ExternalPlaybackRangeJourneyTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun exportedHttpVideoIntentResolvesToExternalPlaybackActivity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://example.invalid/media.mp4"),
        ).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            type = "video/mp4"
            setPackage(context.packageName)
        }

        val resolved = context.packageManager.resolveActivity(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )

        assertThat(resolved).isNotNull()
        assertThat(checkNotNull(resolved).activityInfo.name)
            .isEqualTo(ExternalPlaybackActivity::class.java.name)
    }

    @Test
    fun torrServeStyleIntentPlaysSeeksAndReturnsCleanly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        RangeMediaServer.start(
            RangeMediaServer.Config(
                media = encodedVideo,
                contentType = "video/mp4",
            ),
        ).use { server ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(server.url("/movie.mp4"))).apply {
                setClass(context, ExternalPlaybackActivity::class.java)
                type = "video/mp4"
                putExtra(Intent.EXTRA_TITLE, "EP-08 Journey")
            }

            ActivityScenario.launch<ExternalPlaybackActivity>(intent).use { scenario ->
                composeRule.waitUntil(timeoutMillis = 30_000) {
                    composeRule.onAllNodesWithTag(HTTP_APPROVE_TAG)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag(HTTP_APPROVE_TAG).performClick()

                composeRule.waitUntil(timeoutMillis = 60_000) {
                    composeRule.onAllNodesWithTag("external-surface")
                        .fetchSemanticsNodes().size == 1
                }

                composeRule.onNodeWithTag("external-surface").performKeyInput {
                    repeat(SEEK_BURST_PRESSES) {
                        keyDown(Key.DirectionRight)
                        keyUp(Key.DirectionRight)
                    }
                }
                composeRule.waitUntil(timeoutMillis = 15_000) {
                    composeRule.onAllNodesWithTag("external-seek-hud")
                        .fetchSemanticsNodes().isNotEmpty()
                }

                composeRule.onNodeWithTag("external-surface").performKeyInput {
                    keyDown(Key.Back)
                    keyUp(Key.Back)
                }

                waitForLifecycle(scenario, Lifecycle.State.DESTROYED, 15_000)
                val requestsAtDestroy = server.requestCount()
                Thread.sleep(NO_REQUESTS_AFTER_FINISH_SLEEP_MILLIS)
                assertThat(server.requestCount()).isEqualTo(requestsAtDestroy)
            }
        }
    }

    @Test
    fun malformedExternalIntentShowsTypedRejection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("ftp://invalid.example/media.mp4")).apply {
            setClass(context, ExternalPlaybackActivity::class.java)
            type = "video/mp4"
        }

        ActivityScenario.launch<ExternalPlaybackActivity>(intent).use { scenario ->
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithTag(BACK_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag(BACK_TAG).performClick()
            waitForLifecycle(scenario, Lifecycle.State.DESTROYED, 15_000)
        }
    }

    private fun waitForLifecycle(
        scenario: ActivityScenario<out Activity>,
        target: Lifecycle.State,
        timeoutMillis: Long,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (scenario.state != target && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
        assertThat(scenario.state).isEqualTo(target)
    }

    private companion object {
        const val HTTP_APPROVE_TAG = "external-http-approve"
        const val BACK_TAG = "external-back"
        const val SEEK_BURST_PRESSES = 4
        const val NO_REQUESTS_AFTER_FINISH_SLEEP_MILLIS = 1_000L

        @Volatile
        var encodedVideo: ByteArray = byteArrayOf()

        @BeforeClass
        @JvmStatic
        fun encodeVideoOnce() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            assumeTrue(
                "H.264 encoder/decoder unavailable on this image; " +
                    "first-frame journey evidence requires decodable video",
                OnDeviceVideoFixture.hasRequiredCodecs(),
            )
            encodedVideo = OnDeviceVideoFixture.encode(context, durationSeconds = 4)
        }
    }
}
