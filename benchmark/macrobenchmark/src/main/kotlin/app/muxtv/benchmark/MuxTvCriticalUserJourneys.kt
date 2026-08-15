package app.muxtv.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

internal const val TARGET_PACKAGE = "app.muxtv.tv"
private const val UI_TIMEOUT_MILLIS = 10_000L

internal class MuxTvCriticalUserJourneys(
    private val scope: MacrobenchmarkScope,
) {
    private val device get() = scope.device

    fun startFromHome() {
        scope.startActivityAndWait()
        awaitTag("nav-home")
    }

    fun openChannels() = openTopLevel("nav-channels")

    fun openSearch() = openTopLevel("nav-search")

    fun openGuide() = openTopLevel("nav-guide")

    /**
     * Sources and Doctor live inside the Settings workspace since the Lounge
     * rail redesign: open Settings from the rail, then activate the section.
     */
    fun openSources() {
        openTopLevel("nav-settings")
        activateSettingsSection("settings-section-sources", "sources-add")
    }

    fun openDoctor() {
        openTopLevel("nav-settings")
        activateSettingsSection("settings-section-doctor", "doctor-title")
    }

    private fun activateSettingsSection(sectionTag: String, destinationTag: String) {
        val node = requireNotNull(device.wait(Until.findObject(By.res(sectionTag)), UI_TIMEOUT_MILLIS)) {
            "Required settings section was not found: $sectionTag"
        }
        node.click()
        check(device.wait(Until.hasObject(By.res(destinationTag)), UI_TIMEOUT_MILLIS)) {
            "Settings section destination did not appear: $destinationTag"
        }
        device.waitForIdle()
    }

    private fun openTopLevel(tag: String) {
        val node = requireNotNull(device.wait(Until.findObject(By.res(tag)), UI_TIMEOUT_MILLIS)) {
            "Required top-level destination was not found: $tag"
        }
        node.click()
        check(device.wait(Until.hasObject(By.res(tag)), UI_TIMEOUT_MILLIS)) {
            "Destination did not remain visible after activation: $tag"
        }
        device.waitForIdle()
    }

    private fun awaitTag(tag: String) {
        check(device.wait(Until.hasObject(By.res(tag)), UI_TIMEOUT_MILLIS)) {
            "Required UI tag was not found: $tag"
        }
    }
}
