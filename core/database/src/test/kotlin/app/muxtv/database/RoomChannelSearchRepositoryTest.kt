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
    fun goldenCorpusPreservesUnicodeIntersectionRankingAndTruncation() = runTest {
        val dataSource = FakeSearchDataSource(
            candidates = mapOf(
                "\"Россия*\"" to listOf(
                    candidate("exact-number", ChannelSearchMatchRank.PROVIDER),
                    candidate("name", ChannelSearchMatchRank.NAME),
                    candidate("programme", ChannelSearchMatchRank.PROGRAMME),
                ),
                "\"вечер*\"" to listOf(
                    candidate("exact-number", ChannelSearchMatchRank.PROVIDER),
                    candidate("name", ChannelSearchMatchRank.NAME),
                    candidate("programme", ChannelSearchMatchRank.PROGRAMME),
                ),
            ),
            summaries = listOf(
                summary("programme", "Третий", "3"),
                summary("name", "Россия вечер", "2"),
                summary("exact-number", "Первый", "Россия вечер"),
            ),
        )

        val snapshot = RoomChannelSearchRepository(dataSource, FakeGuideRepository())
            .observe(query("Россия вечер", limit = 2))
            .first()

        assertThat(snapshot.results.map { it.channel.channelId })
            .containsExactly("exact-number", "name")
            .inOrder()
        assertThat(snapshot.isTruncated).isTrue()
    }

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
                "\"Россия*\"" to listOf(
                    candidate("a", ChannelSearchMatchRank.NAME),
                    candidate("b", ChannelSearchMatchRank.NAME),
                ),
                "\"1*\"" to listOf(candidate("a", ChannelSearchMatchRank.PROVIDER)),
            ),
            summaries = listOf(summary("a", "Россия", "1"), summary("b", "Россия 24", "24")),
        )
        val repository = RoomChannelSearchRepository(dataSource, FakeGuideRepository())

        val snapshot = repository.observe(query("Россия 1")).first()

        assertThat(snapshot.results.map { it.channel.channelId }).containsExactly("a")
        assertThat(dataSource.candidateExpressions)
            .containsExactly("\"Россия*\"", "\"1*\"")
            .inOrder()
    }

    @Test
    fun broadTokenIsRecheckedInsideSelectiveSeedWithoutFalseNegative() = runTest {
        val target = "channel-1200"
        val broad = (0..1_200).map { index -> candidate("channel-$index", ChannelSearchMatchRank.NAME) }
        val dataSource = FakeSearchDataSource(
            candidates = mapOf(
                "\"канал*\"" to broad,
                "\"1200*\"" to listOf(candidate(target, ChannelSearchMatchRank.PROVIDER)),
            ),
            summaries = listOf(summary(target, "Канал точный", "1200")),
        )
        val repository = RoomChannelSearchRepository(dataSource, FakeGuideRepository())

        val snapshot = repository.observe(query("канал 1200")).first()

        assertThat(snapshot.results.map { it.channel.channelId }).containsExactly(target)
        assertThat(snapshot.isTruncated).isFalse()
        assertThat(dataSource.restrictedCandidateExpressions).containsExactly("\"канал*\"")
    }

    @Test
    fun overflowingMostSelectiveSeedKeepsHonestTruncation() = runTest {
        val broadA = (0..900).map { index -> candidate("channel-$index", ChannelSearchMatchRank.NAME) }
        val broadB = (0..900).map { index -> candidate("channel-$index", ChannelSearchMatchRank.PROVIDER) }
        val summaries = (0..799).map { index -> summary("channel-$index", "Канал $index", index.toString()) }
        val dataSource = FakeSearchDataSource(
            candidates = mapOf(
                "\"канал*\"" to broadA,
                "\"общий*\"" to broadB,
            ),
            summaries = summaries,
        )
        val repository = RoomChannelSearchRepository(dataSource, FakeGuideRepository())

        val snapshot = repository.observe(query("канал общий", limit = 10)).first()

        assertThat(snapshot.results).hasSize(10)
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
            candidates = mapOf("\"спорт*\"" to candidates),
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
                "\"новости*\"" to listOf(
                    candidate("mixed", ChannelSearchMatchRank.NAME),
                    candidate("metadata", ChannelSearchMatchRank.PROVIDER),
                ),
                "\"вечер*\"" to listOf(
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
    fun publicLimitTruncatesAndUsesOnlyPublishedGuideBoundary() = runTest {
        val candidates = (1..5).map { index -> candidate("c$index", ChannelSearchMatchRank.NAME) }
        val summaries = (1..5).map { index -> summary("c$index", "Канал $index", index.toString()) }
        val guide = FakeGuideRepository(currentTitles = mapOf("c1" to "Сейчас 1", "c2" to "Сейчас 2"))
        val dataSource = FakeSearchDataSource(
            candidates = mapOf("\"канал*\"" to candidates),
            summaries = summaries,
        )
        val repository = RoomChannelSearchRepository(dataSource, guide)

        val snapshot = repository.observe(query("канал", limit = 2)).first()

        assertThat(snapshot.results).hasSize(2)
        assertThat(snapshot.isTruncated).isTrue()
        assertThat(snapshot.nextBoundaryEpochMillis).isEqualTo(1_600)
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
) : ChannelSearchDataSource {
    val candidateExpressions = mutableListOf<String>()
    val restrictedCandidateExpressions = mutableListOf<String>()

    override fun observeChanges(): Flow<Unit> = flowOf(Unit)

    override suspend fun searchCandidates(
        profileId: String,
        ftsExpression: String,
        nowEpochMillis: Long,
        fetchLimit: Int,
        restrictToCanonicalIds: List<String>?,
    ): List<ChannelSearchCandidateRow> {
        candidateExpressions += ftsExpression
        if (restrictToCanonicalIds != null) restrictedCandidateExpressions += ftsExpression
        val allowed = restrictToCanonicalIds?.toSet()
        return candidates[ftsExpression]
            .orEmpty()
            .asSequence()
            .filter { row -> allowed == null || row.canonicalChannelId in allowed }
            .take(fetchLimit)
            .toList()
    }

    override suspend fun activeChannelSummaries(
        profileId: String,
        canonicalChannelIds: List<String>,
    ): List<PlayableChannelSummary> = summaries.filter { it.channelId in canonicalChannelIds }

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
