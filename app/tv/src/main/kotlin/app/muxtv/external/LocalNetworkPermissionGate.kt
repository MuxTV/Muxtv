package app.muxtv.external

/**
 * Result of resolving the Android 16+ local-network permission for a classified target.
 */
enum class LocalNetworkPermissionState {
    /** Below Android 16 or target is not classified local: no prompt, playback may proceed. */
    NOT_REQUIRED,

    /** Permission is currently granted. */
    GRANTED,

    /** User denied; the system may still offer the permission again. */
    DENIED,

    /** User denied and the system will no longer show the prompt: settings are required. */
    PERMANENTLY_DENIED,
}

/**
 * Pure decision logic for the Android 16+ `ACCESS_LOCAL_NETWORK` runtime permission.
 *
 * The Android-side adapter supplies the actual permission check/request results. The gate only
 * decides whether a prompt is required for a target and maps raw outcomes to typed states.
 */
class LocalNetworkPermissionGate(
    private val apiLevel: Int,
) {
    fun permissionRequired(classification: LocalNetworkClassification): Boolean =
        apiLevel >= ANDROID_16_API && classification == LocalNetworkClassification.LOCAL

    fun resolveRequestResult(
        granted: Boolean,
        rationaleAvailable: Boolean,
    ): LocalNetworkPermissionState = when {
        granted -> LocalNetworkPermissionState.GRANTED
        rationaleAvailable -> LocalNetworkPermissionState.DENIED
        else -> LocalNetworkPermissionState.PERMANENTLY_DENIED
    }

    companion object {
        const val ANDROID_16_API = 36
    }
}
