package app.muxtv.database.measurement

internal fun expectedCatalogSearchBoundaryEpochMillis(
    canonicalChannelIds: List<String>,
    firstBoundaryEpochMillis: Long,
): Long {
    require(canonicalChannelIds.isNotEmpty())
    return firstBoundaryEpochMillis + canonicalChannelIds.minOf(::measurementChannelIndex)
}

private fun measurementChannelIndex(canonicalChannelId: String): Long {
    val suffix = canonicalChannelId.removePrefix(MEASUREMENT_CANONICAL_PREFIX)
    require(suffix.length != canonicalChannelId.length && suffix.isNotEmpty()) {
        "Unexpected measurement channel id."
    }
    return suffix.toLongOrNull()
        ?: throw IllegalArgumentException("Unexpected measurement channel id.")
}

private const val MEASUREMENT_CANONICAL_PREFIX = "canonical-"
