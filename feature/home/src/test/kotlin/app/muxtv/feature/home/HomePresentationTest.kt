package app.muxtv.feature.home

import app.muxtv.catalog.ChannelBrowseItem
import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.GuideProgramme
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.RecentChannel
import app.muxtv.common.programmeProgressFraction
import app.muxtv.player.PlaybackSessionPhase
import app.muxtv.player.PlaybackSessionState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId

class HomePresentationTest {
    private val channel = PlayableChannelSummary(
        channelId = "channel-1",
        displayName = "Первый канал",
        logoUrl = null,
        groupTitle = null,
        channelNumber = "1",
        isFavorite = true,
        variantCount = 1,
    )

    private fun recentOf(summary: PlayableChannelSummary, playedAt: Long = 10L) =
        RecentChannel(channel = summary, lastSuccessfulPlaybackAtEpochMillis = playedAt)

    private fun nowNext(
        channelId: String,
        start: Long,
        end: Long,
        title: String = "Программа",
        nextTitle: String = "Далее",
    ) = ChannelNowNext(
        canonicalChannelId = channelId,
        state = GuideProjectionState.READY,
        current = GuideProgramme(startEpochMillis = start, endEpochMillis = end, title = title),
        next = GuideProgramme(startEpochMillis = end, endEpochMillis = end + 1_000, title = nextTitle),
        nextBoundaryEpochMillis = end,
    )

    private fun nowNextItem(channelId: String) = mapOf(channelId to nowNext(channelId, 1_000, 3_000))

    @Test
    fun `progress fraction is null without valid timing`() {
        assertThat(programmeProgressFraction(1_500, 1_000, 1_000)).isNull()
        assertThat(programmeProgressFraction(500, 1_000, 3_000)).isNull()
        assertThat(programmeProgressFraction(4_000, 1_000, 3_000)).isNull()
    }

    @Test
    fun `progress fraction is midpoint of programme window`() {
        assertThat(programmeProgressFraction(2_000, 1_000, 3_000)).isWithin(0.01f).of(0.5f)
    }

    @Test
    fun `hero falls back to generic live cta when nothing was played`() {
        val hero = buildHomeHero(PlaybackSessionState.Idle, emptyList(), emptyMap(), nowEpochMillis = 5_000)
        assertThat(hero.hasChannel).isFalse()
        assertThat(hero.channelId).isNull()
    }

    @Test
    fun `hero picks current playback channel with now next and progress`() {
        val session = PlaybackSessionState(
            channelId = "channel-1",
            phase = PlaybackSessionPhase.READY,
            isPlaying = true,
        )
        val hero = buildHomeHero(
            sessionState = session,
            recent = listOf(recentOf(channel)),
            nowNext = nowNextItem("channel-1"),
            nowEpochMillis = 2_000,
        )
        assertThat(hero.channelId).isEqualTo("channel-1")
        assertThat(hero.isCurrentPlayback).isTrue()
        assertThat(hero.isFavorite).isTrue()
        assertThat(hero.currentTitle).isEqualTo("Программа")
        assertThat(hero.nextTitle).isEqualTo("Далее")
        assertThat(hero.currentStart).isEqualTo(1_000)
        assertThat(hero.currentEnd).isEqualTo(3_000)
        assertThat(hero.nextStart).isEqualTo(3_000)
        assertThat(hero.nextEnd).isEqualTo(4_000)
        assertThat(hero.positionEpochMillis).isEqualTo(2_000)
        assertThat(hero.progressFraction).isWithin(0.01f).of(0.5f)
        assertThat(hero.primaryActionLabel).isEqualTo("Смотреть")
    }

    @Test
    fun `hero without epg shows channel without progress or titles`() {
        val hero = buildHomeHero(
            sessionState = PlaybackSessionState.Idle,
            recent = listOf(recentOf(channel)),
            nowNext = emptyMap(),
            nowEpochMillis = 2_000,
        )
        assertThat(hero.channelId).isEqualTo("channel-1")
        assertThat(hero.currentTitle).isNull()
        assertThat(hero.nextTitle).isNull()
        assertThat(hero.currentStart).isNull()
        assertThat(hero.currentEnd).isNull()
        assertThat(hero.nextStart).isNull()
        assertThat(hero.nextEnd).isNull()
        assertThat(hero.positionEpochMillis).isNull()
        assertThat(hero.progressFraction).isNull()
        assertThat(hero.primaryActionLabel).isEqualTo("Продолжить просмотр")
    }

