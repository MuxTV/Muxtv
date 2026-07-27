package app.muxtv.catalog.refresh

import app.muxtv.catalog.PlaybackAccessDecision
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialReadResult
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialResetResult
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.credentials.SecretBytes
import app.muxtv.network.ExactHttpOrigin
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class EncryptedPlaybackAccessPolicyResolverTest {
    @Test
    fun `HTTPS resolves securely without reading a credential`() = runTest {
        val store = RecordingCredentialStore()
        val resolver = EncryptedPlaybackAccessPolicyResolver(store)

        val result = resolver.resolve(
            credentialRef = CREDENTIAL_ID.value,
            playbackLocator = "https://secure.example/live.m3u8?token=secret",
        )

        assertThat(result).isEqualTo(PlaybackAccessDecision.SecureTransport)
        assertThat(store.readCount.get()).isEqualTo(0)
    }

    @Test
    fun `exact approved HTTP origin resolves approved`() = runTest {
        val store = RecordingCredentialStore()
        storeAccess(
            store,
            RemoteSourceAccess(
                url = "http://provider.example/list.m3u",
                insecureHttpApproved = true,
            ),
        )
        val resolver = EncryptedPlaybackAccessPolicyResolver(store)

        val result = resolver.resolve(
            credentialRef = CREDENTIAL_ID.value,
            playbackLocator = "http://provider.example/live.m3u8?token=secret",
        )

        assertThat(result).isEqualTo(PlaybackAccessDecision.Approved)
    }

    @Test
    fun `different host or port requires a fresh exact-origin approval`() = runTest {
        val store = RecordingCredentialStore()
        storeAccess(
            store,
            RemoteSourceAccess(
                url = "http://provider.example/list.m3u",
                insecureHttpApproved = true,
            ),
        )
        val resolver = EncryptedPlaybackAccessPolicyResolver(store)

        val differentHost = resolver.resolve(
            credentialRef = CREDENTIAL_ID.value,
            playbackLocator = "http://cdn.example/live.m3u8?token=host-secret",
        )
        val differentPort = resolver.resolve(
            credentialRef = CREDENTIAL_ID.value,
            playbackLocator = "http://provider.example:8080/live.m3u8?token=port-secret",
        )

        assertThat(differentHost).isEqualTo(
            PlaybackAccessDecision.ApprovalRequired("http://cdn.example:80"),
        )
        assertThat(differentPort).isEqualTo(
            PlaybackAccessDecision.ApprovalRequired("http://provider.example:8080"),
        )
        assertThat(differentHost.toString()).doesNotContain("host-secret")
        assertThat(differentPort.toString()).doesNotContain("port-secret")
    }

    @Test
    fun `invalid locator and credential states map to safe typed outcomes`() = runTest {
        val store = RecordingCredentialStore()
        val resolver = EncryptedPlaybackAccessPolicyResolver(store)

        assertThat(resolver.resolve(CREDENTIAL_ID.value, "not a url"))
            .isEqualTo(PlaybackAccessDecision.InvalidLocator)
        assertThat(resolver.resolve("not-a-credential", "http://provider.example/live"))
            .isEqualTo(PlaybackAccessDecision.CredentialNotFound)
        assertThat(resolver.resolve(CREDENTIAL_ID.value, "http://provider.example/live"))
            .isEqualTo(PlaybackAccessDecision.CredentialNotFound)

        store.rawRecords[CREDENTIAL_ID] = byteArrayOf(1, 2, 3)
        assertThat(resolver.resolve(CREDENTIAL_ID.value, "http://provider.example/live"))
            .isEqualTo(PlaybackAccessDecision.CredentialCorrupted)

        store.unavailable = true
        assertThat(resolver.resolve(CREDENTIAL_ID.value, "http://provider.example/live"))
            .isEqualTo(PlaybackAccessDecision.CredentialUnavailable)
    }

    @Test
    fun `approve revoke and revokeAll update only encrypted playback origins`() = runTest {
        val store = RecordingCredentialStore()
        val original = RemoteSourceAccess(
            url = "http://provider.example/list.m3u?source=secret",
            insecureHttpApproved = true,
            userAgent = "Secret Agent",
            sensitiveHeaders = mapOf("Authorization" to "Bearer secret"),
        )
        storeAccess(store, original)
        val resolver = EncryptedPlaybackAccessPolicyResolver(store)
        val locator = "http://cdn.example:8080/live.m3u8?token=secret"

        assertThat(resolver.approve(CREDENTIAL_ID.value, locator))
            .isEqualTo(PlaybackAccessMutationResult.Applied)
        assertThat(resolver.approve(CREDENTIAL_ID.value, locator))
            .isEqualTo(PlaybackAccessMutationResult.Unchanged)
        assertThat(resolver.resolve(CREDENTIAL_ID.value, locator))
            .isEqualTo(PlaybackAccessDecision.Approved)

        var decoded = readAccess(store)
        assertThat(decoded.url).isEqualTo(original.url)
        assertThat(decoded.userAgent).isEqualTo("Secret Agent")
        assertThat(decoded.sensitiveHeaders).containsEntry("Authorization", "Bearer secret")
        assertThat(decoded.approvedPlaybackOrigins).contains(origin("http://cdn.example:8080"))

        assertThat(resolver.revoke(CREDENTIAL_ID.value, locator))
            .isEqualTo(PlaybackAccessMutationResult.Applied)
        assertThat(resolver.revoke(CREDENTIAL_ID.value, locator))
            .isEqualTo(PlaybackAccessMutationResult.Unchanged)
        assertThat(resolver.resolve(CREDENTIAL_ID.value, locator))
            .isEqualTo(PlaybackAccessDecision.ApprovalRequired("http://cdn.example:8080"))

        resolver.approve(CREDENTIAL_ID.value, locator)
        assertThat(resolver.revokeAll(CREDENTIAL_ID.value))
            .isEqualTo(PlaybackAccessMutationResult.Applied)
        decoded = readAccess(store)
        assertThat(decoded.approvedPlaybackOrigins).isEmpty()
        assertThat(decoded.insecureHttpApproved).isTrue()
    }

    @Test
    fun `approval capacity and credential write failures map safely`() = runTest {
        val store = RecordingCredentialStore()
        val origins = (1..RemoteSourceAccess.MAX_APPROVED_PLAYBACK_ORIGINS)
            .map { index -> origin("http://cdn-$index.example:80") }
            .toSet()
        storeAccess(
            store,
            RemoteSourceAccess(
                url = "https://provider.example/list.m3u",
                approvedPlaybackOrigins = origins,
            ),
        )
        val resolver = EncryptedPlaybackAccessPolicyResolver(store)

        assertThat(
            resolver.approve(
                CREDENTIAL_ID.value,
                "http://overflow.example/live.m3u8",
            ),
        ).isEqualTo(PlaybackAccessMutationResult.CapacityExceeded)

        store.rejectWrites = true
        storeAccess(
            store,
            RemoteSourceAccess(url = "https://provider.example/list.m3u"),
            direct = true,
        )
        assertThat(resolver.approve(CREDENTIAL_ID.value, "http://cdn.example/live"))
            .isEqualTo(PlaybackAccessMutationResult.Unavailable)
    }

    @Test
    fun `parent cancellation is never converted to an access result`() = runTest {
        val store = RecordingCredentialStore().apply { cancelReads = true }
        val resolver = EncryptedPlaybackAccessPolicyResolver(store)

        assertThrows(CancellationException::class.java) {
            runTest {
                resolver.resolve(CREDENTIAL_ID.value, "http://provider.example/live")
            }
        }
    }

    private suspend fun storeAccess(
        store: RecordingCredentialStore,
        access: RemoteSourceAccess,
        direct: Boolean = false,
    ) {
        val secret = RemoteSourceAccessCodec.encode(access)
        secret.use {
            if (direct) {
                store.rawRecords[CREDENTIAL_ID] = it.copyBytes()
            } else {
                assertThat(store.put(CREDENTIAL_ID, it)).isEqualTo(CredentialWriteResult.Stored)
            }
        }
    }

    private fun readAccess(store: RecordingCredentialStore): RemoteSourceAccess =
        RemoteSourceAccessCodec.decode(
            SecretBytes.copyOf(requireNotNull(store.rawRecords[CREDENTIAL_ID])),
        )

    private fun origin(encoded: String): ExactHttpOrigin =
        requireNotNull(ExactHttpOrigin.parse(encoded))

    private class RecordingCredentialStore : CredentialStore {
        val rawRecords = mutableMapOf<CredentialId, ByteArray>()
        val readCount = AtomicInteger()
        var unavailable = false
        var rejectWrites = false
        var cancelReads = false

        override suspend fun put(
            id: CredentialId,
            secret: SecretBytes,
        ): CredentialWriteResult {
            if (rejectWrites) return CredentialWriteResult.Unavailable(
                app.muxtv.credentials.CredentialUnavailableReason.IoFailure,
            )
            rawRecords[id] = secret.copyBytes()
            return CredentialWriteResult.Stored
        }

        override suspend fun read(id: CredentialId): CredentialReadResult {
            readCount.incrementAndGet()
            if (cancelReads) throw CancellationException("synthetic cancellation")
            if (unavailable) return CredentialReadResult.Unavailable(
                app.muxtv.credentials.CredentialUnavailableReason.IoFailure,
            )
            val bytes = rawRecords[id] ?: return CredentialReadResult.NotFound
            return CredentialReadResult.Found(SecretBytes.copyOf(bytes))
        }

        override suspend fun remove(id: CredentialId): CredentialRemoveResult =
            if (rawRecords.remove(id) != null) {
                CredentialRemoveResult.Removed
            } else {
                CredentialRemoveResult.NotFound
            }

        override suspend fun reset(): CredentialResetResult {
            rawRecords.clear()
            return CredentialResetResult.Reset
        }
    }

    private companion object {
        val CREDENTIAL_ID: CredentialId = CredentialId.parse(
            "00000000-0000-0000-0000-000000000039",
        )
    }
}
