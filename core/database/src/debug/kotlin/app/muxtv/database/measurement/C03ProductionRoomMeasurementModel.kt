package app.muxtv.database.measurement

internal enum class C03ProductionMeasurementVariant {
    A_CURRENT_PRODUCTION,
    B_IMMUTABLE_REUSE,
}

internal data class C03ProductionRoomMeasurementEnvironment(
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
    val availableProcessors: Int,
)

internal data class C03ProductionRoomFileState(
    val databaseBytes: Long,
    val walBytes: Long,
    val shmBytes: Long,
) {
    val totalBytes: Long get() = databaseBytes + walBytes + shmBytes
}

internal data class C03ProductionRoomWriteCounts(
    val providerOrPayloadWrites: Long,
    val searchPayloadWrites: Long,
    val searchDocumentWrites: Long,
    val membershipWrites: Long,
    val revisionMetadataWrites: Long,
    val sourceMetadataWrites: Long,
    val cleanupDeletes: Long,
)

internal data class C03ProductionRoomTimingSample(
    val stageTotalNanos: Long,
    val publicationNanos: Long,
    val cleanupNanos: Long,
    val cancellationCleanupNanos: Long,
    val browseNanos: Long,
    val providerLookupNanos: Long,
    val searchNanos: Long,
    val before: C03ProductionRoomFileState,
    val afterStage: C03ProductionRoomFileState,
    val afterPublication: C03ProductionRoomFileState,
    val afterCleanup: C03ProductionRoomFileState,
)

internal data class C03ProductionRoomDistribution(
    val medianNanos: Long,
    val p90Nanos: Long,
    val p95Nanos: Long,
    val samples: List<Long>,
)

internal data class C03ProductionRoomQueryPlan(
    val operation: String,
    val details: List<String>,
    val indexed: Boolean,
)

internal data class C03ProductionRoomVariantMeasurement(
    val variant: C03ProductionMeasurementVariant,
    val correctnessDigestSha256: String,
    val correctnessCount: Int,
    val logicalIdentityDigestSha256: String,
    val previousGoodDigestSha256: String,
    val writes: C03ProductionRoomWriteCounts,
    val samples: List<C03ProductionRoomTimingSample>,
    val stage: C03ProductionRoomDistribution,
    val publication: C03ProductionRoomDistribution,
    val cleanup: C03ProductionRoomDistribution,
    val browse: C03ProductionRoomDistribution,
    val providerLookup: C03ProductionRoomDistribution,
    val search: C03ProductionRoomDistribution,
    val queryPlans: List<C03ProductionRoomQueryPlan>,
)

internal data class C03ProductionRoomScenarioMeasurement(
    val scenarioId: String,
    val expectedCorrectnessDigestSha256: String,
    val expectedCorrectnessCount: Int,
    val variants: List<C03ProductionRoomVariantMeasurement>,
)

internal data class C03ProductionRoomRepeatedRevisionStorage(
    val variant: C03ProductionMeasurementVariant,
    val revisionCount: Int,
    val before: C03ProductionRoomFileState,
    val beforeCompaction: C03ProductionRoomFileState,
    val afterCompaction: C03ProductionRoomFileState,
    val orphanPayloadRows: Int,
    val orphanSearchPayloadRows: Int,
    val compactionNanos: Long,
    val compactionDeletedRows: Int,
)

internal data class C03ProductionRoomSafetyMeasurement(
    val variant: C03ProductionMeasurementVariant,
    val previousGoodPreserved: Boolean,
    val supersededRejected: Boolean,
    val partialFailurePublished: Boolean,
    val cancellationLatePublished: Boolean,
    val cleanupBounded: Boolean,
    val cancellationCleanupNanos: Long,
)

internal data class C03ProductionRoomMeasurementReport(
    val schemaVersion: Int,
    val methodVersion: String,
    val sourceCommit: String,
    val warmupIterations: Int,
    val measuredIterations: Int,
    val entryCount: Int,
    val environment: C03ProductionRoomMeasurementEnvironment,
    val scenarios: List<C03ProductionRoomScenarioMeasurement>,
    val repeatedRevisionStorage: List<C03ProductionRoomRepeatedRevisionStorage>,
    val safety: List<C03ProductionRoomSafetyMeasurement>,
    val redactionPassed: Boolean,
    val limitations: List<String>,
)
