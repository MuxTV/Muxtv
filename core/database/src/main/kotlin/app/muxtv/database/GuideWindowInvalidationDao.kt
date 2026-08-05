package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

internal data class GuideWindowDataVersionRow(
    val hasSources: Boolean,
    val hasProviderChannels: Boolean,
    val hasCanonicalChannels: Boolean,
    val hasStreamVariants: Boolean,
    val hasOverlays: Boolean,
    val hasEpgSources: Boolean,
    val hasEpgMatches: Boolean,
    val hasEpgProgrammes: Boolean,
)

@Dao
internal interface GuideWindowInvalidationDao {
    @Query(
        """
        SELECT EXISTS(SELECT 1 FROM sources LIMIT 1) AS hasSources,
               EXISTS(SELECT 1 FROM provider_channels LIMIT 1) AS hasProviderChannels,
               EXISTS(SELECT 1 FROM canonical_channels LIMIT 1) AS hasCanonicalChannels,
               EXISTS(SELECT 1 FROM stream_variants LIMIT 1) AS hasStreamVariants,
               EXISTS(SELECT 1 FROM user_channel_overlays LIMIT 1) AS hasOverlays,
               EXISTS(SELECT 1 FROM epg_sources LIMIT 1) AS hasEpgSources,
               EXISTS(SELECT 1 FROM epg_channel_matches LIMIT 1) AS hasEpgMatches,
               EXISTS(SELECT 1 FROM epg_programmes LIMIT 1) AS hasEpgProgrammes
        """,
    )
    fun observeDataVersion(): Flow<GuideWindowDataVersionRow>
}
