package app.muxtv.catalog.refresh

import app.muxtv.credentials.CredentialId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceAccessReferenceContractTest {
    private val credentialId = CredentialId.parse("11111111-1111-1111-1111-111111111111")

    @Test
    fun typedReferencesRoundTripProviderKindAndCredentialId() {
        val m3u = SourceAccessReference.m3u(credentialId)
        val xtream = SourceAccessReference.xtream(credentialId)

        assertThat(SourceAccessReference.parse(m3u.value).kind).isEqualTo(SourceAccessKind.M3U)
        assertThat(SourceAccessReference.parse(m3u.value).credentialId).isEqualTo(credentialId)
        assertThat(SourceAccessReference.parse(xtream.value).kind).isEqualTo(SourceAccessKind.XTREAM)
        assertThat(SourceAccessReference.parse(xtream.value).credentialId).isEqualTo(credentialId)
    }

    @Test
    fun bareLegacyUuidRemainsAnM3uReference() {
        val parsed = SourceAccessReference.parse(credentialId.value)

        assertThat(parsed.kind).isEqualTo(SourceAccessKind.M3U)
        assertThat(parsed.credentialId).isEqualTo(credentialId)
        assertThat(parsed.value).isEqualTo(credentialId.value)
    }

    @Test
    fun newTypedReferencesUseVersionedOpaqueEncoding() {
        val m3u = SourceAccessReference.m3u(credentialId)
        val xtream = SourceAccessReference.xtream(credentialId)

        assertThat(m3u.value).isEqualTo("muxtv-access:v1:m3u:${credentialId.value}")
        assertThat(xtream.value).isEqualTo("muxtv-access:v1:xtream:${credentialId.value}")
        assertThat(m3u.toString()).isEqualTo(
            "SourceAccessReference(kind=M3U, credentialId=<redacted>)",
        )
        assertThat(xtream.toString()).isEqualTo(
            "SourceAccessReference(kind=XTREAM, credentialId=<redacted>)",
        )
        assertThat(m3u.toString()).doesNotContain(credentialId.value)
        assertThat(xtream.toString()).doesNotContain(credentialId.value)
    }

    @Test
    fun malformedOrUnknownTypedReferenceIsRejectedInsteadOfFallingBackToM3u() {
        val malformed = listOf(
            "muxtv-access:v1:xtream:not-a-uuid",
            "muxtv-access:v1:unknown:${credentialId.value}",
            "muxtv-access:v2:m3u:${credentialId.value}",
            "muxtv-access:v1:m3u:${credentialId.value}:extra",
        )

        malformed.forEach { value ->
            val failure = runCatching { SourceAccessReference.parse(value) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
