package app.muxtv.common.tracing

import androidx.tracing.Tracer

enum class MuxTvTraceSection(val traceName: String) {
    SOURCE_REFRESH("MuxTv.SourceRefresh"),
    M3U_PARSE("MuxTv.M3uParse"),
    CATALOG_STAGE("MuxTv.CatalogStage"),
    CATALOG_ACTIVATE("MuxTv.CatalogActivate"),
    EPG_IMPORT("MuxTv.EpgImport"),
    SEARCH("MuxTv.Search"),
    PLAYER_PREPARE("MuxTv.PlayerPrepare"),
    FIRST_FRAME("MuxTv.FirstFrame"),
}

object MuxTvTrace {
    const val category: String = "MuxTv"

    val global: MuxTvTracer = MuxTvTracer(AndroidXMuxTvTraceBackend)
    val disabled: MuxTvTracer = MuxTvTracer(null)

    internal fun forTesting(backend: MuxTvTraceBackend?): MuxTvTracer = MuxTvTracer(backend)
}

class MuxTvTracer internal constructor(
    private val backend: MuxTvTraceBackend?,
) {
    fun <T> section(
        section: MuxTvTraceSection,
        block: () -> T,
    ): T {
        val activeBackend = backend ?: return block()
        var started = false
        var outcome: ProductOutcome<T>? = null

        return try {
            activeBackend.trace(section) {
                if (started) return@trace outcome.resolveAlreadyStarted()
                started = true
                try {
                    val value = block()
                    outcome = ProductOutcome.Success(value)
                    value
                } catch (failure: Throwable) {
                    outcome = ProductOutcome.Failure(failure)
                    throw failure
                }
            }
        } catch (traceFailure: Throwable) {
            traceFailure.throwIfNotRecoverableTraceInfrastructureFailure(outcome)
            when (val productOutcome = outcome) {
                is ProductOutcome.Success -> productOutcome.value
                is ProductOutcome.Failure -> throw productOutcome.failure
                null -> if (!started) block() else throw traceFailure
            }
        }
    }

    suspend fun <T> coroutineSection(
        section: MuxTvTraceSection,
        block: suspend () -> T,
    ): T {
        val activeBackend = backend ?: return block()
        var started = false
        var outcome: ProductOutcome<T>? = null

        return try {
            activeBackend.traceCoroutine(section) {
                if (started) return@traceCoroutine outcome.resolveAlreadyStarted()
                started = true
                try {
                    val value = block()
                    outcome = ProductOutcome.Success(value)
                    value
                } catch (failure: Throwable) {
                    outcome = ProductOutcome.Failure(failure)
                    throw failure
                }
            }
        } catch (traceFailure: Throwable) {
            traceFailure.throwIfNotRecoverableTraceInfrastructureFailure(outcome)
            when (val productOutcome = outcome) {
                is ProductOutcome.Success -> productOutcome.value
                is ProductOutcome.Failure -> throw productOutcome.failure
                null -> if (!started) block() else throw traceFailure
            }
        }
    }
}

internal interface MuxTvTraceBackend {
    fun <T> trace(
        section: MuxTvTraceSection,
        block: () -> T,
    ): T

    suspend fun <T> traceCoroutine(
        section: MuxTvTraceSection,
        block: suspend () -> T,
    ): T
}

private object AndroidXMuxTvTraceBackend : MuxTvTraceBackend {
    override fun <T> trace(
        section: MuxTvTraceSection,
        block: () -> T,
    ): T = Tracer.global.trace(
        category = MuxTvTrace.category,
        name = section.traceName,
    ) {
        block()
    }

    override suspend fun <T> traceCoroutine(
        section: MuxTvTraceSection,
        block: suspend () -> T,
    ): T = Tracer.global.traceCoroutine(
        category = MuxTvTrace.category,
        name = section.traceName,
    ) {
        block()
    }
}

private sealed interface ProductOutcome<out T> {
    data class Success<T>(val value: T) : ProductOutcome<T>
    data class Failure(val failure: Throwable) : ProductOutcome<Nothing>
}

private fun <T> ProductOutcome<T>?.resolveAlreadyStarted(): T = when (this) {
    is ProductOutcome.Success -> value
    is ProductOutcome.Failure -> throw failure
    null -> error("Trace backend attempted to execute a product block concurrently.")
}

private fun Throwable.throwIfNotRecoverableTraceInfrastructureFailure(productOutcome: ProductOutcome<*>?) {
    if (productOutcome is ProductOutcome.Failure) return
    if (this is Exception || this is LinkageError) return
    throw this
}
