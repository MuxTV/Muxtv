package app.muxtv.catalog.sync

import app.muxtv.catalog.refresh.RemoteSourceRefreshRequest
import app.muxtv.catalog.refresh.RemoteSourceRefreshResult
import app.muxtv.catalog.refresh.SourceAccessKind
import app.muxtv.catalog.refresh.SourceAccessReference
import app.muxtv.catalog.refresh.XtreamLiveRefreshRequest
import app.muxtv.catalog.refresh.XtreamLiveRefreshResult
import app.muxtv.database.SourceRefreshTarget

internal class SourceRefreshDispatcher(
    private val m3uRefresh: suspend (RemoteSourceRefreshRequest) -> RemoteSourceRefreshResult,
    private val xtreamRefresh: suspend (XtreamLiveRefreshRequest) -> XtreamLiveRefreshResult,
) {
    suspend fun refresh(
        target: SourceRefreshTarget,
        runToken: String,
    ): SourceRefreshDecision {
        val credentialRef = target.credentialRef
            ?: return SourceRefreshOutcomeMapper.missingCredentialReference()
        val accessReference = try {
            SourceAccessReference.parse(credentialRef)
        } catch (_: IllegalArgumentException) {
            return SourceRefreshOutcomeMapper.invalidCredentialReference()
        }

        return when (accessReference.kind) {
            SourceAccessKind.M3U -> SourceRefreshOutcomeMapper.map(
                m3uRefresh(
                    RemoteSourceRefreshRequest(
                        sourceId = target.sourceId,
                        sourceName = target.sourceName,
                        accessCredentialId = accessReference.credentialId,
                        refreshRunToken = runToken,
                    ),
                ),
            )

            SourceAccessKind.XTREAM -> SourceRefreshOutcomeMapper.map(
                xtreamRefresh(
                    XtreamLiveRefreshRequest(
                        sourceId = target.sourceId,
                        sourceName = target.sourceName,
                        accessCredentialId = accessReference.credentialId,
                        accessReference = accessReference,
                        refreshRunToken = runToken,
                    ),
                ),
            )
        }
    }
}
