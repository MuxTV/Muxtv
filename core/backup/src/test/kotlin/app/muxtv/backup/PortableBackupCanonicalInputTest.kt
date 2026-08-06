package app.muxtv.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PortableBackupCanonicalInputTest {
    @Test
    fun `duplicate json key is rejected even when parsed value and digest would match`() {
        val snapshot = PortableBackupSnapshot(
            createdAtEpochMillis = 1L,
            dataSchemaVersion = 10,
            payload = PortableBackupPayload(
                profiles = emptyList(),
                sources = emptyList(),
                channelOverlays = emptyList(),
                recentChannels = emptyList(),
            ),
        )
        val canonical = PortableBackupCodec.encode(snapshot).toString(Charsets.UTF_8)
        val ambiguous = canonical.replace(
            "\"dataSchemaVersion\":10",
            "\"dataSchemaVersion\":10,\"dataSchemaVersion\":10",
        ).toByteArray(Charsets.UTF_8)

        val decoded = PortableBackupCodec.decode(ambiguous)

        assertThat(decoded).isInstanceOf(PortableBackupDecodeResult.Rejected::class.java)
        assertThat((decoded as PortableBackupDecodeResult.Rejected).reason)
            .isEqualTo(PortableBackupRejectReason.MALFORMED)
    }

    @Test
    fun `non-canonical whitespace is rejected instead of creating parser ambiguity`() {
        val snapshot = PortableBackupSnapshot(
            createdAtEpochMillis = 1L,
            dataSchemaVersion = 10,
            payload = PortableBackupPayload(
                profiles = emptyList(),
                sources = emptyList(),
                channelOverlays = emptyList(),
                recentChannels = emptyList(),
            ),
        )
        val canonical = PortableBackupCodec.encode(snapshot).toString(Charsets.UTF_8)
        val reformatted = canonical.replace("{", "{ ", ignoreCase = false)
            .toByteArray(Charsets.UTF_8)

        val decoded = PortableBackupCodec.decode(reformatted)

        assertThat(decoded).isInstanceOf(PortableBackupDecodeResult.Rejected::class.java)
        assertThat((decoded as PortableBackupDecodeResult.Rejected).reason)
            .isEqualTo(PortableBackupRejectReason.MALFORMED)
    }
}
