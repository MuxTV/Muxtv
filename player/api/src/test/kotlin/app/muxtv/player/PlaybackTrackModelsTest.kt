package app.muxtv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackTrackModelsTest {
    @Test
    fun `track key requires non blank group id and non negative index`() {
        val key = TrackKey(groupId = "group-0", trackIndex = 1)
        assertThat(key.groupId).isEqualTo("group-0")
        assertThat(key.trackIndex).isEqualTo(1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `track key rejects blank group id`() {
        TrackKey(groupId = " ", trackIndex = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `track key rejects negative track index`() {
        TrackKey(groupId = "group-0", trackIndex = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `track key rejects newlines in group id`() {
        TrackKey(groupId = "group\n0", trackIndex = 0)
    }

    @Test
    fun `audio model keeps full metadata projection`() {
        val model = AudioTrackUiModel(
            key = TrackKey("g1", 0),
            selected = true,
            supported = true,
            primaryLabel = "Русский — Дублированный | HDRezka Studio",
            languageLabel = "русский",
            technicalLabel = "E-AC-3 • 5.1 • 48 kHz • 640 kb/s",
            isDefault = true,
            isForced = false,
        )
        assertThat(model.primaryLabel)
            .isEqualTo("Русский — Дублированный | HDRezka Studio")
        assertThat(model.selected).isTrue()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `audio model rejects blank primary label`() {
        audioModel(primaryLabel = " ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `audio model rejects oversized primary label`() {
        audioModel(primaryLabel = "x".repeat(513))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `audio model rejects control characters in primary label`() {
        audioModel(primaryLabel = "first\nsecond")
    }

    @Test
    fun `subtitle model mirrors audio validation`() {
        val model = SubtitleTrackUiModel(
            key = TrackKey("s1", 0),
            selected = false,
            supported = true,
            primaryLabel = "Русские субтитры",
            languageLabel = null,
            technicalLabel = "SRT • русский",
            isForced = false,
        )
        assertThat(model.primaryLabel).isEqualTo("Русские субтитры")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `subtitle model rejects blank technical label`() {
        SubtitleTrackUiModel(
            key = TrackKey("s1", 0),
            selected = false,
            supported = true,
            primaryLabel = "Русские субтитры",
            languageLabel = null,
            technicalLabel = "",
            isForced = false,
        )
    }

    private fun audioModel(primaryLabel: String) = AudioTrackUiModel(
        key = TrackKey("g1", 0),
        selected = false,
        supported = true,
        primaryLabel = primaryLabel,
        languageLabel = null,
        technicalLabel = "AAC",
        isDefault = false,
        isForced = false,
    )
}
