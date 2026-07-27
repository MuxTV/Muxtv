package app.muxtv.feature.sources

enum class SourcePlaybackApprovalResetResult {
    Reset,
    Unchanged,
    SourceNotFound,
    AccessUnavailable,
}

fun interface SourcePlaybackApprovalActions {
    suspend fun revokeAll(sourceId: String): SourcePlaybackApprovalResetResult

    companion object {
        val Unavailable = SourcePlaybackApprovalActions {
            SourcePlaybackApprovalResetResult.AccessUnavailable
        }
    }
}
