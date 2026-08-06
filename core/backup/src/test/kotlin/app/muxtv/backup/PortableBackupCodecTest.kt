package app.muxtv.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PortableBackupCodecTest {
    @Test
    fun `canonical encode is deterministic and round trips portable state`() {
        val snapshot = safeSnapshot()

        val first = PortableBackupCodec.encode(snapshot)
        val second = PortableBackupCodec.encode(snapshot)

        assertThat(second).isEqualTo(first)
        val decoded = PortableBackupCodec.decode(first)
        assertThat(decoded).isInstanceOf(PortableBackupDecodeResult.Success::class.java)
        val success = decoded as PortableBackupDecodeResult.Success
        assertThat(success.document.snapshot).isEqualTo(snapshot)
        assertThat(success.document.snapshot.payload.sources)
            .containsExactly(
                PortableBackupSource(
                    id = "source-a",
                    name = "Source A",
                    recoveryState = PortableSourceRecoveryState.REAUTH_REQUIRED,
                ),
            )
    }

    @Test
    fun `wire format cannot carry credential access or active revision fields`() {
        val encoded = PortableBackupCodec.encode(safeSnapshot()).toString(Charsets.UTF_8)

        listOf(
            "credentialRef",
            "Authorization",
            "Cookie",
            "locator",
            "streamUrl",
            "activeRevision",
            "keystore",
        ).forEach { forbidden ->
            assertThat(encoded).doesNotContain(forbidden)
        }
        assertThat(encoded).contains("REAUTH_REQUIRED")
    }

    @Test
    fun `tampered canonical content with original digest is rejected`() {
        val encoded = PortableBackupCodec.encode(safeSnapshot()).toString(Charsets.UTF_8)
        val tampered = encoded.replace("Profile A", "Profile B").toByteArray(Charsets.UTF_8)

        val decoded = PortableBackupCodec.decode(tampered)

        assertRejected(decoded, PortableBackupRejectReason.INTEGRITY_MISMATCH)
    }

    @Test
    fun `truncated json is rejected as malformed`() {
        val encoded = PortableBackupCodec.encode(safeSnapshot())
        val truncated = encoded.copyOf(encoded.size - 7)

        val decoded = PortableBackupCodec.decode(truncated)

        assertRejected(decoded, PortableBackupRejectReason.MALFORMED)
    }

    @Test
    fun `oversized input is rejected before parsing`() {
        val bytes = ByteArray(PortableBackupLimits.MAX_DOCUMENT_BYTES + 1) { 'x'.code.toByte() }

        val decoded = PortableBackupCodec.decode(bytes)

        assertRejected(decoded, PortableBackupRejectReason.OVERSIZED)
    }

    @Test
    fun `unknown root field fails closed`() {
        val encoded = PortableBackupCodec.encode(safeSnapshot()).toString(Charsets.UTF_8)
        val withUnknownField = encoded.replace(
            "\"formatVersion\":1",
            "\"formatVersion\":1,\"unexpected\":true",
        ).toByteArray(Charsets.UTF_8)

        val decoded = PortableBackupCodec.decode(withUnknownField)

        assertRejected(decoded, PortableBackupRejectReason.UNKNOWN_FIELD)
    }

    @Test
    fun `future format version is rejected before integrity acceptance`() {
        val encoded = PortableBackupCodec.encode(safeSnapshot()).toString(Charsets.UTF_8)
        val future = encoded.replace("\"formatVersion\":1", "\"formatVersion\":2")
            .toByteArray(Charsets.UTF_8)

        val decoded = PortableBackupCodec.decode(future)

        assertRejected(decoded, PortableBackupRejectReason.UNSUPPORTED_VERSION)
    }

    @Test
    fun `payload rejects duplicate profile identity`() {
        val profile = PortableBackupProfile(
            id = "profile-a",
            name = "Profile A",
            isPrimary = true,
        )

        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupPayload(
                profiles = listOf(profile, profile.copy(name = "Duplicate")),
                sources = emptyList(),
                channelOverlays = emptyList(),
                recentChannels = emptyList(),
            )
        }
    }

    @Test
    fun `payload rejects duplicate source identity`() {
        val source = PortableBackupSource(
            id = "source-a",
            name = "Source A",
            recoveryState = PortableSourceRecoveryState.REAUTH_REQUIRED,
        )

        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupPayload(
                profiles = listOf(primaryProfile()),
                sources = listOf(source, source.copy(name = "Duplicate")),
                channelOverlays = emptyList(),
                recentChannels = emptyList(),
            )
        }
    }

    @Test
    fun `overlay and recent rows must reference a profile carried by payload`() {
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupPayload(
                profiles = listOf(primaryProfile()),
                sources = emptyList(),
                channelOverlays = listOf(
                    PortableChannelOverlay(
                        profileId = "missing-profile",
                        canonicalChannelId = "channel-a",
                        isFavorite = true,
                    ),
                ),
                recentChannels = emptyList(),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupPayload(
                profiles = listOf(primaryProfile()),
                sources = emptyList(),
                channelOverlays = emptyList(),
                recentChannels = listOf(
                    PortableRecentChannel(
                        profileId = "missing-profile",
                        canonicalChannelId = "channel-a",
                        lastSuccessfulPlaybackAtEpochMillis = 1L,
                    ),
                ),
            )
        }
    }

    @Test
    fun `recent history is capped at accepted public bound per profile`() {
        val recents = (1..PortableBackupLimits.MAX_RECENT_PER_PROFILE + 1).map { index ->
            PortableRecentChannel(
                profileId = "profile-a",
                canonicalChannelId = "channel-$index",
                lastSuccessfulPlaybackAtEpochMillis = index.toLong(),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupPayload(
                profiles = listOf(primaryProfile()),
                sources = emptyList(),
                channelOverlays = emptyList(),
                recentChannels = recents,
            )
        }
    }

    @Test
    fun `portable values reject unsafe bounds and invalid timestamps`() {
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupProfile(
                id = " profile-a",
                name = "Profile A",
                isPrimary = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupSource(
                id = "source-a",
                name = "x".repeat(PortableBackupLimits.MAX_DISPLAY_NAME_CHARACTERS + 1),
                recoveryState = PortableSourceRecoveryState.REAUTH_REQUIRED,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortableRecentChannel(
                profileId = "profile-a",
                canonicalChannelId = "channel-a",
                lastSuccessfulPlaybackAtEpochMillis = -1L,
            )
        }
    }

    @Test
    fun `diagnostics do not expose portable ids or names`() {
        val snapshot = safeSnapshot()
        val decoded = PortableBackupCodec.decode(PortableBackupCodec.encode(snapshot))
            as PortableBackupDecodeResult.Success
        val diagnostics = listOf(
            snapshot.toString(),
            snapshot.payload.toString(),
            snapshot.payload.profiles.single().toString(),
            snapshot.payload.sources.single().toString(),
            decoded.document.toString(),
        ).joinToString("\n")

        assertThat(diagnostics).doesNotContain("profile-a")
        assertThat(diagnostics).doesNotContain("Profile A")
        assertThat(diagnostics).doesNotContain("source-a")
        assertThat(diagnostics).doesNotContain("Source A")
        assertThat(diagnostics).doesNotContain("channel-a")
    }

    private fun safeSnapshot(): PortableBackupSnapshot = PortableBackupSnapshot(
        createdAtEpochMillis = 1_786_000_000_000L,
        dataSchemaVersion = 10,
        payload = PortableBackupPayload(
            profiles = listOf(primaryProfile()),
            sources = listOf(
                PortableBackupSource(
                    id = "source-a",
                    name = "Source A",
                    recoveryState = PortableSourceRecoveryState.REAUTH_REQUIRED,
                ),
            ),
            channelOverlays = listOf(
                PortableChannelOverlay(
                    profileId = "profile-a",
                    canonicalChannelId = "channel-a",
                    isFavorite = true,
                    customName = "News",
                    channelNumber = 7,
                    isHidden = false,
                ),
            ),
            recentChannels = listOf(
                PortableRecentChannel(
                    profileId = "profile-a",
                    canonicalChannelId = "channel-a",
                    lastSuccessfulPlaybackAtEpochMillis = 1_785_999_000_000L,
                ),
            ),
        ),
    )

    private fun primaryProfile(): PortableBackupProfile = PortableBackupProfile(
        id = "profile-a",
        name = "Profile A",
        isPrimary = true,
        archivedAtEpochMillis = null,
    )

    private fun assertRejected(
        result: PortableBackupDecodeResult,
        reason: PortableBackupRejectReason,
    ) {
        assertThat(result).isInstanceOf(PortableBackupDecodeResult.Rejected::class.java)
        assertThat((result as PortableBackupDecodeResult.Rejected).reason).isEqualTo(reason)
    }
}
