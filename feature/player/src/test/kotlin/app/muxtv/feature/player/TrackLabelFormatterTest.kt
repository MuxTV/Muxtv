package app.muxtv.feature.player

import app.muxtv.player.AudioTrackUiModel
import app.muxtv.player.SubtitleTrackUiModel
import app.muxtv.player.TrackKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackLabelFormatterTest {
    @Test
    fun `audio action label uses selected track primary label`() {
        val label = TrackLabelFormatter.audioActionLabel(
            listOf(
                audioModel("Русский — Дублированный | HDRezka Studio", selected = true),
                audioModel("Original", selected = false),
            ),
        )

        assertThat(label).isEqualTo("Аудио · Русский — Дублированный | HDRezka Studio")
    }

    @Test
    fun `audio action label falls back when nothing selected`() {
        val label = TrackLabelFormatter.audioActionLabel(
            listOf(audioModel("Original", selected = false)),
        )

        assertThat(label).isEqualTo("Аудио")
    }

    @Test
    fun `compact label is structurally bounded with ellipsis`() {
        val label = TrackLabelFormatter.audioActionLabel(
            listOf(audioModel("x".repeat(200), selected = true)),
        )

        assertThat(label).startsWith("Аудио · ")
        assertThat(label).endsWith("…")
        assertThat(label.length).isLessThan(60)
    }

    @Test
    fun `subtitle action label reflects disabled state`() {
        assertThat(
            TrackLabelFormatter.subtitleActionLabel(
                models = listOf(subtitleModel("Русские субтитры", selected = true)),
                textDisabled = true,
            ),
        ).isEqualTo("Субтитры")
    }

    @Test
    fun `subtitle action label uses selected track`() {
        assertThat(
            TrackLabelFormatter.subtitleActionLabel(
                models = listOf(subtitleModel("Русские субтитры", selected = true)),
                textDisabled = false,
            ),
        ).isEqualTo("Субтитры · Русские субтитры")
    }

    @Test
    fun `playback time formats hours minutes and seconds`() {
        assertThat(TrackLabelFormatter.formatPlaybackTime(0L)).isEqualTo("0:00")
        assertThat(TrackLabelFormatter.formatPlaybackTime(61_000L)).isEqualTo("1:01")
        assertThat(TrackLabelFormatter.formatPlaybackTime(3_661_000L)).isEqualTo("1:01:01")
    }

    @Test
    fun `unknown playback time renders dash placeholder`() {
        assertThat(TrackLabelFormatter.formatPlaybackTime(-1L)).isEqualTo("–:––")
    }

    private fun audioModel(primaryLabel: String, selected: Boolean) = AudioTrackUiModel(
        key = TrackKey("g1", 0),
        selected = selected,
        supported = true,
        primaryLabel = primaryLabel,
        languageLabel = null,
        technicalLabel = "AAC",
        isDefault = false,
        isForced = false,
    )

    private fun subtitleModel(primaryLabel: String, selected: Boolean) = SubtitleTrackUiModel(
        key = TrackKey("s1", 0),
        selected = selected,
        supported = true,
        primaryLabel = primaryLabel,
        languageLabel = null,
        technicalLabel = "SRT",
        isForced = false,
    )
}
