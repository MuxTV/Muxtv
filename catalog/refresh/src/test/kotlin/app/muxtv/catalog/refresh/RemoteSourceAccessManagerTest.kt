package app.muxtv.catalog.refresh

import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialReadResult
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialResetResult
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.credentials.SecretBytes
import app.muxtv.network.ExactHttpOrigin
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

class RemoteSourceAccessManagerTest {
    @Test
    fun `concurrent updates preserve both playback approvals and unrelated access fields`() = runTest {
        val store = YieldingCredentialStore()
        val manager = RemoteSourceAccessManager(store)
        val original = RemoteSourceAccess(
            url = "http://provider.example/list.m3u?token=source-secret",
            insecureHttpApproved = true,
            userAgent = "Provider Agent",
            sensitiveHeaders = mapOf("Authorization" to "Bearer source-secret"),
        )
        assertThat(manager.save(CREDENTIAL_ID, original)).isEqualTo(CredentialWriteResult.Stored)

        val firstOrigin = origin("http://cdn-a.example:80")
        val secondOrigin = origin("http://cdn-b.example:8080")
        val results = coroutineScope {
            listOf(
                async {
                    manager.update(CREDENTIAL_ID) { access ->
                        access.withApprovedPlaybackOrigin(firstOrigin)
                    }
                },
                async {
                    manager.update(CREDENTIAL_ID) { access ->
                        access.withApprovedPlaybackOrigin(secondOrigin)
                    }
                },
            ).awaitAll()
        }

        assertThat(results).containsExactly(
            RemoteSourceAccessUpdateResult.Updated,
            RemoteSourceAccessUpdateResult.Updated,
        )
        val updated = (manager.read(CREDENTIAL_ID) as RemoteSourceAccessReadResult.Found).access
        assertThat(updated.approvedPlaybackOrigins).containsAtLeast(firstOrigin, secondOrigin)
        assertThat(updated.url).isEqualTo(original.url)
        assertThat(updated.userAgent).isEqualTo(original.userAgent)
        assertThat(updated.sensitiveHeaders).containsExactlyEntriesIn(original.sensitiveHeaders)
    }

    private fun origin(encoded: String): ExactHttpOrigin =
        requireNotNull(ExactHttpOrigin.parse(encoded))

    private class YieldingCredentialStore : CredentialStore {
        private val records = mutableMapOf<CredentialId, ByteArray>()

        override suspend fun put(
            id: CredentialId,
            secret: SecretBytes,
        ): CredentialWriteResult {
            yield()
            records[id] = secret.copyBytes()
            yield()
            return CredentialWriteResult.Stored
        }

        override suspend fun read(id: CredentialId): CredentialReadResult {
            yield()
            val bytes = records[id] ?: return CredentialReadResult.NotFound
            yield()
            return CredentialReadResult.Found(SecretBytes.copyOf(bytes))
        }

        override suspend fun remove(id: CredentialId): CredentialRemoveResult =
            if (records.remove(id) != null) {
                CredentialRemoveResult.Removed
            } else {
                CredentialRemoveResult.NotFound
            }

        override suspend fun reset(): CredentialResetResult {
            records.clear()
            return CredentialResetResult.Reset
        }
    }

    private companion object {
        val CREDENTIAL_ID: CredentialId = CredentialId.parse(
            "00000000-0000-0000-0000-000000000042",
        )
    }
}
