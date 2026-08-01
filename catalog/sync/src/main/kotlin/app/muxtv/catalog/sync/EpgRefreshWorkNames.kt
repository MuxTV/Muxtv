package app.muxtv.catalog.sync

internal object EpgRefreshWorkNames {
    const val TAG_ALL = "muxtv-epg-refresh"
    private const val TAG_SOURCE_PREFIX = "muxtv-epg-source:"

    fun immediate(sourceId: String): String = "muxtv-epg-refresh:$sourceId"

    fun periodic(sourceId: String): String = "muxtv-epg-periodic:$sourceId"

    fun sourceTag(sourceId: String): String = "$TAG_SOURCE_PREFIX$sourceId"
}
