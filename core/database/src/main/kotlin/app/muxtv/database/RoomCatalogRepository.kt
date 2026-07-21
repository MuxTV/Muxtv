package app.muxtv.database

import app.muxtv.catalog.CatalogRepository
import app.muxtv.common.CanonicalChannelId
import app.muxtv.model.CanonicalChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomCatalogRepository(
    private val catalogDao: CatalogDao,
) : CatalogRepository {
    override fun observeChannels(): Flow<List<CanonicalChannel>> =
        catalogDao.observeActiveCanonicalChannels().map { channels ->
            channels.map { channel -> channel.toModel() }
        }

    override suspend fun findChannel(id: CanonicalChannelId): CanonicalChannel? =
        catalogDao.findActiveCanonicalChannel(id.value)?.toModel()

    private fun CanonicalChannelEntity.toModel(): CanonicalChannel = CanonicalChannel(
        id = CanonicalChannelId(id),
        displayName = displayName,
    )
}
