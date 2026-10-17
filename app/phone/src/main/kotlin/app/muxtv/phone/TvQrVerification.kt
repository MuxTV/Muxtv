package app.muxtv.phone

import app.muxtv.localcontrol.cryptolab.LabTvQrKeyBinding

/**
 * Offline QR verifier. Parsing a QR does not prove ownership of a TV or grant pairing.
 * Never consume pairing URLs, send requests, open a browser, or accept provider secrets.
 */
internal object TvQrVerification {
    sealed interface Result {
        data class PublicKeyPreview(val fingerprint: String, val invitationId: String) : Result
        data object Rejected : Result
    }

    fun verify(value: String): Result {
        if (value.length !in 1..900 || !value.startsWith("MXLC04:1:")) return Result.Rejected
        val key = try {
            LabTvQrKeyBinding.parse(value)
        } catch (_: IllegalArgumentException) {
            return Result.Rejected
        } catch (_: java.security.GeneralSecurityException) {
            return Result.Rejected
        }
        return Result.PublicKeyPreview(key.keyFingerprint, key.binding.tvInvitationId)
    }

    fun formattedFingerprint(fingerprint: String): String {
        require(fingerprint.length == 64 && fingerprint.all { it in '0'..'9' || it in 'a'..'f' })
        return fingerprint.chunked(8).joinToString("\n")
    }
}
