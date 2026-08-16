package app.muxtv.external

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * message instead of silently passing. `external-surface` proves that the real Media3 surface has
 * been attached while the external setup is still pending. `external-first-frame-confirmed` is the
 * causal completion marker: [ExternalPlaybackActivity] exposes it only after
 * `awaitExternalPlaybackStart()` returns `Started`, while the playback service completes that
 * result only from its guarded `onRenderedFirstFrame()` for the active external setup/media id.
 * This ordering avoids the invalid circular dependency where a first frame was required before a
 * surface existed. A second observer controller is deliberately not used because it would
 * introduce an unrelated MediaSession-event race instead of testing the production authority path.
 *
 * ActivityScenario is intentionally given an explicit component while preserving ACTION_VIEW,
 * data and MIME. Android's Intent API requires data and MIME to be assigned together with
 * `setDataAndType`; setting `type` after `data` clears the URI and would test a different rejection
 * path. A separate manifest-resolution test below proves that the exported HTTP/HTTPS video entry
 * point remains discoverable by Android.
 *
 * TV interaction is remote-native: cleartext approval, hidden-surface seek and Back are driven
 * through real Android D-pad events rather than synthetic touch or Compose-local key injection.
 * Evidence produced: intent accepted -> cleartext exact-origin approval -> Media3 surface attached
 * -> service-gated first frame confirmed -> focused hidden surface -> real D-pad seek input reaches
 * the production seek boundary and is accepted (transient HUD) -> real Android Back -> activity
 * destroyed, playback stopped (no HTTP traffic after destroy).
 * Network range/rebuffer semantics are not claimed here: the short fixture is fully buffered long
 * before the D-pad input, so HTTP request deltas around the seek are not causally observable at the
 * app level. Byte-range and resilience evidence lives in `ProgressiveResilienceEvidenceTest`
 * (player:media3).
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
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setDataAndType(Uri.parse("https://example.invalid/media.mp4"), "video/mp4")
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
        // Previous evidence attempts must not make this cleartext-approval journey vacuously skip
        // its gate. The production store is backed by this bounded preferences file and is lazily
        // constructed when ExternalPlaybackActivity is injected.
        context.getSharedPreferences(EXTERNAL_PLAYBACK_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        RangeMediaServer.start(
            RangeMediaServer.Config(
                media = encodedVideo,
                contentType = "video/mp4",
            ),
        ).use { server ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setClass(context, ExternalPlaybackActivity::class.java)
                setDataAndType(Uri.parse(server.url("/movie.mp4")), "video/mp4")
                putExtra(Intent.EXTRA_TITLE, "EP-08 Journey")
            }

            ActivityScenario.launch<ExternalPlaybackActivity>(intent).use { scenario ->
                composeRule.waitUntil(timeoutMillis = 30_000) {
                    composeRule.onAllNodesWithTag(HTTP_APPROVE_TAG)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag(HTTP_APPROVE_TAG).assertIsFocused()
                pressSystemKey(KeyEvent.KEYCODE_DPAD_CENTER)

                composeRule.waitUntil(timeoutMillis = 30_000) {
                    composeRule.onAllNodesWithTag(SURFACE_TAG)
                        .fetchSemanticsNodes().size == 1
                }
                composeRule.waitUntil(timeoutMillis = 60_000) {
                    composeRule.onAllNodesWithTag(FIRST_FRAME_CONFIRMED_TAG)
                        .fetchSemanticsNodes().size == 1
                }

                composeRule.onNodeWithTag(SURFACE_TAG).assertIsFocused()
                pressSystemKey(KeyEvent.KEYCODE_DPAD_RIGHT)

                val seekInputOutcome = awaitSeekInputOutcomeOrNull()
                    ?: diagnoseMissingSystemKeyBoundary(scenario)
                assertThat(seekInputOutcome).isEqualTo(SEEK_INPUT_ACCEPTED_TAG)

                composeRule.waitUntil(timeoutMillis = 15_000) {
                    composeRule.onAllNodesWithTag(SEEK_HUD_TAG)
                        .fetchSemanticsNodes().isNotEmpty()
                }

                pressSystemKey(KeyEvent.KEYCODE_BACK)

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
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClass(context, ExternalPlaybackActivity::class.java)
            setDataAndType(Uri.parse("ftp://invalid.example/media.mp4"), "video/mp4")
        }

        ActivityScenario.launch<ExternalPlaybackActivity>(intent).use { scenario ->
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithTag(BACK_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag(BACK_TAG).assertIsFocused()

            pressSystemKey(KeyEvent.KEYCODE_BACK)

            waitForLifecycle(scenario, Lifecycle.State.DESTROYED, 15_000)
        }
    }

    private fun diagnoseMissingSystemKeyBoundary(
        scenario: ActivityScenario<ExternalPlaybackActivity>,
    ): Nothing {
        scenario.onActivity { activity ->
            activity.dispatchKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT),
            )
            activity.dispatchKeyEvent(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT),
            )
        }
        val directOutcome = awaitSeekInputOutcomeOrNull()
        if (directOutcome != null) {
            throw AssertionError(
                "SYSTEM_KEY_TRANSPORT_MISSED_ACTIVITY_BOUNDARY: " +
                    "direct Activity.dispatchKeyEvent produced $directOutcome",
            )
        }
        throw AssertionError(
            "REMOTE_INPUT_BOUNDARY_INACTIVE: neither real DPAD_RIGHT nor direct " +
                "Activity.dispatchKeyEvent reached the player seek-input outcome",
        )
    }

    private fun awaitSeekInputOutcomeOrNull(): String? {
        val deadline = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(SEEK_INPUT_OUTCOME_TIMEOUT_MILLIS)
        while (System.nanoTime() < deadline) {
            val matches = SEEK_INPUT_OUTCOME_TAGS.filter { tag ->
                composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
            if (matches.isNotEmpty()) {
                return matches.single()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        return null
    }

    private fun pressSystemKey(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().apply {
            sendKeyDownUpSync(keyCode)
            waitForIdleSync()
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
        const val SURFACE_TAG = "external-surface"
        const val FIRST_FRAME_CONFIRMED_TAG = "external-first-frame-confirmed"
        const val SEEK_HUD_TAG = "external-seek-hud"
        const val SEEK_INPUT_ACCEPTED_TAG = "external-seek-input-accepted"
        const val SEEK_INPUT_OUTCOME_TIMEOUT_MILLIS = 2_000L
        val SEEK_INPUT_OUTCOME_TAGS = listOf(
            SEEK_INPUT_ACCEPTED_TAG,
            "external-seek-input-command-unavailable",
            "external-seek-input-unknown-duration",
            "external-seek-input-live-content",
            "external-seek-input-invalid-position",
            "external-seek-input-controller-rejected",
        )
        const val NO_REQUESTS_AFTER_FINISH_SLEEP_MILLIS = 1_000L
        const val EXTERNAL_PLAYBACK_PREFERENCES_NAME = "muxtv_external_playback"

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
