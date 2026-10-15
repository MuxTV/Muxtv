package app.muxtv.localcontrol.server

import app.muxtv.localcontrol.PairingAuthority
import app.muxtv.localcontrol.PairingCapability
import app.muxtv.localcontrol.PairingClaim
import app.muxtv.localcontrol.PairingRedemption
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

private const val MAX_PAIRING_BODY_BYTES = 1_024
private const val CLIENT_MARKER = "X-MuxTV-Client"
private const val SESSION_ID = "X-MuxTV-Session-Id"
private val strictJson = Json { ignoreUnknownKeys = false; isLenient = false }

/** Browser input. Do not log/toString the one-time proof. */
@Serializable
private class ClaimInput(val proof: String, val clientLabel: String) {
    override fun toString(): String = "ClaimInput([REDACTED])"
}

@Serializable
private class RedeemInput(val claimId: String, val proof: String) {
    override fun toString(): String = "RedeemInput([REDACTED])"
}

@Serializable
private data class ClaimResponse(val claimId: String, val clientLabel: String)

@Serializable
private class RedeemResponse(val sessionId: String, val credential: String) {
    override fun toString(): String = "RedeemResponse([REDACTED])"
}

/**
 * Internal transport routes, not a standalone public application.
 *
 * The Android TV host must explicitly call start() and select an active private LAN interface.
 * No provider credentials, catalog/Room or player routes exist in this LC02 slice.
 * A same-origin browser may make calls only after TV has opened the pairing window.
 */
internal fun Application.installLocalControlTransport(
    authority: PairingAuthority,
    activeEndpoint: () -> LanEndpoint?,
    remotePeer: (ApplicationCall) -> String = { call -> call.request.local.remoteHost },
    rateLimiter: LocalRequestLimiter = LocalRequestLimiter(),
) {
    routing {
        post("/v1/pair/claim") {
            if (!call.checkLanRequest(activeEndpoint, remotePeer, rateLimiter)) return@post
            val input = call.boundedJson<ClaimInput>() ?: return@post call.respond(HttpStatusCode.BadRequest)
            when (val result = authority.claim(input.proof, input.clientLabel)) {
                is PairingClaim.AwaitingTvConfirmation -> call.respondText(
                    strictJson.encodeToString(ClaimResponse(result.claimId, result.clientLabel)),
                    ContentType.Application.Json,
                    HttpStatusCode.Accepted,
                )
                is PairingClaim.Denied -> call.respond(HttpStatusCode.Forbidden)
            }
        }

        post("/v1/pair/redeem") {
            if (!call.checkLanRequest(activeEndpoint, remotePeer, rateLimiter)) return@post
            val input = call.boundedJson<RedeemInput>() ?: return@post call.respond(HttpStatusCode.BadRequest)
            when (val result = authority.redeem(input.claimId, input.proof)) {
                is PairingRedemption.Granted -> call.respondText(
                    strictJson.encodeToString(RedeemResponse(result.grant.sessionId, result.grant.credential)),
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
                is PairingRedemption.Denied -> call.respond(HttpStatusCode.Forbidden)
            }
        }

        /** Authenticated POST-only proof-of-session endpoint; browser requests carry Origin. */
        post("/v1/session/check") {
            if (!call.checkLanRequest(activeEndpoint, remotePeer, rateLimiter)) return@post
            val ids = call.request.headers.getAll(SESSION_ID).orEmpty()
            val auths = call.request.headers.getAll(HttpHeaders.Authorization).orEmpty()
            val bearer = auths.singleOrNull()
            if (ids.size != 1 || bearer == null || !bearer.startsWith("Bearer ") ||
                !authority.authorize(
                    ids.single(),
                    bearer.removePrefix("Bearer "),
                    PairingCapability.SOURCE_TEST,
                )
            ) {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }
            call.respondText("{\"paired\":true}", ContentType.Application.Json)
        }
    }
    installPairingBootstrapPage(activeEndpoint, remotePeer, rateLimiter)
}

private suspend fun ApplicationCall.checkLanRequest(
    endpoint: () -> LanEndpoint?,
    remotePeer: (ApplicationCall) -> String,
    limiter: LocalRequestLimiter,
): Boolean {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append("Pragma", "no-cache")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("X-Frame-Options", "DENY")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
    val active = endpoint()
    if (active == null) {
        respond(HttpStatusCode.ServiceUnavailable)
        return false
    }
    val peer = remotePeer(this)
    val accepted = LanRequestPolicy(active).accepts(
        request.headers.getAll(HttpHeaders.Host).orEmpty(),
        request.headers.getAll(HttpHeaders.Origin).orEmpty(),
        request.headers.getAll(CLIENT_MARKER).orEmpty(),
        peer,
    )
    if (!accepted) {
        respond(HttpStatusCode.Forbidden)
        return false
    }
    if (!limiter.permit(peer)) {
        respond(HttpStatusCode.TooManyRequests)
        return false
    }
    return true
}

private suspend inline fun <reified T> ApplicationCall.boundedJson(): T? {
    if (!request.contentType().match(ContentType.Application.Json)) return null
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && (declared < 0 || declared > MAX_PAIRING_BODY_BYTES)) return null

    // Network-slow clients must not hold a pairing handler indefinitely.
    val payload = withTimeoutOrNull(5_000L) {
        val channel = receiveChannel()
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read < 0) break
            if (bytes.size() + read > MAX_PAIRING_BODY_BYTES) return@withTimeoutOrNull null
            if (read > 0) bytes.write(buffer, 0, read)
        }
        bytes.toByteArray()
    } ?: return null
    return try {
        strictJson.decodeFromString<T>(payload.toString(Charsets.UTF_8))
    } catch (_: IllegalArgumentException) {
        null // Validation diagnostics must never echo token/body content.
    }
}

/**
 * This is a dormant server until a TV user gesture explicitly calls start().
 * Android lifecycle/network-change owner must invoke stop(); both erase all grants.
 */
class TvLanServer(private val pairing: PairingAuthority) {
    private val lifecycleMutex = Mutex()
    private var shutdown: (() -> Unit)? = null

    suspend fun start(privateInterfaceAddress: String): LanEndpoint = lifecycleMutex.withLock {
        check(shutdown == null) { "Local control is already active." }
        require(LanAddressPolicy.isPrivateLiteral(privateInterfaceAddress))
        val endpoint = AtomicReference<LanEndpoint?>(null)
        val limiter = LocalRequestLimiter()
        val engine = embeddedServer(CIO, port = 0, host = privateInterfaceAddress) {
            installLocalControlTransport(
                authority = pairing,
                activeEndpoint = { endpoint.get() },
                rateLimiter = limiter,
            )
        }
        try {
            engine.start(wait = false)
            val connector = engine.engine.resolvedConnectors().single()
            val active = LanEndpoint(privateInterfaceAddress, connector.port)
            endpoint.set(active)
            shutdown = {
                endpoint.set(null)
                pairing.stop()
                limiter.clear()
                engine.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
            }
            active
        } catch (e: Exception) {
            endpoint.set(null)
            pairing.stop()
            engine.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
            throw e
        }
    }

    suspend fun stop() = lifecycleMutex.withLock {
        shutdown?.invoke()
        shutdown = null
        pairing.stop()
    }
}
