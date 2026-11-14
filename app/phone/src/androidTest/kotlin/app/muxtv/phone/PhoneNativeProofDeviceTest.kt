package app.muxtv.phone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hosted phone emulator smoke gate, API26 and API36.
 * A QR scan, explicit user action and TV D-pad consent are prerequisites;
 * launching the Activity must not expose a connection action or send packets.
 */
@RunWith(AndroidJUnit4::class)
class PhoneNativeProofDeviceTest {
    @Test fun openingPhoneWithoutQrCannotEnableTcpOrVerification() {
        ActivityScenario.launch(PhoneQrActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(android.R.id.content)
                val panel = checkNotNull(findByTag(root, "lc04-phone-native-panel"))
                val start = checkNotNull(findByTag(root, "lc04-phone-native-opt-in"))
                val verify = checkNotNull(findByTag(root, "lc04-phone-native-verify"))
                val sixFrame = checkNotNull(findByTag(root, "lc04-phone-mxh1-second-opt-in"))
                assertThat(panel.visibility).isEqualTo(View.GONE)
                assertThat(verify.visibility).isEqualTo(View.GONE)
                // The manifest grants no pairing authority. Camera permission
                // alone is not a transport opt-in.
                assertThat(start.isShown).isFalse()
                assertThat(verify.isShown).isFalse()
                assertThat(sixFrame.isShown).isFalse()
            }
        }
    }

    @Test fun activityStopAndReentryDoNotResumePreviousNativeAttempt() {
        ActivityScenario.launch(PhoneQrActivity::class.java).use { scenario ->
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(android.R.id.content)
                assertThat(checkNotNull(findByTag(root,
                    "lc04-phone-native-panel")).visibility).isEqualTo(View.GONE)
            }
        }
    }

    @Test fun declaredLanPermissionsNeverImplyApi26OrApi36RuntimePrompt() {
        // API37+ has a separate explicit runtime local-network permission
        // branch. API26/API36 must never try requesting a permission unknown
        // to those OS versions.
        assertThat(Build.VERSION.SDK_INT).isAtMost(36)
        val packageName = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext.packageName
        val pkg = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext.packageManager
            .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val permissions = pkg.requestedPermissions.orEmpty().toList()
        assertThat(permissions).contains(Manifest.permission.CAMERA)
        assertThat(permissions).contains(Manifest.permission.INTERNET)
        assertThat(permissions).contains(Manifest.permission.ACCESS_NETWORK_STATE)
        assertThat(permissions).contains(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    private fun findByTag(view: View, tag: String): View? {
        if (view.tag == tag) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val match = findByTag(view.getChildAt(i), tag)
                if (match != null) return match
            }
        }
        return null
    }
}
