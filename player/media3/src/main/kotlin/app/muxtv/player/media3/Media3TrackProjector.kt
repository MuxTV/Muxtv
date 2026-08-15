package app.muxtv.player.media3

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Label
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import app.muxtv.player.AudioTrackUiModel
import app.muxtv.player.SubtitleTrackUiModel
import app.muxtv.player.TrackKey
import java.util.Locale

/**
 * Converts the current Media3 [Tracks] snapshot into immutable presentation projections.
 *
 * Pure JVM logic: no Context, no controller, no persistence. Selection is described by the
 * active override group/indices taken from [androidx.media3.common.TrackSelectionParameters]
 * through [Media3TrackController.snapshot]; the UI treats selection as authoritative only after
 * Media3 reports updated parameters/tracks events.
 *
 * Human labels keep full studio/voice annotations ("HDRezka Studio", "LostFilm", "Original")
 * and are never collapsed to "Русский 5.1".
 */
@AndroidXOptIn(UnstableApi::class)
class Media3TrackProjector(private val locale: Locale = Locale.getDefault()) {

    fun audioTracks(
        tracks: Tracks,
        selectedGroup: TrackGroup?,
        selectedIndices: Set<Int>,
    ): List<AudioTrackUiModel> {
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        var groupIndex = 0
        val models = ArrayList<AudioTrackUiModel>(groups.sumOf { it.mediaTrackGroup.length })
        for (group in groups) {
            val mediaTrackGroup = group.mediaTrackGroup
            val groupId = trackGroupId(mediaTrackGroup, groupIndex)
            for (trackIndex in 0 until mediaTrackGroup.length) {
                val format = mediaTrackGroup.getFormat(trackIndex)
                val primary = primaryLabel(
                    format = format,
                    fallback = "Аудиодорожка ${trackIndex + 1}",
                )
                models += AudioTrackUiModel(
                    key = TrackKey(groupId = groupId, trackIndex = trackIndex),
                    selected = mediaTrackGroup == selectedGroup && trackIndex in selectedIndices,
                    supported = group.isTrackSupported(trackIndex),
                    primaryLabel = primary.primary,
                    languageLabel = primary.language,
                    technicalLabel = technicalAudioLabel(format),
                    isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                    isForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                )
            }
            groupIndex += 1
        }
        return models
    }

    fun textTracks(
        tracks: Tracks,
        selectedGroup: TrackGroup?,
        selectedIndices: Set<Int>,
    ): List<SubtitleTrackUiModel> {
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        var groupIndex = 0
        val models = ArrayList<SubtitleTrackUiModel>(groups.sumOf { it.mediaTrackGroup.length })
        for (group in groups) {
            val mediaTrackGroup = group.mediaTrackGroup
            val groupId = trackGroupId(mediaTrackGroup, groupIndex)
            for (trackIndex in 0 until mediaTrackGroup.length) {
                val format = mediaTrackGroup.getFormat(trackIndex)
                val primary = primaryLabel(
                    format = format,
                    fallback = "Субтитры ${trackIndex + 1}",
                )
                models += SubtitleTrackUiModel(
                    key = TrackKey(groupId = groupId, trackIndex = trackIndex),
                    selected = mediaTrackGroup == selectedGroup && trackIndex in selectedIndices,
                    supported = group.isTrackSupported(trackIndex),
                    primaryLabel = primary.primary,
                    languageLabel = primary.language,
                    technicalLabel = technicalTextLabel(format),
                    isForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                )
            }
            groupIndex += 1
        }
        return models
    }

    private fun trackGroupId(group: TrackGroup, groupIndex: Int): String =
        group.id?.takeIf { it.isNotBlank() } ?: "group-$groupIndex"

    private fun primaryLabel(
        format: Format,
        fallback: String,
    ): TrackPrimaryLabel {
        val label = sanitizeText(format.label)
        if (label.isNotBlank()) {
            return TrackPrimaryLabel(
                primary = label,
                language = distinctLanguage(format.language, label),
            )
        }
        format.labels?.let { labels ->
            labels.firstOrNull { it.value.isNotBlank() }?.let { structured ->
                val value = sanitizeText(structured.value)
                if (value.isNotBlank()) {
                    return TrackPrimaryLabel(
                        primary = value,
                        language = distinctLanguage(format.language, value),
                    )
                }
            }
        }
        val languageName = format.language?.takeIf(String::isNotBlank)
            ?.let { displayLanguage(it) }
            ?.takeIf { it.isNotBlank() }
        if (languageName != null) {
            return TrackPrimaryLabel(primary = languageName, language = null)
        }
        return TrackPrimaryLabel(primary = fallback, language = null)
    }

