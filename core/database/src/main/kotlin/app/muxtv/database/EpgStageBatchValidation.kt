package app.muxtv.database

internal fun validateEpgStageBatch(
    channels: List<EpgChannelEntity>,
    programmes: List<EpgProgrammeEntity>,
) {
    val totalRows = channels.size + programmes.size
    if (totalRows == 0) return
    require(totalRows <= MAX_EPG_STAGE_BATCH_ROWS) {
        "EPG staging batch exceeds the production row limit."
    }

    val firstChannel = channels.firstOrNull()
    val expectedSourceId = firstChannel?.sourceId ?: programmes.first().sourceId
    val expectedRevisionNumber = firstChannel?.revisionNumber ?: programmes.first().revisionNumber

    require(
        channels.all {
            it.sourceId == expectedSourceId && it.revisionNumber == expectedRevisionNumber
        } && programmes.all {
            it.sourceId == expectedSourceId && it.revisionNumber == expectedRevisionNumber
        },
    ) { "EPG staging batch must belong to one source revision." }
}

private const val MAX_EPG_STAGE_BATCH_ROWS = 1_000
