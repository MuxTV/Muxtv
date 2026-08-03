package app.muxtv.database

import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.ChannelSearchQuery
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.NowNextQuery
import app.muxtv.catalog.PlayableChannelSummary
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RoomChannelSearchRepositoryTest {
    @Test
    fun blankQueryDoesNotExecuteCandidateSearch() = runTest {
        val dataSource = FakeSearchDataSource()
        val repository = RoomChannelSearchRepository(dataSource, FakeGuideRepository())

        val snapshot = repository.observe(query("   ")).first()

        assertThat(snapshot.results).isEmpty()
        assertThat(snapshot.isTruncated).isFalse()
        assertThat(dataSource.candidateExpressions).isEmpty()
    }

    @Test
    fun intersectsCanonicalIdsAcrossDifferentTokenFields() = runTest {
        val dataSource = FakeSearchDataSource(
            candidates = mapOf(
                "Россия*" to listOf(candidate("a", ChannelSearchMatchRank.NAME), candidate("b", ChannelSearchMatchRank.NAME)),
                "1*" to listOf(candidate("a", ChannelSearchMatchRank.PROVIDER)),
            ),
            summaries = listOf(summary("a", "Россия", "1"), summary("b", "Россия 24", "24")),
        )
        val repository = RoomChannelSearchRepository(dataSource, FakeGuideRepository())

        val snapshot = repository.observe(query("Россия 1")).first()

        assertThat(snapshot.results.map { it.channel.channelId }).containsExactly("a")
        assertThat(dataSource.candidateExpressions).containsExactly("Россия*", "1*").inOrder()
    }

    @Test
    fun intermediateCandidateOverflowMarksSmallFinalIntersectionTruncated() = runTest {
        val broad = (0..800).map { index -> candidate("channel-$index", ChannelSearchMatchRank.NAME) }
        val target = "channel-799"
        val dataSource = FakeSearchDataSource(
            candidates = mapOf(
                "канал*" to broad,
                "точный*" to listOf(candidate(target, ChannelSearchMatchRank.PROVIDER)),
            ),
            summaries = listOf(summary(target, "Канал точный", "799")),
        )
        val repository = RoomChannelSearchRepository(dataSource, FakeGuideRepository())

        val snapshot = repository.observe(query("канал точный")).first()

        assertThat(snapshot.results.map { it.channel.channelId }).containsExactly(target)
        assertThat(snapshot.isTruncated).isTrue()
    }

    @Test
    fun structuredRankingPrefersExactNumberThenExactNameThenPrefixThenOrigins() = runTest {
        val ids = listOf("number", "exact-name", "prefix-name", "raw", "group", "programme")
        val candidates = ids.map { id ->
            candidate(
                id,
                when (id) {
                    "number", "raw" -> ChannelSearchMatchRank.PROVIDER
                    "group" -> ChannelSearchMatchRank.GROUP
                    "programme" -> ChannelSearchMatchRank.PROGRAMME
                    else -> ChannelSearchMatchRank.NAME
                },
            )
        }
        val dataSource = FakeSearchDataSource(
            candidates = mapOf("спорт*" to candidates),
            summaries = listOf(
                summary("programme", "Шестой", "6"),
                summary("group", "Пятый", "5"),
                summary("raw", "Четвёртый", "4"),
                summary("prefix-name", "Спорт Плюс", "3"),
                summary("exact-name", "СПОРТ", "2"),
                summary("number", "Первый", "спорт"),
            ),
        )
        val repository = RoomChannelSearchRepository(dataSource, FakeGuideRepository())

        val snapshot = repository.observe(query("спорт")).first()

        assertThat(snapshot.results.map { it.channel.channelId }).containsExactly(
            "number",
            "exact-name",
            "prefix-name",
            "raw",
            "group",
            "programme",
        ).inOrder()
    }

    @Test
    fun multiTokenOriginUsesWeakestRequiredMatchForRanking() = runTest {
        val dataSource = FakeSearchDataSource(
            candidates = mapOf(
                "новости*" to listOf(
                    candidate("mixed", ChannelSearchMatchRank.NAME),
                    candidate("metadata", ChannelSearchMatchRank.PROVIDER),
                ),
                "вечер*" to listOf(
                    candidate("mixed", ChannelSearchMatchRank.PROGRAMME),
                    candidate("metadata", ChannelSearchMatchRank.PROVIDER),
                ),
            ),
            summaries = listOf(
                summary("mixed", "Канал A", "10"),
                summary("metadata", "Канал B", "11"),
            ),
        )
        val repository = RoomChannelSearchRepository(dataSource, FakeGuideRepository())

        val snapshot = repository.observe(query("новости вечер")).first()

        assertThat(snapshot.results.map { it.channel.channelId })
            .containsExactly("metadata", "mixed")
            .inOrder()
    }

    @Test
    fun publicLimitTruncatesDeterministicallyAndOnlyProjectsGuideForPublishedRows() = runTest {
        val candidates = (1..5).map { index -> candidate("c$index", ChannelSearchMatchRank.NAME) }
        val summaries = (1..5).map { index -> summary("c$index", "Канал $index", index.toString()) }
        val guide = FakeGuideRepository(currentTitles = mapOf("c1" to "Сейчас 1", "c2" to "Сейчас 2"))
        val dataSource = FakeSearchDataSource(
            candidates = mapOf("канал*" to candidates),
            summaries = summaries,
            nextBoundary = 9_000,
        )
        val repository = RoomChannelSearchRepository(dataSource, guide)

        val snapshot = repository.observe(query("канал", limit = 2)).first()

        assertThat(snapshot.results).hasSize(2)
        assertThat(snapshot.isTruncated).isTrue()
        assertThat(snapshot.nextBoundaryEpochMillis).isEqualTo(9_000)
        assertThat(guide.requestedIds.single()).containsExactlyElementsIn(
            snapshot.results.map { it.channel.channelId },
        )
        assertThat(snapshot.results.map { it.currentProgrammeTitle })
            .containsExactly("Сейчас 1", "Сейчас 2")
    }

    private fun query(text: String, limit: Int = ChannelSearchQuery.DEFAULT_LIMIT) = ChannelSearchQuery(
        profileId = "profile-main",
        text = text,
        nowEpochMillis = 1_500,
        limit = limit,
    )

    private fun candidate(id: String, rank: Int) = ChannelSearchCandidateRow(id, rank)

    private fun summary(id: String, name: String, number: String?) = PlayableChannelSummary(
        channelId = id,
        displayName = name,
        logoUrl = null,
        groupTitle = "Группа",
        channelNumber = number,
        isFavorite = false,
        variantCount = 1,
    )
}

