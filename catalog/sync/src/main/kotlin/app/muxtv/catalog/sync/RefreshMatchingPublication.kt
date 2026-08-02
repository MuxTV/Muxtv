package app.muxtv.catalog.sync

import app.muxtv.database.RefreshCompletionDisposition
import app.muxtv.database.SourceRefreshRunState
import kotlinx.coroutines.CancellationException

internal suspend fun reconcileEpgAfterPublication(
    decision: EpgRefreshDecision,
    disposition: RefreshCompletionDisposition,
    reconcile: suspend () -> Unit,
) {
    val successfulGuidePublication =
        disposition == RefreshCompletionDisposition.APPLIED &&
            (decision is EpgRefreshDecision.Refreshed || decision is EpgRefreshDecision.NotModified)
    if (!successfulGuidePublication) return
    reconcileBestEffort(reconcile)
}

internal suspend fun reconcileSourceAfterPublication(
    decision: SourceRefreshDecision,
    disposition: RefreshCompletionDisposition,
    reconcile: suspend () -> Unit,
) {
    val publishedNewRevision =
        disposition == RefreshCompletionDisposition.APPLIED &&
            decision.state == SourceRefreshRunState.SUCCEEDED &&
            decision.workSucceeded &&
            decision.revisionNumber != null
    if (!publishedNewRevision) return
    reconcileBestEffort(reconcile)
}

private suspend fun reconcileBestEffort(reconcile: suspend () -> Unit) {
    try {
        reconcile()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // The producer revision is already durable. Matching is derived local state and can be rebuilt;
        // an ordinary reconciliation failure must not convert a successful network refresh into retry.
    }
}
