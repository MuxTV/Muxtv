package app.muxtv.feature.guide

import app.muxtv.catalog.GuideProgrammeKey

internal data class GuideFocusChannel(
    val channelId: String,
    val programmeKeys: List<GuideProgrammeKey>,
) {
    init {
        require(channelId.isNotBlank())
    }

    override fun toString(): String =
        "GuideFocusChannel(programmeCount=${programmeKeys.size})"
}

internal data class GuideFocusAnchor(
    val channelId: String,
    val programmeKey: GuideProgrammeKey?,
    val previousChannelIndex: Int,
    val previousProgrammeIndex: Int,
) {
    init {
        require(channelId.isNotBlank())
        require(previousChannelIndex >= 0)
        require(previousProgrammeIndex >= 0)
    }

    override fun toString(): String =
        "GuideFocusAnchor(programmePresent=${programmeKey != null}, " +
            "previousChannelIndex=$previousChannelIndex, " +
            "previousProgrammeIndex=$previousProgrammeIndex)"
}

internal data class GuideFocusTarget(
    val channelId: String,
    val channelIndex: Int,
    val programmeKey: GuideProgrammeKey?,
    val programmeIndex: Int?,
) {
    override fun toString(): String =
        "GuideFocusTarget(channelIndex=$channelIndex, programmePresent=${programmeKey != null}, " +
            "programmeIndex=$programmeIndex)"
}

internal fun GuideFocusAnchor.resolveAgainst(
    channels: List<GuideFocusChannel>,
): GuideFocusTarget? {
    if (channels.isEmpty()) return null

    val exactChannelIndex = channels.indexOfFirst { it.channelId == channelId }
    val channelIndex = when {
        exactChannelIndex >= 0 -> exactChannelIndex
        previousChannelIndex > 0 -> minOf(previousChannelIndex - 1, channels.lastIndex)
        previousChannelIndex <= channels.lastIndex -> previousChannelIndex
        else -> 0
    }
    val channel = channels[channelIndex]

    if (channel.programmeKeys.isEmpty()) {
        return GuideFocusTarget(
            channelId = channel.channelId,
            channelIndex = channelIndex,
            programmeKey = null,
            programmeIndex = null,
        )
    }

    val exactProgrammeIndex = programmeKey?.let(channel.programmeKeys::indexOf) ?: -1
    val programmeIndex = when {
        exactProgrammeIndex >= 0 -> exactProgrammeIndex
        previousProgrammeIndex > channel.programmeKeys.lastIndex -> channel.programmeKeys.lastIndex
        else -> previousProgrammeIndex
    }

    return GuideFocusTarget(
        channelId = channel.channelId,
        channelIndex = channelIndex,
        programmeKey = channel.programmeKeys[programmeIndex],
        programmeIndex = programmeIndex,
    )
}