private class FakeSearchDataSource(
    private val candidates: Map<String, List<ChannelSearchCandidateRow>> = emptyMap(),
    private val summaries: List<PlayableChannelSummary> = emptyList(),
    private val nextBoundary: Long? = null,
) : ChannelSearchDataSource {
    val candidateExpressions = mutableListOf<String>()

    override fun observeChanges(): Flow<Unit> = flowOf(Unit)

    override suspend fun searchCandidates(
        profileId: String,
        ftsExpression: String,
        nowEpochMillis: Long,
        fetchLimit: Int,
    ): List<ChannelSearchCandidateRow> {
        candidateExpressions += ftsExpression
        return candidates[ftsExpression].orEmpty().take(fetchLimit)
    }

    override suspend fun activeChannelSummaries(
        profileId: String,
        canonicalChannelIds: List<String>,
    ): List<PlayableChannelSummary> = summaries.filter { it.channelId in canonicalChannelIds }

    override suspend fun nextProgrammeBoundary(
        profileId: String,
        nowEpochMillis: Long,
    ): Long? = nextBoundary
}

private class FakeGuideRepository(
    private val currentTitles: Map<String, String> = emptyMap(),
) : EpgGuideRepository {
    val requestedIds = mutableListOf<List<String>>()

    override suspend fun getNowNext(query: NowNextQuery): List<ChannelNowNext> {
        requestedIds += query.canonicalChannelIds
        return query.canonicalChannelIds.map { id ->
            ChannelNowNext(
                canonicalChannelId = id,
                state = GuideProjectionState.READY,
                current = currentTitles[id]?.let { title ->
                    app.muxtv.catalog.GuideProgramme(
                        startEpochMillis = query.nowEpochMillis - 100,
                        endEpochMillis = query.nowEpochMillis + 100,
                        title = title,
                    )
                },
                next = null,
                nextBoundaryEpochMillis = query.nowEpochMillis + 100,
            )
        }
    }

    override fun observeDataChanges(): Flow<Unit> = flowOf(Unit)
}
