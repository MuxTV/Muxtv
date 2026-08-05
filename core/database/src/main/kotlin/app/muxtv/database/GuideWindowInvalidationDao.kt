package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

internal data class GuideWindowDataVersionRow(
    val sourceCount: Long,
    val providerChannelCount: Long,
    val canonicalChannelCount: Long,
    val streamVariantCount: Long,
    val overlayCount: Long,
    val epgSourceCount: Long,
    val epgMatchCount: Long,
    val epgProgrammeCount: Long,
)

@Dao
internal interface GuideWindowInvalidationDao {
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM sources) AS sourceCount,
               (SELECT COUNT(*) FROM provider_channels) AS providerChannelCount,
               (SELECT COUNT(*) FROM canonical_channels) AS canonicalChannelCount,
               (SELECT COUNT(*) FROM stream_variants) AS streamVariantCount,
               (SELECT COUNT(*) FROM user_channel_overlays) AS overlayCount,
               (SELECT COUNT(*) FROM epg_sources) AS epgSourceCount,
               (SELECT COUNT(*) FROM epg_channel_matches) AS epgMatchCount,
               (SELECT COUNT(*) FROM epg_programmes) AS epgProgrammeCount
        """,
    )
    fun observeDataVersion(): Flow<GuideWindowDataVersionRow>
}
