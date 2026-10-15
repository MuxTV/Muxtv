package app.muxtv.localcontrol.server

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LanSecurityTest {
    @Test fun privateAddressesAccepted() {
        listOf("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254",
            "192.168.0.1", "192.168.200.200", "fd00::1234", "fc00::1"
        ).forEach { assertThat(LanAddressPolicy.isPrivateLiteral(it)).isTrue() }
    }

    @Test fun publicLoopbackMappedAndTrickyAddressesDenied() {
        listOf("0.0.0.0", "127.0.0.1", "169.254.1.1", "8.8.8.8", "192.0.0.1",
            "172.32.0.1", "192.169.1.1", "100.64.0.1", "localhost", "0xC0A80101",
            "0300.0250.1.2", "192.168.01.2", "192.168.1.256", "192.168.1.1 ",
            "fe80::1", "::1", "::", "::ffff:192.168.1.1", "2001:db8::1",
            "fd00::1%wlan0", "http://192.168.1.2", "192.168.1.2:80"
        ).forEach { assertThat(LanAddressPolicy.isPrivateLiteral(it)).isFalse() }
    }

    @Test fun invalidBindRefused() {
        listOf("0.0.0.0", "127.0.0.1", "8.8.8.8").forEach { bad ->
            var rejected = false
            try { LanEndpoint(bad, 8080) } catch (_: IllegalArgumentException) { rejected = true }
            assertThat(rejected).isTrue()
        }
    }

    @Test fun hostOriginMarkerAndRemotePeerMustMatchExactly() {
        val policy = LanRequestPolicy(LanEndpoint("192.168.10.5", 45678))
        val host = "192.168.10.5:45678"
        val origin = "http://$host"
        fun accept(
            h: List<String> = listOf(host),
            o: List<String> = listOf(origin),
            m: List<String> = listOf("1"),
            ip: String = "192.168.10.7",
        ) = policy.accepts(h, o, m, ip)
        assertThat(accept()).isTrue()
        assertThat(accept(h = listOf("evil.example"))).isFalse()
        assertThat(accept(h = listOf(host, host))).isFalse()
        assertThat(accept(o = listOf("null"))).isFalse()
        assertThat(accept(o = emptyList())).isFalse()
        assertThat(accept(o = listOf(origin, origin))).isFalse()
        assertThat(accept(m = emptyList())).isFalse()
        assertThat(accept(m = listOf("true"))).isFalse()
        assertThat(accept(ip = "1.1.1.1")).isFalse()
        assertThat(accept(ip = "127.0.0.1")).isFalse()
    }

    @Test fun burstAndPeerCapacityBoundedWithClockRollbackFailClosed() {
        var time = 1_000L
        val gate = LocalRequestLimiter(monotonicMillis = { time }, maxRequests = 2, maxPeers = 2)
        assertThat(gate.permit("192.168.1.2")).isTrue()
        assertThat(gate.permit("192.168.1.2")).isTrue()
        assertThat(gate.permit("192.168.1.2")).isFalse()
        assertThat(gate.permit("10.1.1.1")).isTrue()
        assertThat(gate.permit("172.16.1.1")).isFalse()
        time += 60_000
        assertThat(gate.permit("192.168.1.2")).isTrue()
        time -= 1
        assertThat(gate.permit("192.168.1.2")).isFalse()
        assertThat(gate.permit("192.168.1.2")).isTrue()
        assertThat(gate.permit("8.8.8.8")).isFalse()
        gate.clear()
        assertThat(gate.permit("172.16.1.1")).isTrue()
    }
}
