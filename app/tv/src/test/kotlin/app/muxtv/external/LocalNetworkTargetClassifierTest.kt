package app.muxtv.external

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalNetworkTargetClassifierTest {
    @Test
    fun `rfc1918 ipv4 literals are local`() {
        for (host in listOf(
            "192.168.1.10",
            "10.0.0.1",
            "172.16.0.1",
            "172.31.255.254",
        )) {
            assertThat(LocalNetworkTargetClassifier.classify(host))
                .isEqualTo(LocalNetworkClassification.LOCAL)
        }
    }

    @Test
    fun `link local ipv4 and ipv6 literals are local`() {
        assertThat(LocalNetworkTargetClassifier.classify("169.254.10.5"))
            .isEqualTo(LocalNetworkClassification.LOCAL)
        assertThat(LocalNetworkTargetClassifier.classify("fe80::1"))
            .isEqualTo(LocalNetworkClassification.LOCAL)
        assertThat(LocalNetworkTargetClassifier.classify("[FE80::AABB:CCFF:FEDD:EEFF]"))
            .isEqualTo(LocalNetworkClassification.LOCAL)
    }

    @Test
    fun `private ulas are local`() {
        assertThat(LocalNetworkTargetClassifier.classify("fc00::5"))
            .isEqualTo(LocalNetworkClassification.LOCAL)
        assertThat(LocalNetworkTargetClassifier.classify("fd12:3456:789a::1"))
            .isEqualTo(LocalNetworkClassification.LOCAL)
    }

    @Test
    fun `local hostname suffix is local`() {
        assertThat(LocalNetworkTargetClassifier.classify("nas.local"))
            .isEqualTo(LocalNetworkClassification.LOCAL)
        assertThat(LocalNetworkTargetClassifier.classify("torrserver.local"))
            .isEqualTo(LocalNetworkClassification.LOCAL)
    }

    @Test
    fun `loopback forms are loopback`() {
        for (host in listOf("127.0.0.1", "127.8.8.8", "localhost", "sub.localhost", "::1")) {
            assertThat(LocalNetworkTargetClassifier.classify(host))
                .isEqualTo(LocalNetworkClassification.LOOPBACK)
        }
    }

    @Test
    fun `public addresses are remote`() {
        for (host in listOf("8.8.8.8", "93.184.216.34", "2001:4860:4860::8888", "2606:2800:220:1::1")) {
            assertThat(LocalNetworkTargetClassifier.classify(host))
                .isEqualTo(LocalNetworkClassification.REMOTE)
        }
    }

    @Test
    fun `plain hostnames stay ambiguous without dns`() {
        for (host in listOf("example.com", "media.example.org", "myserver")) {
            assertThat(LocalNetworkTargetClassifier.classify(host))
                .isEqualTo(LocalNetworkClassification.AMBIGUOUS)
        }
    }

    @Test
    fun `malformed addresses are ambiguous`() {
        for (host in listOf("192.168.1", "192.168.1.999", "01.2.3.4", "host:port", "fe80:::1")) {
            assertThat(LocalNetworkTargetClassifier.classify(host))
                .isEqualTo(LocalNetworkClassification.AMBIGUOUS)
        }
    }

    @Test
    fun `classification is case and whitespace insensitive`() {
        assertThat(LocalNetworkTargetClassifier.classify("  192.168.1.5  "))
            .isEqualTo(LocalNetworkClassification.LOCAL)
        assertThat(LocalNetworkTargetClassifier.classify("NAS.LOCAL"))
            .isEqualTo(LocalNetworkClassification.LOCAL)
    }

    @Test
    fun `ipv4 mapped ipv6 uses the embedded address`() {
        assertThat(LocalNetworkTargetClassifier.classify("::ffff:192.168.1.7"))
            .isEqualTo(LocalNetworkClassification.LOCAL)
        assertThat(LocalNetworkTargetClassifier.classify("::ffff:8.8.8.8"))
            .isEqualTo(LocalNetworkClassification.REMOTE)
    }
}
