package app.muxtv.localcontrol.mxh1lan

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test

/** S2J2C1: only lease and fake Network callbacks; NOT a physical Android LAN test. */
class Lc04MxH1SelectedNetworkLeaseTest {
    private val clock = AtomicLong(10_000)
    private val eligible = AtomicBoolean(true)
    private val binderCalls = AtomicInteger(0)
    private val revocations = AtomicInteger(0)

    private fun newLease(
        bind: (Socket) -> Unit = { binderCalls.incrementAndGet() },
        permitted: () -> Boolean = eligible::get,
    ): Lc04MxH1SelectedNetworkLease =
        Lc04MxH1SelectedNetworkLease.forSelectedPrivateIpv4(
            numericIp = "192.168.42.10",
            port = 47721,
            elapsedMillis = clock::get,
            selectedRouteEligible = permitted,
            bindToSelectedNetwork = bind,
            revokeOriginalQrAndKeys = { revocations.incrementAndGet() },
        )

    @Test fun `one unconnected socket can claim a private route and revocation closes it`() {
        val socket = Socket()
        val lease = newLease()
        try {
            assertThat(lease.bindOnce(socket)).isTrue()
            assertThat(binderCalls.get()).isEqualTo(1)
            assertThat(lease.endpointIfBoundAndEligible()?.address?.hostAddress)
                .isEqualTo("192.168.42.10")
            assertThat(lease.endpointIfBoundAndEligible()?.port).isEqualTo(47721)
            assertThat(lease.checkpoint()).isTrue()
            lease.revokeSelectedNetwork()
            assertThat(socket.isClosed).isTrue()
            assertThat(lease.checkpoint()).isFalse()
            assertThat(lease.endpointIfBoundAndEligible()).isNull()
            assertThat(revocations.get()).isEqualTo(1)
            lease.close()
            assertThat(revocations.get()).isEqualTo(1)
        } finally {
            lease.close()
            socket.close()
        }
    }

    @Test fun `public loopback wildcard ipv6 and invalid port are rejected before bind`() {
        for (address in listOf("127.0.0.1", "0.0.0.0", "8.8.8.8",
            "172.32.1.2", "169.254.1.1", "::1", "localhost", "192.168.1.1:80"
        )) {
            assertThat(runCatching {
                Lc04MxH1SelectedNetworkLease.forSelectedPrivateIpv4(
                    address, 443, clock::get, eligible::get,
                    { binderCalls.incrementAndGet() }, { revocations.incrementAndGet() },
                )
            }.isFailure).isTrue()
        }
        for (port in listOf(0, -1, 65536)) {
            assertThat(runCatching {
                Lc04MxH1SelectedNetworkLease.forSelectedPrivateIpv4(
                    "10.2.0.1", port, clock::get, eligible::get,
                    { binderCalls.incrementAndGet() }, { revocations.incrementAndGet() },
                )
            }.isFailure).isTrue()
        }
        assertThat(binderCalls.get()).isEqualTo(0)
    }

    @Test fun `unapproved route never enters platform binder and revokes owner`() {
        eligible.set(false)
        val socket = Socket()
        val lease = newLease()
        try {
            assertThat(lease.bindOnce(socket)).isFalse()
            assertThat(binderCalls.get()).isEqualTo(0)
            assertThat(revocations.get()).isEqualTo(1)
            assertThat(socket.isClosed).isTrue()
        } finally { lease.close(); socket.close() }
    }

    @Test fun `route or permission revoked after bind closes active socket synchronously`() {
        val socket = Socket()
        val lease = newLease()
        try {
            assertThat(lease.bindOnce(socket)).isTrue()
            eligible.set(false)
            assertThat(lease.checkpoint()).isFalse()
            assertThat(socket.isClosed).isTrue()
            assertThat(revocations.get()).isEqualTo(1)
            assertThat(lease.bindOnce(Socket())).isFalse()
            assertThat(binderCalls.get()).isEqualTo(1)
        } finally { lease.close(); socket.close() }
    }

