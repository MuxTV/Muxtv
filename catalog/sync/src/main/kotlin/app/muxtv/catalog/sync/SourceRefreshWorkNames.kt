package app.muxtv.catalog.sync

internal object SourceRefreshWorkNames {
    const val TAG_ALL = "muxtv-source-refresh"
    private const val TAG_SOURCE_PREFIX = "muxtv-source:"

    fun immediate(sourceId: String): String = "muxtv-source-refresh:$sourceId"

    fun periodic(sourceId: String): String = "muxtv-source-periodic:$sourceId"

    fun sourceTag(sourceId: String): String = "$TAG_SOURCE_PREFIX$sourceId"
}
