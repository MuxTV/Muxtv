package app.muxtv

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Saves a rendered screen capture into app external files so the CI harness
 * can adb-pull it into `.work/evidence/screenshots` for visual review against
 * the approved Lounge Light mockup at the same 1080p viewport.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeContentTestRule.captureScreenshot(name: String) =
    saveScreenshot(onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap(), name)

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.captureScreenshot(name: String) =
    saveScreenshot(onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap(), name)

private fun saveScreenshot(bitmap: Bitmap, name: String) {
    val directory = File(
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
        "screenshots",
    )
    directory.mkdirs()
    File(directory, "$name.png").outputStream().use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    }
}