    @Test fun `platform binder exception is terminal with no reconnect grant`() {
        val socket = Socket()
        val lease = newLease(bind = { throw IOException("network revoked") })
        try {
            assertThat(lease.bindOnce(socket)).isFalse()
            assertThat(socket.isClosed).isTrue()
            assertThat(revocations.get()).isEqualTo(1)
            assertThat(lease.endpointIfBoundAndEligible()).isNull()
            assertThat(lease.bindOnce(Socket())).isFalse()
        } finally { lease.close(); socket.close() }
    }

    @Test fun `prebound socket is never allowed to bypass selected network bind`() {
        val socket = Socket()
        socket.bind(InetSocketAddress("127.0.0.1", 0))
        val lease = newLease()
        try {
            assertThat(lease.bindOnce(socket)).isFalse()
            assertThat(binderCalls.get()).isEqualTo(0)
            assertThat(revocations.get()).isEqualTo(1)
            assertThat(socket.isClosed).isTrue()
        } finally { lease.close(); socket.close() }
    }

    @Test fun `network disappears during bind is rejected after callback returns`() {
        val socket = Socket()
        val lease = newLease(bind = {
            binderCalls.incrementAndGet()
            eligible.set(false)
        })
        try {
            assertThat(lease.bindOnce(socket)).isFalse()
            assertThat(binderCalls.get()).isEqualTo(1)
            assertThat(revocations.get()).isEqualTo(1)
            assertThat(socket.isClosed).isTrue()
        } finally { lease.close(); socket.close() }
    }

    @Test fun `onStop revokes promptly even while Android binder callback is blocked`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val socket = Socket()
        val lease = newLease(bind = {
            entered.countDown()
            check(resume.await(4, TimeUnit.SECONDS))
        })
        val result = AtomicBoolean(true)
        val worker = Thread {
            result.set(lease.bindOnce(socket))
        }.apply { isDaemon = true; start() }
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue()
            lease.revokeSelectedNetwork()
            assertThat(socket.isClosed).isTrue()
            assertThat(revocations.get()).isEqualTo(1)
            resume.countDown()
            worker.join(3_000)
            assertThat(worker.isAlive).isFalse()
            assertThat(result.get()).isFalse()
        } finally {
            resume.countDown()
            lease.close()
            socket.close()
            worker.join(1_000)
        }
    }

    @Test fun `120 second TTL and elapsed clock rollback revoke original owner`() {
        val socket = Socket()
        val lease = newLease()
        try {
            assertThat(lease.bindOnce(socket)).isTrue()
            clock.addAndGet(119_999)
            assertThat(lease.checkpoint()).isTrue()
            clock.incrementAndGet()
            assertThat(lease.checkpoint()).isFalse()
            assertThat(socket.isClosed).isTrue()
            assertThat(revocations.get()).isEqualTo(1)
        } finally { lease.close(); socket.close() }

        val second = Socket()
        val other = newLease()
        try {
            assertThat(other.bindOnce(second)).isTrue()
            clock.addAndGet(22)
            assertThat(other.checkpoint()).isTrue()
            clock.decrementAndGet()
            assertThat(other.checkpoint()).isFalse()
            assertThat(second.isClosed).isTrue()
            assertThat(revocations.get()).isEqualTo(2)
        } finally { other.close(); second.close() }
    }

    @Test fun `after successful bind any second bind consumes first socket and QR owner`() {
        val first = Socket()
        val second = Socket()
        val lease = newLease()
        try {
            assertThat(lease.bindOnce(first)).isTrue()
            assertThat(lease.bindOnce(second)).isFalse()
            assertThat(first.isClosed).isTrue()
            assertThat(revocations.get()).isEqualTo(1)
        } finally { lease.close(); first.close(); second.close() }
    }
}