    @Test
    fun `recent rail is bounded and empty when no recents`() {
        val many = (0 until 15).map { index ->
            recentOf(
                channel.copy(channelId = "channel-$index", displayName = "Канал $index"),
                playedAt = 100L - index,
            )
        }
        val rail = buildRecentRail(
            recent = many,
            nowNext = emptyMap(),
            sessionState = PlaybackSessionState.Idle,
            nowEpochMillis = 0,
        )
        assertThat(rail).hasSize(HOME_RAIL_LIMIT)
        assertThat(
            buildRecentRail(
                recent = emptyList(),
                nowNext = emptyMap(),
                sessionState = PlaybackSessionState.Idle,
                nowEpochMillis = 0,
            ),
        ).isEmpty()
    }

    @Test
    fun `favorites rail marks playing and falls back to browse titles without epg`() {
        val item = ChannelBrowseItem(
            channelId = "channel-1",
            displayName = "Первый канал",
            channelNumber = "1",
            groupTitle = null,
            isFavorite = true,
            isCurrentPlayback = true,
            currentProgrammeTitle = "Browse-программа",
            currentProgrammeEndEpochMillis = 3_000,
            nextProgrammeTitle = null,
            nextProgrammeStartEpochMillis = null,
            variantCount = 1,
            guideState = GuideProjectionState.READY,
        )
        val cards = buildFavoritesRail(listOf(item), emptyMap(), nowEpochMillis = 2_000)
        assertThat(cards).hasSize(1)
        assertThat(cards[0].isPlaying).isTrue()
        assertThat(cards[0].isFavorite).isTrue()
        assertThat(cards[0].currentTitle).isEqualTo("Browse-программа")
        assertThat(cards[0].progressFraction).isNull()
    }

    @Test
    fun `favorites rail uses epg progress when available`() {
        val item = ChannelBrowseItem(
            channelId = "channel-1",
            displayName = "Первый канал",
            channelNumber = null,
            groupTitle = null,
            isFavorite = true,
            isCurrentPlayback = false,
            currentProgrammeTitle = "Browse-программа",
            currentProgrammeEndEpochMillis = null,
            nextProgrammeTitle = null,
            nextProgrammeStartEpochMillis = null,
            variantCount = 1,
            guideState = GuideProjectionState.READY,
        )
        val cards = buildFavoritesRail(listOf(item), nowNextItem("channel-1"), nowEpochMillis = 2_000)
        assertThat(cards[0].currentTitle).isEqualTo("Программа")
        assertThat(cards[0].currentStart).isEqualTo(1_000)
        assertThat(cards[0].currentEnd).isEqualTo(3_000)
        assertThat(cards[0].progressFraction).isWithin(0.01f).of(0.5f)
    }

    @Test
    fun `home time formatting uses the supplied zone`() {
        assertThat(formatHomeTime(0L, ZoneId.of("UTC"))).isEqualTo("00:00")
        assertThat(formatHomeTime(1_704_067_200_000L, ZoneId.of("UTC"))).isEqualTo("00:00")
        assertThat(formatHomeTime(1_704_067_200_000L, ZoneId.of("Europe/Moscow"))).isEqualTo("03:00")
    }

    @Test
    fun `no guide state suppresses titles and progress`() {
        val item = ChannelBrowseItem(
            channelId = "channel-1",
            displayName = "Первый канал",
            channelNumber = null,
            groupTitle = null,
            isFavorite = false,
            isCurrentPlayback = false,
            currentProgrammeTitle = null,
            currentProgrammeEndEpochMillis = null,
            nextProgrammeTitle = null,
            nextProgrammeStartEpochMillis = null,
            variantCount = 1,
            guideState = GuideProjectionState.NO_GUIDE,
        )
        val cards = buildFavoritesRail(listOf(item), emptyMap(), nowEpochMillis = 2_000)
        assertThat(cards[0].currentTitle).isNull()
        assertThat(cards[0].progressFraction).isNull()
    }

    @Test
    fun `long russian titles stay single line candidates`() {
        val hero = buildHomeHero(
            sessionState = PlaybackSessionState.Idle,
            recent = listOf(
                recentOf(
                    channel.copy(
                        displayName = "Очень Длинное Название Канала Телевидения Всероссийского Значения",
                    ),
                ),
            ),
            nowNext = mapOf(
                "channel-1" to nowNext(
                    "channel-1",
                    start = 1_000,
                    end = 3_000,
                    title = "Чрезвычайно длинное название передачи которое не влезает в одну строку полностью",
                ),
            ),
            nowEpochMillis = 2_000,
        )
        assertThat(hero.displayName).contains("Очень Длинное")
        assertThat(hero.currentTitle).isNotEmpty()
    }
}
