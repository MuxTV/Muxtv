package app.muxtv.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PortableBackupStrictJsonTest {
    @Test
    fun `quoted numeric primitive is malformed instead of coerced`() {
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
        val encoded = PortableBackupCodec.encode(snapshot).toString(Charsets.UTF_8)
        val quotedNumber = encoded.replace(
            "\"dataSchemaVersion\":10",
            "\"dataSchemaVersion\":\"10\"",
        ).toByteArray(Charsets.UTF_8)

        val decoded = PortableBackupCodec.decode(quotedNumber)

        assertThat(decoded).isInstanceOf(PortableBackupDecodeResult.Rejected::class.java)
        assertThat((decoded as PortableBackupDecodeResult.Rejected).reason)
            .isEqualTo(PortableBackupRejectReason.MALFORMED)
    }

    @Test
    fun `quoted boolean primitive is malformed instead of coerced`() {
        val snapshot = PortableBackupSnapshot(
            createdAtEpochMillis = 1L,
            dataSchemaVersion = 10,
            payload = PortableBackupPayload(
                profiles = listOf(
                    PortableBackupProfile(
                        id = "profile-a",
                        name = "Profile A",
                        isPrimary = true,
                    ),
                ),
                sources = emptyList(),
                channelOverlays = emptyList(),
                recentChannels = emptyList(),
            ),
        )
        val encoded = PortableBackupCodec.encode(snapshot).toString(Charsets.UTF_8)
        val quotedBoolean = encoded.replace(
            "\"isPrimary\":true",
            "\"isPrimary\":\"true\"",
        ).toByteArray(Charsets.UTF_8)

        val decoded = PortableBackupCodec.decode(quotedBoolean)

        assertThat(decoded).isInstanceOf(PortableBackupDecodeResult.Rejected::class.java)
        assertThat((decoded as PortableBackupDecodeResult.Rejected).reason)
            .isEqualTo(PortableBackupRejectReason.MALFORMED)
    }
}
