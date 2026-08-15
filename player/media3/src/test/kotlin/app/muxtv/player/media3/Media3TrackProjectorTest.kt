package app.muxtv.player.media3

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class Media3TrackProjectorTest {
    private val projector = Media3TrackProjector(Locale("ru"))

    @Test
    fun `label only track keeps full studio label and adds language`() {
        val group = audioGroup(
            id = "g1",
            trackSupport = intArrayOf(C.FORMAT_HANDLED),
            audioFormatBuilder()
                .setId("a1")
                .setLabel("Русский — Дублированный | HDRezka Studio")
                .setLanguage("ru")
                .setSampleMimeType("audio/eac3")
                .setChannelCount(6)
                .setSampleRate(48_000)
                .setAverageBitrate(640_000)
                .build(),
        )
        val tracks = Tracks(listOf(group))

        val models = projector.audioTracks(tracks, group.mediaTrackGroup, setOf(0))

        assertThat(models).hasSize(1)
        val model = models[0]
        assertThat(model.primaryLabel)
            .isEqualTo("Русский — Дублированный | HDRezka Studio")
        assertThat(model.languageLabel).isNull()
        assertThat(model.technicalLabel).isEqualTo("E-AC-3 • 5.1 • 48 kHz • 640 kb/s")
        assertThat(model.selected).isTrue()
        assertThat(model.supported).isTrue()
    }

    @Test
    fun `language only track uses localized language name as primary`() {
        val group = audioGroup(
            id = "g1",
            trackSupport = intArrayOf(C.FORMAT_HANDLED),
            Format.Builder()
                .setId("a1")
                .setLanguage("en")
                .setSampleMimeType("audio/mp4a-latm")
                .setChannelCount(2)
                .build(),
        )
        val tracks = Tracks(listOf(group))

        val models = projector.audioTracks(tracks, null, emptySet())

        assertThat(models).hasSize(1)
        assertThat(models[0].primaryLabel).isEqualTo("английский")
        assertThat(models[0].languageLabel).isNull()
        assertThat(models[0].technicalLabel).isEqualTo("AAC • Stereo")
    }

    @Test
    fun `track without any metadata falls back to numbered label`() {
        val group = audioGroup(
            id = "g1",
            trackSupport = intArrayOf(C.FORMAT_HANDLED),
            Format.Builder().setId("a1").setSampleMimeType("audio/mp4a-latm").build(),
        )
        val tracks = Tracks(listOf(group))

        val models = projector.audioTracks(tracks, null, emptySet())

        assertThat(models[0].primaryLabel).isEqualTo("Аудиодорожка 1")
    }

    @Test
    fun `long russian voice-over labels are preserved without truncation`() {
        val longLabel = "Авторский одноголосый перевод Гоблина с матом и комментариями"
        val group = audioGroup(
            id = "g1",
            trackSupport = intArrayOf(C.FORMAT_HANDLED),
            Format.Builder()
                .setId("a1")
                .setLabel(longLabel)
                .setSampleMimeType("audio/ac3")
                .setChannelCount(6)
                .build(),
        )
        val tracks = Tracks(listOf(group))

        val models = projector.audioTracks(tracks, null, emptySet())

        assertThat(models[0].primaryLabel).isEqualTo(longLabel)
        assertThat(models[0].technicalLabel).isEqualTo("AC-3 • 5.1")
    }

    @Test
    fun `duplicate label and language does not repeat language`() {
        val group = audioGroup(
            id = "g1",
            trackSupport = intArrayOf(C.FORMAT_HANDLED),
            Format.Builder()
                .setId("a1")
                .setLabel("Русский")
                .setLanguage("ru")
                .setSampleMimeType("audio/mp4a-latm")
                .build(),
        )
        val tracks = Tracks(listOf(group))

        val models = projector.audioTracks(tracks, null, emptySet())

        assertThat(models[0].primaryLabel).isEqualTo("Русский")
        assertThat(models[0].languageLabel).isNull()
    }

    @Test
    fun `unknown bitrate and sample rate are omitted from technical line`() {
        val group = audioGroup(
            id = "g1",
            trackSupport = intArrayOf(C.FORMAT_HANDLED),
            Format.Builder()
                .setId("a1")
                .setLabel("LostFilm")
                .setSampleMimeType("audio/ac3")
                .setChannelCount(Format.NO_VALUE)
                .build(),
        )
        val tracks = Tracks(listOf(group))

        val models = projector.audioTracks(tracks, null, emptySet())

        assertThat(models[0].technicalLabel).isEqualTo("AC-3")
    }

    @Test
    fun `unsupported tracks stay visible but disabled`() {
        val group = audioGroup(
            id = "g1",
            trackSupport = intArrayOf(C.FORMAT_HANDLED, C.FORMAT_EXCEEDS_CAPABILITIES),
            Format.Builder()
                .setId("a1")
                .setLabel("Original")
                .setSampleMimeType("audio/mp4a-latm")
                .build(),
            Format.Builder()
                .setId("a2")
                .setLabel("Director Commentary")
                .setSampleMimeType("audio/true-hd")
                .setChannelCount(8)
                .build(),
        )
        val tracks = Tracks(listOf(group))

        val models = projector.audioTracks(tracks, null, emptySet())

        assertThat(models).hasSize(2)
        assertThat(models[0].supported).isTrue()
        assertThat(models[0].technicalLabel).isEqualTo("AAC")
        assertThat(models[1].supported).isFalse()
        assertThat(models[1].primaryLabel).isEqualTo("Director Commentary")
        assertThat(models[1].technicalLabel).isEqualTo("TrueHD • 7.1")
    }

    @Test
    fun `selection reflects the active override group and index`() {
        val firstGroup = audioGroup(
            id = "g1",
            trackSupport = intArrayOf(C.FORMAT_HANDLED),
            Format.Builder().setId("a1").setLabel("Первая").setSampleMimeType("audio/mp4a-latm").build(),
        )
        val secondGroup = audioGroup(
            id = "g2",
            trackSupport = intArrayOf(C.FORMAT_HANDLED),
            Format.Builder().setId("b1").setLabel("Вторая").setSampleMimeType("audio/ac3").build(),
        )
        val tracks = Tracks(listOf(firstGroup, secondGroup))

        val models = projector.audioTracks(tracks, secondGroup.mediaTrackGroup, setOf(0))

        assertThat(models[0].selected).isFalse()
        assertThat(models[1].selected).isTrue()
    }

    @Test
    fun `stale selection from a foreign group never marks any track`() {
        val group = audioGroup(
            id = "g1",
            trackSupport = intArrayOf(C.FORMAT_HANDLED),
            Format.Builder().setId("a1").setLabel("Первая").setSampleMimeType("audio/mp4a-latm").build(),
        )
        val tracks = Tracks(listOf(group))
        val foreignGroup = TrackGroup(
            Format.Builder().setId("z1").setSampleMimeType("audio/mp4a-latm").build(),
        )

        val models = projector.audioTracks(tracks, foreignGroup, setOf(0))

        assertThat(models[0].selected).isFalse()
    }

    @Test
    fun `default and forced selection flags are projected`() {
        val group = audioGroup(
            id = "g1",
            trackSupport = intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED),
            Format.Builder()
                .setId("a1")
                .setLabel("Первая")
                .setSampleMimeType("audio/mp4a-latm")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build(),
            Format.Builder()
                .setId("a2")
                .setLabel("Вторая")
                .setSampleMimeType("audio/mp4a-latm")
                .setSelectionFlags(C.SELECTION_FLAG_FORCED)
                .build(),
        )
        val tracks = Tracks(listOf(group))

        val models = projector.audioTracks(tracks, null, emptySet())

        assertThat(models[0].isDefault).isTrue()
        assertThat(models[0].isForced).isFalse()
        assertThat(models[1].isDefault).isFalse()
        assertThat(models[1].isForced).isTrue()
    }

    @Test
    fun `text tracks project subtitle labels and format badge`() {
        val group = textGroup(
            id = "s1",
            trackSupport = intArrayOf(C.FORMAT_HANDLED),
            Format.Builder()
                .setId("t1")
                .setLabel("Русские субтитры")
                .setLanguage("ru")
                .setSampleMimeType("application/x-subrip")
                .build(),
        )
        val tracks = Tracks(listOf(group))

        val models = projector.textTracks(tracks, group.mediaTrackGroup, setOf(0))

        assertThat(models).hasSize(1)
        assertThat(models[0].primaryLabel).isEqualTo("Русские субтитры")
        assertThat(models[0].languageLabel).isEqualTo("русский")
        assertThat(models[0].technicalLabel).isEqualTo("SRT")
        assertThat(models[0].selected).isTrue()
        assertThat(models[0].supported).isTrue()
    }

    @Test
    fun `text track language label appears when label does not carry language`() {
        val group = textGroup(
            id = "s1",
            trackSupport = intArrayOf(C.FORMAT_HANDLED),
            Format.Builder()
                .setId("t1")
                .setLabel("Студийные")
                .setLanguage("en")
                .setSampleMimeType("text/vtt")
                .build(),
        )
        val tracks = Tracks(listOf(group))

        val models = projector.textTracks(tracks, null, emptySet())

        assertThat(models[0].primaryLabel).isEqualTo("Студийные")
        assertThat(models[0].languageLabel).isEqualTo("английский")
        assertThat(models[0].technicalLabel).isEqualTo("WebVTT")
    }

    @Test
    fun `control and bidi characters are stripped from untrusted labels`() {
        val group = audioGroup(
            id = "g1",
            trackSupport = intArrayOf(C.FORMAT_HANDLED),
            Format.Builder()
                .setId("a1")
                .setLabel("Ори\u2066гинал\r\nX\u0000")
                .setSampleMimeType("audio/mp4a-latm")
                .build(),
        )
        val tracks = Tracks(listOf(group))

        val models = projector.audioTracks(tracks, null, emptySet())

        assertThat(models[0].primaryLabel).isEqualTo("Оригинал X")
    }

    @Test
    fun `group without media3 id uses positional fallback key`() {
        val group = Tracks.Group(
            TrackGroup(
                Format.Builder().setId("a1").setSampleMimeType("audio/mp4a-latm").build(),
            ),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(false),
        )
        val tracks = Tracks(listOf(group))

        val models = projector.audioTracks(tracks, null, emptySet())

        assertThat(models[0].key.groupId).isEqualTo("group-0")
        assertThat(models[0].key.trackIndex).isEqualTo(0)
    }

    @Test
    fun `codec id from codecs field is used when mime type is unknown`() {
        val group = audioGroup(
            id = "g1",
            trackSupport = intArrayOf(C.FORMAT_HANDLED),
            Format.Builder()
                .setId("a1")
                .setLabel("Оригинал")
                .setSampleMimeType("audio/x-unknown-mime")
                .setCodecs("mp4a.40.2")
                .setChannelCount(2)
                .build(),
        )
        val tracks = Tracks(listOf(group))

        val models = projector.audioTracks(tracks, null, emptySet())

        assertThat(models[0].technicalLabel).isEqualTo("mp4a.40.2 • Stereo")
    }

    private fun audioGroup(
        id: String,
        trackSupport: IntArray,
        vararg formats: Format,
    ): Tracks.Group = Tracks.Group(
        TrackGroup(id, *formats),
        false,
        trackSupport,
        BooleanArray(formats.size) { false },
    )

    private fun textGroup(
        id: String,
        trackSupport: IntArray,
        vararg formats: Format,
    ): Tracks.Group = Tracks.Group(
        TrackGroup(id, *formats),
        false,
        trackSupport,
        BooleanArray(formats.size) { false },
    )

    private fun audioFormatBuilder(): Format.Builder = Format.Builder()
}
