package app.muxtv.feature.channels

import app.muxtv.catalog.ChannelBrowseItem
import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.GuideProgramme
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.player.PlaybackSessionPhase
import app.muxtv.player.PlaybackSessionState
import com.google.common.truth.Truth.assertThat
import java.util.TimeZone
import org.junit.Test

class ChannelsPresentationTest {
    private val item = ChannelBrowseItem(
        channelId = "channel-1",
        displayName = "Первый канал",
        channelNumber = "1",
        groupTitle = "Общие",
        isFavorite = true,
        isCurrentPlayback = false,
        currentProgrammeTitle = "Browse-программа",
        currentProgrammeEndEpochMillis = 3_000,
        nextProgrammeTitle = "Browse-далее",
        nextProgrammeStartEpochMillis = 3_000,
        variantCount = 2,
        guideState = GuideProjectionState.READY,
    )

    private val readyNowNext = ChannelNowNext(
        canonicalChannelId = "channel-1",
        state = GuideProjectionState.READY,
        current = GuideProgramme(1_000, 3_000, "Эфирная программа"),
        next = GuideProgramme(3_000, 4_000, "Эфирная далее"),
        nextBoundaryEpochMillis = 3_000,
    )

    @Test
    fun `row uses epg now next with time labels and progress`() {
        val row = buildChannelRow(
            item = item,
            nowNext = readyNowNext,
            playback = PlaybackSessionState.Idle,
            nowEpochMillis = 2_000,
        )
        assertThat(row.currentTitle).isEqualTo("Эфирная программа")
        assertThat(row.nextTitle).isEqualTo("Эфирная далее")
        assertThat(row.progressFraction).isWithin(0.01f).of(0.5f)
        assertThat(row.currentEndLabel).isNotNull()
        assertThat(row.nextStartLabel).isNotNull()
    }

    @Test
    fun `row without epg falls back to browse now next without progress`() {
        val row = buildChannelRow(
            item = item,
            nowNext = null,
            playback = PlaybackSessionState.Idle,
            nowEpochMillis = 2_000,
        )
        assertThat(row.currentTitle).isEqualTo("Browse-программа")
        assertThat(row.nextTitle).isEqualTo("Browse-далее")
        assertThat(row.progressFraction).isNull()
        assertThat(row.currentEndLabel).matches("\\d{2}:\\d{2}")
    }

    @Test
    fun `no guide state suppresses programme rows`() {
        val row = buildChannelRow(
            item = item.copy(
                currentProgrammeTitle = null,
                currentProgrammeEndEpochMillis = null,
                nextProgrammeTitle = null,
                nextProgrammeStartEpochMillis = null,
                guideState = GuideProjectionState.NO_GUIDE,
            ),
            nowNext = null,
            playback = PlaybackSessionState.Idle,
            nowEpochMillis = 2_000,
        )
        assertThat(row.currentProgrammeLabel.trim()).isEmpty()
        assertThat(row.nextProgrammeLabel.trim()).isEmpty()
        assertThat(row.progressFraction).isNull()
    }

    @Test
    fun `source conflict keeps typed unavailability label`() {
        val row = buildChannelRow(
            item = item.copy(
                currentProgrammeTitle = null,
                currentProgrammeEndEpochMillis = null,
                nextProgrammeTitle = null,
                nextProgrammeStartEpochMillis = null,
                guideState = GuideProjectionState.SOURCE_CONFLICT,
            ),
            nowNext = null,
            playback = PlaybackSessionState.Idle,
            nowEpochMillis = 2_000,
        )
        assertThat(row.currentProgrammeLabel).isEqualTo("Программа недоступна")
    }

    @Test
    fun `playing identity is playback derived not browse derived`() {
        val row = buildChannelRow(
            item = item.copy(isCurrentPlayback = false),
            nowNext = null,
            playback = PlaybackSessionState("channel-1", PlaybackSessionPhase.READY, isPlaying = true),
            nowEpochMillis = 2_000,
        )
        assertThat(row.isCurrentPlayback).isTrue()
    }

    @Test
    fun `long russian titles keep bounded metadata`() {
        val row = buildChannelRow(
            item = item.copy(
                displayName = "Очень Длинное Название Канала Телевидения Всероссийского Значения",
                groupTitle = "Длинная Группа Каналов Российской Федерации",
            ),
            nowNext = readyNowNext.copy(
                current = GuideProgramme(1_000, 3_000, "Чрезвычайно длинное название текущей передачи номер один"),
                next = GuideProgramme(3_000, 4_000, "Чрезвычайно длинное название следующей передачи номер два"),
            ),
            playback = PlaybackSessionState.Idle,
            nowEpochMillis = 2_000,
        )
        assertThat(row.displayName).contains("Очень Длинное")
        assertThat(row.metadataLabel).contains("Длинная Группа")
    }

    @Test
    fun `time formatter honours explicit zone`() {
        val text = formatHm(epochMillis = 0L, timeZone = TimeZone.getTimeZone("UTC"))
        assertThat(text).isEqualTo("00:00")
        assertThat(text).matches("\\d{2}:\\d{2}")
    }
}
