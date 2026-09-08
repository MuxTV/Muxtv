package app.muxtv.network

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request

fun interface MuxTvNetworkTimingSink {
    fun record(observation: MuxTvNetworkTimingObservation)

    companion object {
        val NONE: MuxTvNetworkTimingSink = MuxTvNetworkTimingSink { }
    }
}

internal class MuxTvNetworkTimingListenerFactory(
    private val clientKind: MuxTvNetworkClientKind,
    private val sink: MuxTvNetworkTimingSink,
) : EventListener.Factory {
    override fun create(call: Call): EventListener = MuxTvNetworkTimingListener(
        clientKind = clientKind,
        sink = sink,
    )
}

private class MuxTvNetworkTimingListener(
    private val clientKind: MuxTvNetworkClientKind,
    private val sink: MuxTvNetworkTimingSink,
) : EventListener() {
    private val lock = Any()
    private val durations = LongArray(MuxTvNetworkPhase.entries.size)
    private val observed = BooleanArray(MuxTvNetworkPhase.entries.size)

    private var callStartNanos: Long? = null
    private var dnsStartNanos: Long? = null
    private var connectStartNanos: Long? = null
    private var tlsStartNanos: Long? = null
    private var connectionAcquireStartNanos: Long? = null
    private var requestStartNanos: Long? = null
    private var ttfbStartNanos: Long? = null
    private var responseBodyStartNanos: Long? = null
    private var finished = false

    override fun callStart(call: Call) {
        val now = System.nanoTime()
        synchronized(lock) {
            if (!finished) {
                callStartNanos = now
                connectionAcquireStartNanos = now
            }
        }
    }

    override fun dnsStart(call: Call, domainName: String) {
        synchronized(lock) {
            if (!finished) {
                dnsStartNanos = System.nanoTime()
            }
        }
    }

    override fun dnsEnd(
        call: Call,
        domainName: String,
        inetAddressList: List<InetAddress>,
    ) {
        val now = System.nanoTime()
        synchronized(lock) {
            if (!finished) {
                dnsStartNanos = finishInterval(
                    phase = MuxTvNetworkPhase.DNS,
                    startNanos = dnsStartNanos,
                    endNanos = now,
                )
            }
        }
    }

    override fun connectStart(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
    ) {
        synchronized(lock) {
            if (!finished) {
                connectStartNanos = System.nanoTime()
            }
        }
    }

    override fun secureConnectStart(call: Call) {
        synchronized(lock) {
            if (!finished) {
                tlsStartNanos = System.nanoTime()
            }
        }
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        val now = System.nanoTime()
        synchronized(lock) {
            if (!finished) {
                tlsStartNanos = finishInterval(
                    phase = MuxTvNetworkPhase.TLS,
                    startNanos = tlsStartNanos,
                    endNanos = now,
                )
            }
        }
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        val now = System.nanoTime()
        synchronized(lock) {
            if (!finished) {
                connectStartNanos = finishInterval(
                    phase = MuxTvNetworkPhase.CONNECT,
                    startNanos = connectStartNanos,
                    endNanos = now,
                )
            }
        }
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        val now = System.nanoTime()
        synchronized(lock) {
            if (!finished) {
                tlsStartNanos = finishInterval(
                    phase = MuxTvNetworkPhase.TLS,
                    startNanos = tlsStartNanos,
                    endNanos = now,
                )
                connectStartNanos = finishInterval(
                    phase = MuxTvNetworkPhase.CONNECT,
                    startNanos = connectStartNanos,
                    endNanos = now,
                )
            }
        }
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        val now = System.nanoTime()
        synchronized(lock) {
            if (!finished) {
                connectionAcquireStartNanos = finishInterval(
                    phase = MuxTvNetworkPhase.CONNECTION_ACQUIRE,
                    startNanos = connectionAcquireStartNanos,
                    endNanos = now,
                )
            }
        }
    }

    override fun connectionReleased(call: Call, connection: Connection) {
        synchronized(lock) {
            if (!finished) {
                connectionAcquireStartNanos = System.nanoTime()
            }
        }
    }

    override fun requestHeadersStart(call: Call) {
        synchronized(lock) {
            if (!finished) {
                requestStartNanos = System.nanoTime()
            }
        }
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        val now = System.nanoTime()
        synchronized(lock) {
            if (!finished) {
                requestStartNanos = finishInterval(
                    phase = MuxTvNetworkPhase.REQUEST,
                    startNanos = requestStartNanos,
                    endNanos = now,
                )
                ttfbStartNanos = now
            }
        }
    }

    override fun responseHeadersStart(call: Call) {
        val now = System.nanoTime()
        synchronized(lock) {
            if (!finished) {
                ttfbStartNanos = finishInterval(
                    phase = MuxTvNetworkPhase.TTFB,
                    startNanos = ttfbStartNanos,
                    endNanos = now,
                )
            }
        }
    }

    override fun responseBodyStart(call: Call) {
        synchronized(lock) {
            if (!finished) {
                responseBodyStartNanos = System.nanoTime()
            }
        }
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        val now = System.nanoTime()
        synchronized(lock) {
            if (!finished) {
                responseBodyStartNanos = finishInterval(
                    phase = MuxTvNetworkPhase.RESPONSE_BODY,
                    startNanos = responseBodyStartNanos,
                    endNanos = now,
                )
            }
        }
    }

    override fun responseFailed(call: Call, ioe: IOException) {
        val now = System.nanoTime()
        synchronized(lock) {
            if (!finished) {
                responseBodyStartNanos = finishInterval(
                    phase = MuxTvNetworkPhase.RESPONSE_BODY,
                    startNanos = responseBodyStartNanos,
                    endNanos = now,
                )
            }
        }
    }

    override fun callEnd(call: Call) {
        emitTerminal(MuxTvNetworkOutcome.SUCCEEDED)
    }

    override fun callFailed(call: Call, ioe: IOException) {
        emitTerminal(MuxTvNetworkOutcome.FAILED)
    }

    private fun emitTerminal(outcome: MuxTvNetworkOutcome) {
        val now = System.nanoTime()
        val observations = synchronized(lock) {
            if (finished) {
                return
            }
            finished = true

            dnsStartNanos = finishInterval(MuxTvNetworkPhase.DNS, dnsStartNanos, now)
            tlsStartNanos = finishInterval(MuxTvNetworkPhase.TLS, tlsStartNanos, now)
            connectStartNanos = finishInterval(MuxTvNetworkPhase.CONNECT, connectStartNanos, now)
            requestStartNanos = finishInterval(MuxTvNetworkPhase.REQUEST, requestStartNanos, now)
            ttfbStartNanos = finishInterval(MuxTvNetworkPhase.TTFB, ttfbStartNanos, now)
            responseBodyStartNanos = finishInterval(
                MuxTvNetworkPhase.RESPONSE_BODY,
                responseBodyStartNanos,
                now,
            )
            callStartNanos?.let { startNanos ->
                addDuration(
                    phase = MuxTvNetworkPhase.TOTAL,
                    durationNanos = muxTvNetworkDurationNanos(startNanos, now),
                )
            }
            callStartNanos = null
            connectionAcquireStartNanos = null

            MuxTvNetworkPhase.entries.map { phase ->
                MuxTvNetworkTimingObservation(
                    clientKind = clientKind,
                    phase = phase,
                    outcome = outcome,
                    durationNanos = durationOrNull(phase),
                )
            }
        }

        observations.forEach { observation ->
            try {
                sink.record(observation)
            } catch (_: RuntimeException) {
                // Timing is diagnostic-only and must never replace the HTTP result.
            }
        }
    }

    private fun finishInterval(
        phase: MuxTvNetworkPhase,
        startNanos: Long?,
        endNanos: Long,
    ): Long? {
        if (startNanos != null) {
            addDuration(
                phase = phase,
                durationNanos = muxTvNetworkDurationNanos(startNanos, endNanos),
            )
        }
        return null
    }

    private fun addDuration(
        phase: MuxTvNetworkPhase,
        durationNanos: Long,
    ) {
        val index = phase.ordinal
        val current = durations[index]
        durations[index] = if (Long.MAX_VALUE - current < durationNanos) {
            Long.MAX_VALUE
        } else {
            current + durationNanos
        }
        observed[index] = true
    }

    private fun durationOrNull(phase: MuxTvNetworkPhase): Long? =
        if (observed[phase.ordinal]) durations[phase.ordinal] else null
}
