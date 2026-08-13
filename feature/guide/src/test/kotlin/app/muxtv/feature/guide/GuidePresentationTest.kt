package app.muxtv.feature.guide

import app.muxtv.catalog.GuideProgrammeCell
import app.muxtv.catalog.GuideProgrammeKey
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.PlayableChannelSummary
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

class GuidePresentationTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val viewportFrom = localEpochMillis(hour = 10, minute = 0)
    private val viewportTo = viewportFrom + 6L * 60L * 60L * 1_000L

    @Test
    fun `ready programmes are clamped to viewport and sorted by start`() {
        val row = projectGuideRow(
            channel = channel(displayName = "Первый канал"),
            state = GuideProjectionState.READY,
            programmes = listOf(
                programme(sequence = 2, startOffsetMillis = -30 * MINUTE_MILLIS, durationMillis = 60 * MINUTE_MILLIS),
                programme(sequence = 1, startOffsetMillis = 2 * HOUR_MILLIS, durationMillis = 60 * MINUTE_MILLIS),
            ),
            viewportFromEpochMillis = viewportFrom,
            viewportToEpochMillis = viewportTo,
            zoneId = zone,
        )

        assertThat(row.cells).hasSize(2)
        assertThat(row.cells[0].programmeKey).isEqualTo(key(2))
        assertThat(row.cells[0].startEpochMillis).isEqualTo(viewportFrom)
        assertThat(row.cells[0].endEpochMillis).isEqualTo(viewportFrom + 30 * MINUTE_MILLIS)
        assertThat(row.cells[1].programmeKey).isEqualTo(key(1))
        assertThat(row.cells[1].startEpochMillis).isEqualTo(viewportFrom + 2 * HOUR_MILLIS)
    }

    @Test
    fun `programme cell carries preformatted time and detail labels`() {
        val row = projectGuideRow(
            channel = channel(displayName = "Первый канал"),
            state = GuideProjectionState.READY,
            programmes = listOf(
                programme(sequence = 1, startOffsetMillis = HOUR_MILLIS, durationMillis = 2 * HOUR_MILLIS),
            ),
            viewportFromEpochMillis = viewportFrom,
            viewportToEpochMillis = viewportTo,
            zoneId = zone,
        )

        val cell = row.cells.single()
        assertThat(cell.title).isEqualTo("Новости дня")
        assertThat(cell.timeLabel).isEqualTo("11:00–13:00")
        assertThat(cell.detailLabel).isEqualTo("Первый канал · Новости дня · 11:00–13:00")
    }

    @Test
    fun `blank programme title falls back to deterministic copy`() {
        val row = projectGuideRow(
            channel = channel(displayName = "Первый канал"),
            state = GuideProjectionState.READY,
            programmes = listOf(
                GuideProgrammeCell(
                    key = key(1),
                    startEpochMillis = viewportFrom,
                    endEpochMillis = viewportFrom + HOUR_MILLIS,
                    title = "   ",
                ),
            ),
            viewportFromEpochMillis = viewportFrom,
            viewportToEpochMillis = viewportTo,
            zoneId = zone,
        )

        assertThat(row.cells.single().title).isEqualTo("Без названия")
        assertThat(row.cells.single().detailLabel).contains("Без названия")
    }

    @Test
    fun `empty ready window produces single status cell with no programme identity`() {
        val row = projectGuideRow(
            channel = channel(displayName = "Первый канал"),
            state = GuideProjectionState.READY,
            programmes = emptyList(),
            viewportFromEpochMillis = viewportFrom,
            viewportToEpochMillis = viewportTo,
            zoneId = zone,
        )

        assertThat(row.cells).hasSize(1)
        val cell = row.cells.single()
        assertThat(cell.title).isEqualTo("Нет передач в этом окне")
        assertThat(cell.programmeKey).isNull()
        assertThat(cell.timeLabel).isNull()
        assertThat(cell.state).isEqualTo(GuideProjectionState.READY)
        assertThat(cell.endEpochMillis - cell.startEpochMillis).isEqualTo(30 * MINUTE_MILLIS)
    }

    @Test
    fun `no guide row uses deterministic presentation copy`() {
        val row = projectGuideRow(
            channel = channel(displayName = "Первый канал"),
            state = GuideProjectionState.NO_GUIDE,
            programmes = emptyList(),
            viewportFromEpochMillis = viewportFrom,
            viewportToEpochMillis = viewportTo,
            zoneId = zone,
        )

        assertThat(row.cells).hasSize(1)
        assertThat(row.cells.single().title).isEqualTo("Нет программы")
        assertThat(row.cells.single().detailLabel)
            .isEqualTo("Первый канал · программа не найдена")
        assertThat(row.cells.single().programmeKey).isNull()
    }

    @Test
    fun `source conflict row uses deterministic presentation copy`() {
        val row = projectGuideRow(
            channel = channel(displayName = "Первый канал"),
            state = GuideProjectionState.SOURCE_CONFLICT,
            programmes = emptyList(),
            viewportFromEpochMillis = viewportFrom,
            viewportToEpochMillis = viewportTo,
            zoneId = zone,
        )

        assertThat(row.cells).hasSize(1)
        assertThat(row.cells.single().title).isEqualTo("Конфликт источников")
        assertThat(row.cells.single().detailLabel)
            .isEqualTo("Первый канал · конфликт источников программы")
        assertThat(row.cells.single().composeKey()).isEqualTo("status:SOURCE_CONFLICT")
    }

    @Test
    fun `primary label combines number favorite marker and display name`() {
        val row = projectGuideRow(
            channel = channel(displayName = "Первый канал", channelNumber = "7", isFavorite = true),
            state = GuideProjectionState.READY,
            programmes = emptyList(),
            viewportFromEpochMillis = viewportFrom,
            viewportToEpochMillis = viewportTo,
            zoneId = zone,
        )

        assertThat(row.primaryLabel).isEqualTo("7  ★ Первый канал")
    }

    @Test
    fun `long russian labels survive projection without truncation`() {
        val longTitle = "Очень длинное название передачи на русском языке, которое точно не помещается в одну строку"
        val row = projectGuideRow(
            channel = channel(displayName = "Длинное название телеканала для проверки геометрии строки"),
            state = GuideProjectionState.READY,
            programmes = listOf(
                GuideProgrammeCell(
                    key = key(1),
                    startEpochMillis = viewportFrom,
                    endEpochMillis = viewportFrom + HOUR_MILLIS,
                    title = longTitle,
                ),
            ),
            viewportFromEpochMillis = viewportFrom,
            viewportToEpochMillis = viewportTo,
            zoneId = zone,
        )

        assertThat(row.cells.single().title).isEqualTo(longTitle)
        assertThat(row.cells.single().detailLabel).contains(longTitle)
        assertThat(row.primaryLabel).contains("Длинное название телеканала для проверки геометрии строки")
    }

    @Test
    fun `focus channel carries programme keys in cell order`() {
        val row = projectGuideRow(
            channel = channel(displayName = "Первый канал"),
            state = GuideProjectionState.READY,
            programmes = listOf(
                programme(sequence = 2, startOffsetMillis = HOUR_MILLIS, durationMillis = HOUR_MILLIS),
                programme(sequence = 1, startOffsetMillis = 2 * HOUR_MILLIS, durationMillis = HOUR_MILLIS),
            ),
            viewportFromEpochMillis = viewportFrom,
            viewportToEpochMillis = viewportTo,
            zoneId = zone,
        )

        assertThat(row.focusChannel.channelId).isEqualTo("channel-a")
        assertThat(row.focusChannel.programmeKeys).containsExactly(key(2), key(1)).inOrder()
    }

    @Test
    fun `full window presentation carries bounded half hour ticks and normal label`() {
        val viewport = GuideViewport(
            fromEpochMillis = viewportFrom,
            toEpochMillis = viewportTo,
            hasMoreChannels = false,
            canGoPrevious = false,
            canResetToFirstPage = false,
        )

        val presentation = viewport.toPresentation(zone)

        assertThat(presentation.isNarrowed).isFalse()
        assertThat(presentation.label).isEqualTo("Окно: 6 ч")
        assertThat(presentation.spanMillis).isEqualTo(6L * HOUR_MILLIS)
        assertThat(presentation.ticks).hasSize(13)
        assertThat(presentation.ticks.first().label).isEqualTo("10:00")
        assertThat(presentation.ticks.last().label).isEqualTo("16:00")
    }

    @Test
    fun `narrowed window presentation uses safe window label`() {
        val viewport = GuideViewport(
            fromEpochMillis = viewportFrom,
            toEpochMillis = viewportFrom + 45 * MINUTE_MILLIS,
            hasMoreChannels = false,
            canGoPrevious = false,
            canResetToFirstPage = false,
        )

        val presentation = viewport.toPresentation(zone)

        assertThat(presentation.isNarrowed).isTrue()
        assertThat(presentation.label).isEqualTo("Безопасное окно: 45 мин")
    }

    @Test
    fun `projection diagnostics never expose channel or epg identity`() {
        val row = projectGuideRow(
            channel = channel(displayName = "Секретный канал"),
            state = GuideProjectionState.READY,
            programmes = listOf(
                programme(sequence = 1, startOffsetMillis = HOUR_MILLIS, durationMillis = HOUR_MILLIS),
            ),
            viewportFromEpochMillis = viewportFrom,
            viewportToEpochMillis = viewportTo,
            zoneId = zone,
        )

        val rowText = row.toString()
        assertThat(rowText).doesNotContain("channel-a")
        assertThat(rowText).doesNotContain("secret-epg")
        assertThat(rowText).doesNotContain("Секретный канал")

        val cellText = row.cells.single().toString()
        assertThat(cellText).doesNotContain("secret-epg")
        assertThat(cellText).doesNotContain("Новости дня")
    }

    private fun channel(
        displayName: String,
        channelNumber: String? = null,
        isFavorite: Boolean = false,
    ): PlayableChannelSummary = PlayableChannelSummary(
        channelId = "channel-a",
        displayName = displayName,
        logoUrl = null,
        groupTitle = null,
        channelNumber = channelNumber,
        isFavorite = isFavorite,
        variantCount = 1,
    )

    private fun programme(
        sequence: Long,
        startOffsetMillis: Long,
        durationMillis: Long,
    ): GuideProgrammeCell = GuideProgrammeCell(
        key = key(sequence),
        startEpochMillis = viewportFrom + startOffsetMillis,
        endEpochMillis = viewportFrom + startOffsetMillis + durationMillis,
        title = "Новости дня",
    )

    private fun key(sequence: Long): GuideProgrammeKey = GuideProgrammeKey(
        epgSourceId = "secret-epg",
        epgRevisionNumber = 1,
        sequenceNumber = sequence,
    )

    private fun localEpochMillis(hour: Int, minute: Int): Long = ZonedDateTime.of(
        2026,
        8,
        13,
        hour,
        minute,
        0,
        0,
        zone,
    ).toInstant().toEpochMilli()

    private companion object {
        const val MINUTE_MILLIS = 60_000L
        const val HOUR_MILLIS = 60L * MINUTE_MILLIS
    }
}
