package app.muxtv.external

import java.net.URI

/**
 * App-owned preflight for source preparation that never performs network I/O.
 *
 * Host classification is intentionally syntactic. Ambiguous hostnames remain unprompted until a
 * later platform/network signal can prove that local-network access is required.
 */
class LocalNetworkSourcePreflight(
    apiLevel: Int,
    private val permissionGranted: () -> Boolean,
) {
    private val permissionGate = LocalNetworkPermissionGate(apiLevel)

    fun accessRequired(locator: String): Boolean {
        val host = extractHost(locator) ?: return false
        val classification = LocalNetworkTargetClassifier.classify(host)
        return permissionGate.permissionRequired(classification) && !permissionGranted()
    }

    private fun extractHost(locator: String): String? {
        val value = locator.trim()
        if (value.isEmpty()) return null

        return parseHost(value) ?: parseHost("//$value")
    }

    private fun parseHost(value: String): String? =
        runCatching { URI(value).host }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
}
