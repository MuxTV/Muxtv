package app.muxtv.feature.guide

import app.muxtv.catalog.GuideProgrammeKey
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

sealed interface GuidePlaybackSelection {
    val channelId: String

    data class Live(
        override val channelId: String,
    ) : GuidePlaybackSelection {
        init {
            require(channelId.isNotBlank())
        }

        override fun toString(): String = "GuidePlaybackSelection.Live(channelId=<redacted>)"
    }

    data class CatchupProgram(
        override val channelId: String,
        val programmeId: String,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
    ) : GuidePlaybackSelection {
        init {
            require(channelId.isNotBlank())
            require(programmeId.isNotBlank())
            require(programmeId.length <= MAX_PROGRAMME_ID_LENGTH)
            require(startEpochMillis >= 0L)
            require(endEpochMillis > startEpochMillis)
        }

        override fun toString(): String =
            "GuidePlaybackSelection.CatchupProgram(channelId=<redacted>, " +
                "programmeId=<redacted>, startEpochMillis=$startEpochMillis, " +
                "endEpochMillis=$endEpochMillis)"
    }
}

internal fun guidePlaybackSelection(
    channelId: String,
    cell: GuideCellProjection,
    nowEpochMillis: Long,
): GuidePlaybackSelection? {
    require(channelId.isNotBlank())
    require(nowEpochMillis >= 0L)

    val programmeKey = cell.programmeKey
        ?: return GuidePlaybackSelection.Live(channelId)
    val originalStartEpochMillis = requireNotNull(cell.originalStartEpochMillis)
    val originalEndEpochMillis = requireNotNull(cell.originalEndEpochMillis)

    return when {
        nowEpochMillis < originalStartEpochMillis -> null
        nowEpochMillis < originalEndEpochMillis -> GuidePlaybackSelection.Live(channelId)
        else -> GuidePlaybackSelection.CatchupProgram(
            channelId = channelId,
            programmeId = opaqueGuideProgrammeId(channelId, programmeKey),
            startEpochMillis = originalStartEpochMillis,
            endEpochMillis = originalEndEpochMillis,
        )
    }
}

private fun opaqueGuideProgrammeId(
    channelId: String,
    key: GuideProgrammeKey,
): String {
    val payload = buildString {
        append(channelId)
        append('\u0000')
        append(key.epgSourceId)
        append('\u0000')
        append(key.epgRevisionNumber)
        append('\u0000')
        append(key.sequenceNumber)
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(StandardCharsets.UTF_8))
    return buildString(PROGRAMME_ID_PREFIX.length + PROGRAMME_ID_HEX_LENGTH) {
        append(PROGRAMME_ID_PREFIX)
        repeat(PROGRAMME_ID_HASH_BYTES) { index ->
            val value = digest[index].toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }
}

private const val MAX_PROGRAMME_ID_LENGTH = 80
private const val PROGRAMME_ID_PREFIX = "gp1_"
private const val PROGRAMME_ID_HASH_BYTES = 16
private const val PROGRAMME_ID_HEX_LENGTH = PROGRAMME_ID_HASH_BYTES * 2
private const val HEX_DIGITS = "0123456789abcdef"
