package app.muxtv.external

enum class LocalNetworkClassification {
    LOCAL,
    LOOPBACK,
    REMOTE,
    AMBIGUOUS,
}

/**
 * Classifies a URL host without performing DNS resolution.
 *
 * Only literal addresses and local hostname suffixes are decided. Hostname resolution is
 * deliberately out of scope: a permission prompt must never require a speculative network
 * lookup. Ambiguous hosts remain [LocalNetworkClassification.AMBIGUOUS] until the platform
 * signals a local-network denial during an actual connection.
 */
object LocalNetworkTargetClassifier {
    private const val LOCAL_HOST_SUFFIX = ".local"
    private const val LOCALHOST = "localhost"
    private const val IPV4_LOOPBACK_PREFIX = 127L shl 24
    private const val IPV4_LINK_LOCAL_PREFIX = (169L shl 24) or (254L shl 16)
    private const val IPV4_PRIVATE_PREFIX_10 = 10L shl 24
    private const val IPV4_PRIVATE_PREFIX_172_16 = (172L shl 24) or (16L shl 16)
    private const val IPV4_PRIVATE_PREFIX_192_168 = (192L shl 24) or (168L shl 16)
    private const val IPV4_CGNAT_PREFIX_100_64 = (100L shl 24) or (64L shl 16)
    private const val IPV4_MULTICAST_PREFIX = 224L shl 24
    private const val IPV4_LIMITED_BROADCAST = 0xFFFF_FFFFL

    fun classify(host: String): LocalNetworkClassification {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty()) return LocalNetworkClassification.AMBIGUOUS
        if (normalized.contains(':')) return classifyIpv6(normalized)
        if (normalized.contains('.')) {
            val ipv4 = parseIpv4(normalized)
            if (ipv4 != null) return classifyIpv4(ipv4)
        }
        return classifyHostname(normalized)
    }

    private fun classifyHostname(host: String): LocalNetworkClassification = when {
        host == LOCALHOST || host.endsWith(".$LOCALHOST") ->
            LocalNetworkClassification.LOOPBACK
        host.endsWith(LOCAL_HOST_SUFFIX) && host.length > LOCAL_HOST_SUFFIX.length ->
            LocalNetworkClassification.LOCAL
        else -> LocalNetworkClassification.AMBIGUOUS
    }

    private fun classifyIpv4(address: Long): LocalNetworkClassification = when {
        address and 0xFF00_0000L == IPV4_LOOPBACK_PREFIX ->
            LocalNetworkClassification.LOOPBACK
        address and 0xFF00_0000L == IPV4_PRIVATE_PREFIX_10 ||
            address and 0xFFF0_0000L == IPV4_PRIVATE_PREFIX_172_16 ||
            address and 0xFFFF_0000L == IPV4_PRIVATE_PREFIX_192_168 ||
            address and 0xFFC0_0000L == IPV4_CGNAT_PREFIX_100_64 ||
            address and 0xFFFF_0000L == IPV4_LINK_LOCAL_PREFIX ||
            address and 0xF000_0000L == IPV4_MULTICAST_PREFIX ||
            address == IPV4_LIMITED_BROADCAST -> LocalNetworkClassification.LOCAL
        else -> LocalNetworkClassification.REMOTE
    }

    private fun classifyIpv6(host: String): LocalNetworkClassification {
        val literal = host.removePrefix("[").removeSuffix("]")
        val address = parseIpv6(literal) ?: return LocalNetworkClassification.AMBIGUOUS
        val ipv4 = ipv4MappedAddress(address)
        if (ipv4 != null) return classifyIpv4(ipv4)
        return when {
            address.contentEquals(IPV6_LOOPBACK) -> LocalNetworkClassification.LOOPBACK
            address[0] == 0xFE.toByte() && (address[1].toInt() and 0xC0) == 0x80 ->
                LocalNetworkClassification.LOCAL
            (address[0].toInt() and 0xFE) == 0xFC -> LocalNetworkClassification.LOCAL
            else -> LocalNetworkClassification.REMOTE
        }
    }

    private fun parseIpv4(host: String): Long? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        var result = 0L
        for (part in parts) {
            if (part.isEmpty() || part.length > 3) return null
            if (part.length > 1 && part.startsWith('0')) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            result = result * 256 + value
        }
        return result
    }

    private fun parseIpv6(host: String): ByteArray? {
        if (host.isEmpty() || host.contains('[') || host.contains(']')) return null
        val doubleColon = host.indexOf("::")
        val headGroups: List<String>
        val tailGroups: List<String>
        if (doubleColon >= 0) {
            if (host.indexOf("::", doubleColon + 2) >= 0) return null
            headGroups = host.substring(0, doubleColon).let {
                if (it.isEmpty()) emptyList() else it.split(':')
            }
            tailGroups = host.substring(doubleColon + 2).let {
                if (it.isEmpty()) emptyList() else it.split(':')
            }
            if (headGroups.size + tailGroups.size > 7) return null
        } else {
            headGroups = host.split(':')
            tailGroups = emptyList()
        }
        val groups = headGroups + tailGroups
        val lastGroup = groups.lastOrNull()
        if (lastGroup?.contains('.') == true) {
            if (lastGroup != groups.last()) return null
            val ipv4 = parseIpv4(lastGroup) ?: return null
            val groupCount = groups.size - 1
            val slotCount = groupCount + 2
            if (slotCount > 8) return null
            if (doubleColon < 0 && slotCount != 8) return null
            val bytes = ByteArray(16)
            var index = 0
            for (group in headGroups) {
                val groupBytes = parseHexGroup(group) ?: return null
                bytes[index++] = groupBytes[0]
                bytes[index++] = groupBytes[1]
            }
            if (doubleColon >= 0) index += 16 - slotCount * 2
            for (group in tailGroups.dropLast(1)) {
                val groupBytes = parseHexGroup(group) ?: return null
                bytes[index++] = groupBytes[0]
                bytes[index++] = groupBytes[1]
            }
            val ipv4Bytes = longToBytes(ipv4)
            for (byte in ipv4Bytes) bytes[index++] = byte
            return bytes
        }
        if (doubleColon < 0 && groups.size != 8) return null
        if (doubleColon >= 0 && groups.size >= 8) return null
        val bytes = ByteArray(16)
        var index = 0
        for (group in headGroups) {
            val groupBytes = parseHexGroup(group) ?: return null
            bytes[index++] = groupBytes[0]
            bytes[index++] = groupBytes[1]
        }
        if (doubleColon >= 0) index += 16 - groups.size * 2
        for (group in tailGroups) {
            val groupBytes = parseHexGroup(group) ?: return null
            bytes[index++] = groupBytes[0]
            bytes[index++] = groupBytes[1]
        }
        return bytes
    }

    private fun parseHexGroup(group: String): ByteArray? {
        if (group.isEmpty() || group.length > 4) return null
        val value = group.toIntOrNull(16) ?: return null
        return byteArrayOf((value shr 8).toByte(), value.toByte())
    }

    private fun ipv4MappedAddress(address: ByteArray): Long? {
        if (address.size != 16) return null
        for (index in 0 until 10) {
            if (address[index] != 0.toByte()) return null
        }
        if (address[10] != 0xFF.toByte() || address[11] != 0xFF.toByte()) return null
        return bytesToLong(address, 12)
    }

    private fun longToBytes(value: Long): ByteArray = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte(),
    )

    private fun bytesToLong(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) shl 24 or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private val IPV6_LOOPBACK = byteArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
    )
}
