package app.muxtv.localcontrol.server

import app.muxtv.localcontrol.PairingAuthority
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class LanTransportTest {
    private val endpoint = LanEndpoint("192.168.10.5", 45678)

    private fun io.ktor.client.request.HttpRequestBuilder.sameOrigin() {
        header(HttpHeaders.Host, endpoint.authority)
        header(HttpHeaders.Origin, endpoint.origin)
        header("X-MuxTV-Client", "1")
    }

    @Test fun unrelatedEndpointIsNotExposedOrImplemented() = testApplication {
        val authority = PairingAuthority()
        application {
            installLocalControlTransport(
                authority,
                activeEndpoint = { endpoint },
                remotePeer = { "192.168.10.7" },
            )
        }
        val response = client.get("/v1/catalog/channels") { sameOrigin() }
        assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
    }

    @Test fun claimTvApprovalAndRedemptionRequireOneTimeProof() = testApplication {
        val authority = PairingAuthority()
        application {
            installLocalControlTransport(
                authority,
                activeEndpoint = { endpoint },
                remotePeer = { "192.168.10.7" },
            )
        }
        val proof = authority.begin().secret
        val claim = client.post("/v1/pair/claim") {
            sameOrigin()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"proof":"$proof","clientLabel":"Phone"}""")
        }
        assertThat(claim.status).isEqualTo(HttpStatusCode.Accepted)
        assertThat(claim.headers[HttpHeaders.CacheControl]).contains("no-store")
        assertThat(claim.bodyAsText()).doesNotContain(proof)
        val claimId = Json.parseToJsonElement(claim.bodyAsText()).jsonObject
            .getValue("claimId").jsonPrimitive.content
        assertThat(authority.pendingClaimForTv()?.claimId).isEqualTo(claimId)

        val tooEarly = client.post("/v1/pair/redeem") {
            sameOrigin()
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"claimId":"$claimId","proof":"$proof"}""")
        }
        assertThat(tooEarly.status).isEqualTo(HttpStatusCode.Forbidden)
        assertThat(authority.approve(claimId)).isTrue()

        val success = client.post("/v1/pair/redeem") {
            sameOrigin()
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"claimId":"$claimId","proof":"$proof"}""")
        }
        assertThat(success.status).isEqualTo(HttpStatusCode.OK)
        val parsed = Json.parseToJsonElement(success.bodyAsText()).jsonObject
        val sessionId = parsed.getValue("sessionId").jsonPrimitive.content
        val credential = parsed.getValue("credential").jsonPrimitive.content
        assertThat(credential).hasLength(64)

        val status = client.post("/v1/session/check") {
            sameOrigin()
            header("X-MuxTV-Session-Id", sessionId)
            header(HttpHeaders.Authorization, "Bearer $credential")
        }
        assertThat(status.status).isEqualTo(HttpStatusCode.OK)
        assertThat(status.bodyAsText()).isEqualTo("""{"paired":true}""")

        val replay = client.post("/v1/pair/redeem") {
            sameOrigin()
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"claimId":"$claimId","proof":"$proof"}""")
        }
        assertThat(replay.status).isEqualTo(HttpStatusCode.Forbidden)
        authority.revoke(sessionId)
        val afterRevoke = client.post("/v1/session/check") {
            sameOrigin()
            header("X-MuxTV-Session-Id", sessionId)
            header(HttpHeaders.Authorization, "Bearer $credential")
        }
        assertThat(afterRevoke.status).isEqualTo(HttpStatusCode.Forbidden)
    }

    @Test fun missingTvActivationRejectsRequestsWithoutProcessingProof() = testApplication {
        val authority = PairingAuthority()
        application {
            installLocalControlTransport(
                authority,
                activeEndpoint = { null },
                remotePeer = { "192.168.10.7" },
            )
        }
        val response = client.post("/v1/pair/claim") {
            sameOrigin()
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"proof":"deadbeef","clientLabel":"Phone"}""")
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
    }

    @Test fun forgedHostOriginMissingMarkerAndPublicPeerRejected() = testApplication {
        val authority = PairingAuthority()
        application {
            installLocalControlTransport(
                authority,
                activeEndpoint = { endpoint },
                remotePeer = { "8.8.8.8" },
            )
        }
        val response = client.post("/v1/session/check") { sameOrigin() }
        assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
    }

    @Test fun oversizedAndExtraFieldPayloadRejected() = testApplication {
        val authority = PairingAuthority()
        application {
            installLocalControlTransport(
                authority,
                activeEndpoint = { endpoint },
                remotePeer = { "192.168.10.7" },
            )
        }
        val proof = authority.begin().secret
        val oversized = client.post("/v1/pair/claim") {
            sameOrigin()
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"proof":"$proof","clientLabel":"${"x".repeat(2000)}"}""")
        }
        assertThat(oversized.status).isEqualTo(HttpStatusCode.BadRequest)

        val extra = client.post("/v1/pair/claim") {
            sameOrigin()
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"proof":"$proof","clientLabel":"Phone","admin":true}""")
        }
        assertThat(extra.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(authority.pendingClaimForTv()).isNull()
    }
}
