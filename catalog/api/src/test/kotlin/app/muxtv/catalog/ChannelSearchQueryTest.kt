package app.muxtv.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelSearchQueryTest {
    @Test
    fun normalizesWhitespaceWithoutChangingUnicodeText() {
        val query = ChannelSearchQuery(
            profileId = "profile-main",
            text = "  Россия\t1\n  HD  ",
            nowEpochMillis = 1234L,
        )

        assertThat(query.normalizedText).isEqualTo("Россия 1 HD")
        assertThat(query.limit).isEqualTo(ChannelSearchQuery.DEFAULT_LIMIT)
    }

    @Test
    fun rejectsBlankProfileId() {
        val error = runCatching {
            ChannelSearchQuery(
                profileId = "   ",
                text = "Россия",
                nowEpochMillis = 0L,
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun rejectsNegativeTime() {
        val error = runCatching {
            ChannelSearchQuery(
                profileId = "profile-main",
                text = "Россия",
                nowEpochMillis = -1L,
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun rejectsLimitsOutsidePublicBound() {
        listOf(0, ChannelSearchQuery.MAX_LIMIT + 1).forEach { invalidLimit ->
            val error = runCatching {
                ChannelSearchQuery(
                    profileId = "profile-main",
                    text = "Россия",
                    nowEpochMillis = 0L,
                    limit = invalidLimit,
                )
            }.exceptionOrNull()

            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun diagnosticsDoNotExposeProfileOrQueryText() {
        val query = ChannelSearchQuery(
            profileId = "secret-profile",
            text = "Секретный запрос",
            nowEpochMillis = 1234L,
            limit = 17,
        )

        val diagnostic = query.toString()

        assertThat(diagnostic).doesNotContain("secret-profile")
        assertThat(diagnostic).doesNotContain("Секретный")
        assertThat(diagnostic).contains("hasText=true")
        assertThat(diagnostic).contains("limit=17")
    }

    @Test
    fun snapshotDiagnosticsDoNotExposeProgrammeOrChannelText() {
        val result = ChannelSearchResult(
            channel = PlayableChannelSummary(
                channelId = "channel-a",
                displayName = "Россия 1",
                logoUrl = null,
                groupTitle = "Новости",
                channelNumber = "1",
                isFavorite = true,
                variantCount = 1,
            ),
            currentProgrammeTitle = "Вести",
        )
        val snapshot = ChannelSearchSnapshot(
            results = listOf(result),
            isTruncated = false,
            nextBoundaryEpochMillis = 9999L,
        )

        assertThat(result.toString()).doesNotContain("Россия")
        assertThat(result.toString()).doesNotContain("Вести")
        assertThat(snapshot.toString()).doesNotContain("Россия")
        assertThat(snapshot.toString()).contains("resultCount=1")
    }
}
