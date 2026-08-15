package app.muxtv.external

/**
 * Result of resolving the Android 17+ local-network permission for a classified target.
 */
enum class LocalNetworkPermissionState {
    /** Below Android 17 or target is not classified local: no prompt, playback may proceed. */
    NOT_REQUIRED,

    /** Permission is currently granted. */
    GRANTED,

    /** User denied; the system may still offer the permission again. */
    DENIED,

    /** User denied and the system will no longer show the prompt: settings are required. */
    PERMANENTLY_DENIED,
}

/**
 * Pure decision logic for the `ACCESS_LOCAL_NETWORK` runtime permission.
 *
 * Official Android contract (targetSdk 37, see
 * developer.android.com/privacy-and-security/local-network-permission):
 * - Android 16 / API36: Local Network Protection is an opt-in experiment — there is no mandatory
 *   `ACCESS_LOCAL_NETWORK` runtime permission contract; apps must NOT request it as a runtime
 *   permission;
 * - Android 17 / API37+ with targetSdk 37+: `ACCESS_LOCAL_NETWORK` is a mandatory runtime
 *   permission for local-network (private IPv4/v6, mDNS, link-local) access;
 * - loopback and remote targets never require the broad LAN permission.
 *
 * The Android-side adapter supplies the actual permission check/request results. The gate only
 * decides whether a prompt is required for a target and maps raw outcomes to typed states.
 */
class LocalNetworkPermissionGate(
    private val apiLevel: Int,
) {
    fun permissionRequired(classification: LocalNetworkClassification): Boolean =
        apiLevel >= ANDROID_17_API && classification == LocalNetworkClassification.LOCAL

    fun resolveRequestResult(
        granted: Boolean,
        rationaleAvailable: Boolean,
    ): LocalNetworkPermissionState = when {
        granted -> LocalNetworkPermissionState.GRANTED
        rationaleAvailable -> LocalNetworkPermissionState.DENIED
        else -> LocalNetworkPermissionState.PERMANENTLY_DENIED
    }

    companion object {
        const val ANDROID_17_API = 37
    }
}
