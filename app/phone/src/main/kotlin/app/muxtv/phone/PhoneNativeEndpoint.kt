package app.muxtv.phone

import app.muxtv.localcontrol.nativetransport.Lc04PrivateIpv4

/**
 * TV displays a numeric LAN locator separately from its QR-pinned key.
 * Neither address nor port can authenticate the TV or carry source rights.
 */
internal data class PhoneNativeEndpoint(val privateIpv4: String, val port: Int) {
    override fun toString(): String = "PhoneNativeEndpoint([PRIVATE_ADDRESS_REDACTED])"

    companion object {
        fun parseOrNull(address: String, portText: String): PhoneNativeEndpoint? {
            if (address.length !in 7..15 ||
                Lc04PrivateIpv4.parse(address) == null ||
                portText.length !in 1..5 ||
                portText.any { it !in '0'..'9' } ||
                (portText.length > 1 && portText.startsWith('0'))
            ) return null
            val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return PhoneNativeEndpoint(address, port)
        }
    }
}
