package app.muxtv.catalog.sync

import app.muxtv.database.EpgRefreshTrigger

internal object EpgRefreshWorkNames {
    const val TAG_ALL = "muxtv-epg-refresh"
    private const val TAG_SOURCE_PREFIX = "muxtv-epg-source:"

    fun immediate(
        sourceId: String,
        trigger: EpgRefreshTrigger,
    ): String {
        require(sourceId.isNotBlank())
        val triggerSegment = when (trigger) {
            EpgRefreshTrigger.MANUAL -> "manual"
            EpgRefreshTrigger.STARTUP -> "startup"
            EpgRefreshTrigger.PERIODIC -> error(
                "Periodic EPG refresh uses a separate unique periodic identity.",
            )
        }
        return "muxtv-epg-refresh:$triggerSegment:$sourceId"
    }

    fun periodic(sourceId: String): String = "muxtv-epg-periodic:$sourceId"

    fun sourceTag(sourceId: String): String = "$TAG_SOURCE_PREFIX$sourceId"
}
