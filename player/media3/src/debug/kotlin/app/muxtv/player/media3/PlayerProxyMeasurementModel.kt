package app.muxtv.player.media3

internal data class PlayerProxyMeasurementSummary(
    val sampleCount: Int,
    val minimum: Long,
    val p50: Long,
    val p90: Long,
    val p95: Long,
    val maximum: Long,
) {
    init {
        require(sampleCount > 0)
        require(minimum >= 0L)
        require(minimum <= p50)
        require(p50 <= p90)
        require(p90 <= p95)
        require(p95 <= maximum)
    }
}

internal data class PlayerProxyMeasurementWorkload(
    val warmupSamples: Int,
    val measuredSamples: Int,
    val operationsPerSample: Int,
) {
    init {
        require(warmupSamples in 0..20)
        require(measuredSamples in 5..100)
        require(operationsPerSample in 1..100_000)
    }
}

internal data class PlayerProxyMeasurementSpec(
    val sourceCommit: String,
    val runnerLabel: String,
    val workload: PlayerProxyMeasurementWorkload,
) {
    init {
        require(SOURCE_COMMIT.matches(sourceCommit))
        require(RUNNER_LABEL.matches(runnerLabel))
    }

    private companion object {
        val SOURCE_COMMIT = Regex("[0-9a-f]{40}")
        val RUNNER_LABEL = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    }
}

internal class PlayerProxyMeasurementEnvironment(
    val manufacturer: String,
    val model: String,
    val fingerprint: String,
    val apiLevel: Int,
    supportedAbis: List<String>,
    val lowRamDevice: Boolean,
    val memoryClassMb: Int,
    val availableProcessors: Int,
) {
    val supportedAbis: List<String> = supportedAbis.toList()

    init {
        require(manufacturer.isNotBlank())
        require(model.isNotBlank())
        require(fingerprint.isNotBlank())
        require(apiLevel > 0)
        require(this.supportedAbis.isNotEmpty())
        require(this.supportedAbis.all(String::isNotBlank))
        require(memoryClassMb > 0)
        require(availableProcessors > 0)
    }

    override fun toString(): String =
        "PlayerProxyMeasurementEnvironment(apiLevel=$apiLevel, abiCount=${supportedAbis.size}, " +
            "lowRamDevice=$lowRamDevice, memoryClassMb=$memoryClassMb, " +
            "availableProcessors=$availableProcessors)"
}

internal data class PlayerProxyMeasurementSample(
    val sampleIndex: Int,
    val batchWallTimeNanos: Long,
    val operationCount: Int,
    val normalizedNanosPerOperation: Long,
    val successfulResultCount: Int,
) {
    init {
        require(sampleIndex > 0)
        require(batchWallTimeNanos > 0L)
        require(operationCount > 0)
        require(normalizedNanosPerOperation > 0L)
        require(successfulResultCount in 0..operationCount)
    }
}

internal class PlayerProxyOperationReport(
    val operationId: String,
    val expectedSuccessfulResultCount: Int,
    samples: List<PlayerProxyMeasurementSample>,
    val batchWallTimeNanos: PlayerProxyMeasurementSummary,
    val normalizedNanosPerOperation: PlayerProxyMeasurementSummary,
) {
    val samples: List<PlayerProxyMeasurementSample> = samples.toList()

    init {
        require(OPERATION_ID.matches(operationId))
        require(expectedSuccessfulResultCount > 0)
        require(this.samples.isNotEmpty())
        require(this.samples.map(PlayerProxyMeasurementSample::sampleIndex) == (1..this.samples.size).toList())
        require(this.samples.all { sample ->
            sample.operationCount == expectedSuccessfulResultCount &&
                sample.successfulResultCount == expectedSuccessfulResultCount
        })
        require(batchWallTimeNanos.sampleCount == this.samples.size)
        require(normalizedNanosPerOperation.sampleCount == this.samples.size)
    }

    override fun toString(): String =
        "PlayerProxyOperationReport(operationId=$operationId, sampleCount=${samples.size}, " +
            "expectedSuccessfulResultCount=$expectedSuccessfulResultCount)"

    private companion object {
        val OPERATION_ID = Regex("[a-z0-9][a-z0-9-]{0,63}")
    }
}

internal class PlayerProxyMeasurementReport(
    val schemaVersion: Int,
    val methodVersion: Int,
    val buildMode: String,
    val thresholdApplied: Boolean,
    val sourceCommit: String,
    val runnerLabel: String,
    val workload: PlayerProxyMeasurementWorkload,
    val requestProfileSha256: String,
    val environment: PlayerProxyMeasurementEnvironment,
    operations: List<PlayerProxyOperationReport>,
    val failureCount: Int,
    limitations: List<String>,
) {
    val operations: List<PlayerProxyOperationReport> = operations.toList()
    val limitations: List<String> = limitations.toList()

    init {
        require(schemaVersion > 0)
        require(methodVersion > 0)
        require(buildMode == "debug-instrumentation")
        require(!thresholdApplied)
        require(Regex("[0-9a-f]{40}").matches(sourceCommit))
        require(Regex("[a-z0-9][a-z0-9._-]{0,63}").matches(runnerLabel))
        require(Regex("[0-9a-f]{64}").matches(requestProfileSha256))
        require(this.operations.isNotEmpty())
        require(this.operations.map(PlayerProxyOperationReport::operationId).distinct().size == this.operations.size)
        require(this.operations.all { operation ->
            operation.samples.size == workload.measuredSamples &&
                operation.expectedSuccessfulResultCount == workload.operationsPerSample
        })
        require(failureCount == 0)
        require(this.limitations.isNotEmpty())
        require(this.limitations.all(String::isNotBlank))
    }

    override fun toString(): String =
        "PlayerProxyMeasurementReport(schemaVersion=$schemaVersion, methodVersion=$methodVersion, " +
            "buildMode=$buildMode, thresholdApplied=$thresholdApplied, operationCount=${operations.size}, " +
            "failureCount=$failureCount)"
}

internal object PlayerProxyMeasurementStatistics {
    fun summarize(values: List<Long>): PlayerProxyMeasurementSummary {
        require(values.isNotEmpty())
        require(values.all { value -> value >= 0L })
        val sorted = values.sorted()
        return PlayerProxyMeasurementSummary(
            sampleCount = sorted.size,
            minimum = sorted.first(),
            p50 = sorted.nearestRank(50),
            p90 = sorted.nearestRank(90),
            p95 = sorted.nearestRank(95),
            maximum = sorted.last(),
        )
    }

    private fun List<Long>.nearestRank(percent: Int): Long {
        require(percent in 1..100)
        val rank = ((size * percent) + 99) / 100
        return this[rank.coerceIn(1, size) - 1]
    }
}
