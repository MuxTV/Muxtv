package app.muxtv

import app.muxtv.catalog.ChannelSearchQuery
import app.muxtv.catalog.ChannelSearchRepository
import app.muxtv.catalog.ChannelSearchSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal object NoChannelSearchRepository : ChannelSearchRepository {
    override fun observe(query: ChannelSearchQuery): Flow<ChannelSearchSnapshot> =
        flowOf(ChannelSearchSnapshot.EMPTY)
}
