package app.muxtv.catalog

import app.muxtv.common.SourceId

enum class ProviderCapability {
    LIVE,
    EPG,
    CATCHUP,
}

class ProviderDescriptor(
    val sourceId: SourceId,
    capabilities: Set<ProviderCapability>,
) {
    val capabilities: Set<ProviderCapability> = capabilities.toSet()

    override fun toString(): String {
        val orderedCapabilities = ProviderCapability.entries.filter { capability ->
            capability in capabilities
        }
        return "ProviderDescriptor(capabilities=$orderedCapabilities)"
    }
}
