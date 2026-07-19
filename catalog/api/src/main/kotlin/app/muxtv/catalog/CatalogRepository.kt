package app.muxtv.catalog

import app.muxtv.common.CanonicalChannelId
import app.muxtv.model.CanonicalChannel
import kotlinx.coroutines.flow.Flow

interface CatalogRepository {
    fun observeChannels(): Flow<List<CanonicalChannel>>
    suspend fun findChannel(id: CanonicalChannelId): CanonicalChannel?
}
