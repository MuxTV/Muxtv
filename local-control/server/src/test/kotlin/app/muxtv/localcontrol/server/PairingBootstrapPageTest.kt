package app.muxtv.localcontrol.server

import app.muxtv.localcontrol.PairingAuthority
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Test

class PairingBootstrapPageTest {
    private val endpoint = LanEndpoint("192.168.1.27", 45678)

    @Test
    fun qrLandingPageAndScriptAreLocallyServedWithoutPairingSecret() = testApplication {
        val authority = PairingAuthority()
        application {
            installLocalControlTransport(
                authority = authority,
                activeEndpoint = { endpoint },
                remotePeer = { "192.168.1.5" },
            )
        }
        val invitation = authority.begin()
        val page = client.get("/pair") {
            header(HttpHeaders.Host, endpoint.authority)
        }
        assertThat(page.status).isEqualTo(HttpStatusCode.OK)
        assertThat(page.headers[HttpHeaders.CacheControl]).contains("no-store")
        assertThat(page.headers["Content-Security-Policy"]).contains("script-src 'self'")
        assertThat(page.bodyAsText()).contains("src=\"/pair.js\"")
        assertThat(page.bodyAsText()).doesNotContain(invitation.secret)

        val js = client.get("/pair.js") {
            header(HttpHeaders.Host, endpoint.authority)
        }
        assertThat(js.status).isEqualTo(HttpStatusCode.OK)
        assertThat(js.bodyAsText()).contains("window.location.hash")
        assertThat(js.bodyAsText()).contains("window.history.replaceState")
        assertThat(js.bodyAsText()).doesNotContain(invitation.secret)
        assertThat(js.bodyAsText()).doesNotContain("localStorage")
        assertThat(js.bodyAsText()).doesNotContain("Authorization")
    }

    @Test
    fun hostileHostRejectedOnUnauthenticatedStaticRoute() = testApplication {
        application {
            installLocalControlTransport(
                authority = PairingAuthority(),
                activeEndpoint = { endpoint },
                remotePeer = { "192.168.1.5" },
            )
        }
        val response = client.get("/pair") {
            header(HttpHeaders.Host, "attacker.example")
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
    }

    @Test
    fun pairingLandingPageUnavailableOutsideTvWindow() = testApplication {
        application {
            installLocalControlTransport(
                authority = PairingAuthority(),
                activeEndpoint = { null },
                remotePeer = { "192.168.1.5" },
            )
        }
        val response = client.get("/pair") {
            header(HttpHeaders.Host, endpoint.authority)
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
    }

    @Test
    fun publicPeerCannotLoadPairingPage() = testApplication {
        application {
            installLocalControlTransport(
                authority = PairingAuthority(),
                activeEndpoint = { endpoint },
                remotePeer = { "8.8.8.8" },
            )
        }
        val response = client.get("/pair") {
            header(HttpHeaders.Host, endpoint.authority)
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
    }
}
