package app.muxtv.phone

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** No DNS, HTTP URL, public, loopback, address-with-port or noncanonical IPv4. */
class PhoneNativeEndpointTest {
    @Test fun acceptsOnlyCanonicalRfc1918AndValidTcpPort() {
        for (ip in listOf("10.0.2.2", "172.16.0.1", "172.31.255.254", "192.168.1.2")) {
            val endpoint = PhoneNativeEndpoint.parseOrNull(ip, "41234")
            assertThat(endpoint?.privateIpv4).isEqualTo(ip)
            assertThat(endpoint?.port).isEqualTo(41234)
        }
        assertThat(PhoneNativeEndpoint.parseOrNull("192.168.1.4", "1")?.port).isEqualTo(1)
        assertThat(PhoneNativeEndpoint.parseOrNull("192.168.1.4", "65535")?.port).isEqualTo(65535)
    }

    @Test fun rejectsHostnamesUrlsPublicVpnAddressesMalformedAndPortInjection() {
        for (bad in listOf(
            "localhost", "127.0.0.1", "8.8.8.8", "169.254.3.1", "0.0.0.0",
            "10.0.0.1:4000", "http://10.0.0.1", "192.168.01.2",
            "192.168.1.255 ", "192.168.1.256", "172.15.0.1", "172.32.0.1",
            "::1", "", "10.0.0.1\n", "10.0.0.1.evil.example",
        )) assertThat(PhoneNativeEndpoint.parseOrNull(bad, "4000")).isNull()
        for (port in listOf("", "0", "00001", "65536", "-1", "1 ", "1/2",
            "99999999999", "443\n", "80:80", "http", "０８０",
        )) assertThat(PhoneNativeEndpoint.parseOrNull("10.0.0.1", port)).isNull()
    }

    @Test fun endpointToStringNeverDisclosesAddress() {
        val endpoint = checkNotNull(PhoneNativeEndpoint.parseOrNull("192.168.1.19", "48900"))
        assertThat(endpoint.toString()).doesNotContain("192.168.1.19")
        assertThat(endpoint.toString()).doesNotContain("48900")
    }
}
