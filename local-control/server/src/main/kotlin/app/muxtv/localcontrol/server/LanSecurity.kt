package app.muxtv.localcontrol.server

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * LAN admission is numeric-only: DNS and spoofable proxy headers cannot establish local trust.
 * Network ownership/permission and interface selection remain the Android composition root's job.
 */
object LanAddressPolicy {
    fun isPrivateLiteral(address: String): Boolean {
        if (address.isBlank() || address != address.trim() || '%' in address) return false
        if ('.' in address && ':' !in address) {
            val octets = address.split('.')
            if (octets.size != 4) return false
            val numbers = octets.map {
                if (it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } ||
                    (it.length > 1 && it[0] == '0')
                ) return false
                it.toIntOrNull()?.takeIf { number -> number in 0..255 } ?: return false
            }
            val first = numbers[0]
            val second = numbers[1]
            return first == 10 ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168)
        }
        if (':' !in address || address.any {
            it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' && it != ':'
        }) return false
        val inet = runCatching { InetAddress.getByName(address) }.getOrNull()
        if (inet is Inet4Address || inet !is Inet6Address) return false
        val firstByte = inet.address[0].toInt() and 0xff
        return firstByte == 0xfc || firstByte == 0xfd
    }
}

class LanEndpoint(address: String, val port: Int) {
    val address: String = address.also { require(LanAddressPolicy.isPrivateLiteral(it)) }
    init { require(port in 1..65535) }
    val authority: String = if (':' in address) "[$address]:$port" else "$address:$port"
    val origin: String = "http://$authority"

    override fun toString(): String = "LanEndpoint(privateAddress=[REDACTED], port=$port)"
}

/** All HTTP routes must pass the same check, not merely the pairing route. */
class LanRequestPolicy(private val endpoint: LanEndpoint) {
    fun accepts(
        hosts: List<String>,
        origins: List<String>,
        markers: List<String>,
        remoteAddress: String,
    ): Boolean =
        hosts.size == 1 && hosts[0] == endpoint.authority &&
            origins.size == 1 && origins[0] == endpoint.origin &&
            markers.size == 1 && markers[0] == "1" &&
            LanAddressPolicy.isPrivateLiteral(remoteAddress)
}

/** Small fail-closed per-IP limit for the pairing and session HTTP endpoints. */
class LocalRequestLimiter(
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxRequests: Int = 30,
    private val windowMillis: Long = 60_000,
    private val maxPeers: Int = 32,
) {
    private data class Window(val started: Long, var count: Int)
    private val windows = mutableMapOf<String, Window>()

    init {
        require(maxRequests in 1..1_000)
        require(windowMillis in 1_000..600_000)
        require(maxPeers in 1..256)
    }

    @Synchronized
    fun permit(peer: String): Boolean {
        if (!LanAddressPolicy.isPrivateLiteral(peer)) return false
        val now = monotonicMillis()
        val itr = windows.iterator()
        while (itr.hasNext()) {
            val w = itr.next().value
            if (now < w.started || now - w.started >= windowMillis) itr.remove()
        }
        val window = windows[peer]
        if (window == null) {
            if (windows.size >= maxPeers) return false
            windows[peer] = Window(now, 1)
            return true
        }
        if (window.count >= maxRequests) return false
        window.count++
        return true
    }

    @Synchronized
    fun clear() = windows.clear()
}
