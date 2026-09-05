package app.muxtv.common.diagnostics

internal enum class BackgroundWorkFailureKind {
    INITIALIZATION,
    SCHEDULING,
    WORKER_INITIALIZATION,
    WORKER_EXECUTION,
}

internal enum class BackgroundWorkerCategory {
    SOURCE_REFRESH,
    EPG_REFRESH,
    UNKNOWN,
}

internal data class BackgroundWorkFailureObservation(
    val kind: BackgroundWorkFailureKind,
    val timestampEpochMillis: Long,
    val workerCategory: BackgroundWorkerCategory,
) {
    init {
        require(timestampEpochMillis >= 0L) { "Timestamp must be non-negative" }
    }
}