    private fun distinctLanguage(languageCode: String?, primary: String): String? {
        val code = languageCode?.takeIf(String::isNotBlank) ?: return null
        val name = displayLanguage(code)
        if (name.isBlank() || name.equals(primary, ignoreCase = true)) return null
        if (primary.contains(name, ignoreCase = true)) return null
        return name
    }

    private fun displayLanguage(code: String): String {
        val display = runCatching { Locale.forLanguageTag(code).getDisplayLanguage(locale) }
            .getOrNull()
        return if (display.isNullOrBlank()) code else display
    }

    private fun technicalAudioLabel(format: Format): String {
        val parts = ArrayList<String>(4)
        codecName(format.sampleMimeType, format.codecs)?.let(parts::add)
        channelLayout(format.channelCount)?.let(parts::add)
        if (format.sampleRate != Format.NO_VALUE && format.sampleRate > 0) {
            parts += if (format.sampleRate >= 1_000) {
                "${format.sampleRate / 1_000} kHz"
            } else {
                "${format.sampleRate} Hz"
            }
        }
        val effectiveBitrate = when {
            format.averageBitrate != Format.NO_VALUE && format.averageBitrate > 0 ->
                format.averageBitrate
            format.bitrate != Format.NO_VALUE && format.bitrate > 0 -> format.bitrate
            else -> Format.NO_VALUE
        }
        if (effectiveBitrate != Format.NO_VALUE) {
            parts += "${effectiveBitrate / 1_000} kb/s"
        }
        return parts.joinToString(separator = " • ").ifBlank { "Аудио" }
    }

    private fun technicalTextLabel(format: Format): String =
        codecName(format.sampleMimeType, format.codecs) ?: "Субтитры"

    private fun channelLayout(channelCount: Int): String? = when (channelCount) {
        Format.NO_VALUE, 0 -> null
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        else -> "$channelCount ch"
    }

    private fun codecName(sampleMimeType: String?, codecs: String?): String? {
        sampleMimeType?.takeIf(String::isNotBlank)?.let { mime ->
            AUDIO_MIME_NAMES[mime]?.let { return it }
            TEXT_MIME_NAMES[mime]?.let { return it }
        }
        val codecsValue = codecs?.takeIf { it.isNotBlank() } ?: return null
        val codecId = codecsValue.split(',').firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        return codecId.take(MAX_CODEC_ID_LENGTH)
    }

    private fun sanitizeText(raw: String?): String {
        val value = raw ?: return ""
        val builder = StringBuilder(value.length)
        for (char in value) {
            if (char == '\r' || char == '\n' || char == '\t') {
                if (builder.isNotEmpty() && builder.last() != ' ') builder.append(' ')
                continue
            }
            if (char.isISOControl()) continue
            if (char in BIDI_CONTROL_CHARS) continue
            builder.append(char)
        }
        return builder.toString().trim().replace(Regex(" +"), " ").take(MAX_TRACK_TEXT_LENGTH)
    }

    private data class TrackPrimaryLabel(
        val primary: String,
        val language: String?,
    )

    companion object {
        private const val MAX_CODEC_ID_LENGTH = 64
        private const val MAX_TRACK_TEXT_LENGTH = 512
        private val BIDI_CONTROL_CHARS = setOf(
            '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
            '\u2066', '\u2067', '\u2068', '\u2069',
        )
        private val AUDIO_MIME_NAMES = mapOf(
            "audio/mp4a-latm" to "AAC",
            "audio/aac" to "AAC",
            "audio/mp4" to "MP4 Audio",
            "audio/ac3" to "AC-3",
            "audio/ac4" to "AC-4",
            "audio/eac3" to "E-AC-3",
            "audio/eac3-joc" to "E-AC-3 JOC",
            "audio/dts" to "DTS",
            "audio/dts-hd" to "DTS-HD",
            "audio/vnd.dts.hd" to "DTS-HD",
            "audio/true-hd" to "TrueHD",
            "audio/opus" to "Opus",
            "audio/vorbis" to "Vorbis",
            "audio/flac" to "FLAC",
            "audio/mpeg" to "MP3",
            "audio/mpeg-l2" to "MP2",
            "audio/3gpp" to "AMR",
            "audio/amr-wb" to "AMR-WB",
            "audio/raw" to "PCM",
            "audio/mp4a" to "MPEG Audio",
        )
        private val TEXT_MIME_NAMES = mapOf(
            "text/vtt" to "WebVTT",
            "application/x-subrip" to "SRT",
            "application/x-mp4-vtt" to "MP4 VTT",
            "application/x-ass" to "ASS",
            "text/x-ssa" to "SSA",
            "application/pgs" to "PGS",
            "application/ttml+xml" to "TTML",
            "application/dvbsubs" to "DVB",
            "application/x-quicktime-tx3g" to "Tx3g",
        )
    }
}
