package app.muxtv.catalog.refresh

import app.muxtv.catalog.PlaybackReferenceResolution
import app.muxtv.catalog.PlaybackReferenceUnavailableReason
import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialReadResult
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialResetResult
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.credentials.SecretBytes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class XtreamPlaybackReferenceResolverContractTest {
    @Test
    fun `direct http and https locators pass through unchanged without reading credentials`() = runTest {
        val store = PlaybackReferenceCredentialStore()
        val resolver = XtreamPlaybackReferenceResolver(XtreamSourceAccessManager(store))

        listOf(
            "https://cdn.example/live/channel.m3u8",
            "http://192.0.2.10/live.ts",
        ).forEach { locator ->
            val result = resolver.resolve(CREDENTIAL_ID.value, locator)
            assertThat(result).isInstanceOf(PlaybackReferenceResolution.Ready::class.java)
            assertThat((result as PlaybackReferenceResolution.Ready).locator).isEqualTo(locator)
        }
        assertThat(store.readCount).isEqualTo(0)
    }

    @Test
    fun `opaque Xtream live reference resolves to ephemeral credential bearing transport only`() = runTest {
        val store = PlaybackReferenceCredentialStore()
        val manager = XtreamSourceAccessManager(store)
        assertThat(
            manager.save(
                CREDENTIAL_ID,
                XtreamSourceAccess(
                    baseUrl = "https://provider.example/root/",
                    username = USERNAME,
                    password = PASSWORD,
                ),
            ),
        ).isEqualTo(CredentialWriteResult.Stored)
        val resolver = XtreamPlaybackReferenceResolver(manager)

        val result = resolver.resolve(CREDENTIAL_ID.value, "muxtv-provider://xtream/live/707")

        assertThat(result).isInstanceOf(PlaybackReferenceResolution.Ready::class.java)
        val ready = result as PlaybackReferenceResolution.Ready
        assertThat(ready.locator).isEqualTo(
            "https://provider.example/root/live/TEST_USER_236/TEST_PASS_236/707.ts",
        )
        assertThat(ready.toString()).doesNotContain(USERNAME)
        assertThat(ready.toString()).doesNotContain(PASSWORD)
        assertThat(ready.toString()).doesNotContain("provider.example")
    }

    @Test
    fun `provider reference grammar is strict and bounded`() = runTest {
        val resolver = XtreamPlaybackReferenceResolver(
            XtreamSourceAccessManager(PlaybackReferenceCredentialStore()),
        )
        val invalid = listOf(
            "muxtv-provider://xtream/live/",
            "muxtv-provider://xtream/live/0",
            "muxtv-provider://xtream/live/-1",
            "muxtv-provider://xtream/live/707/extra",
            "muxtv-provider://xtream/vod/707",
            "muxtv-provider://other/live/707",
            "muxtv-provider://xtream/live/${"9".repeat(32)}",
        )

        invalid.forEach { reference ->
            assertThat(resolver.resolve(CREDENTIAL_ID.value, reference)).isEqualTo(
                PlaybackReferenceResolution.Unavailable(
                    PlaybackReferenceUnavailableReason.InvalidReference,
                ),
            )
        }
    }

    @Test
    fun `missing malformed and unavailable Xtream credentials stay typed and redacted`() = runTest {
        val notFound = XtreamPlaybackReferenceResolver(
            XtreamSourceAccessManager(PlaybackReferenceCredentialStore()),
        ).resolve(CREDENTIAL_ID.value, OPAQUE)
        assertThat(notFound).isEqualTo(
            PlaybackReferenceResolution.Unavailable(
                PlaybackReferenceUnavailableReason.CredentialNotFound,
            ),
        )

        val malformedStore = PlaybackReferenceCredentialStore(
            initial = mapOf(CREDENTIAL_ID to byteArrayOf(0x01, 0x02, 0x03)),
        )
        val malformed = XtreamPlaybackReferenceResolver(
            XtreamSourceAccessManager(malformedStore),
        ).resolve(CREDENTIAL_ID.value, OPAQUE)
        assertThat(malformed).isEqualTo(
            PlaybackReferenceResolution.Unavailable(
                PlaybackReferenceUnavailableReason.CredentialCorrupted,
            ),
        )

        val unavailable = XtreamPlaybackReferenceResolver(
            XtreamSourceAccessManager(PlaybackReferenceCredentialStore(unavailable = true)),
        ).resolve(CREDENTIAL_ID.value, OPAQUE)
        assertThat(unavailable).isEqualTo(
            PlaybackReferenceResolution.Unavailable(
                PlaybackReferenceUnavailableReason.CredentialUnavailable,
            ),
        )

        val diagnostics = "$notFound $malformed $unavailable"
        assertThat(diagnostics).doesNotContain(CREDENTIAL_ID.value)
        assertThat(diagnostics).doesNotContain(USERNAME)
        assertThat(diagnostics).doesNotContain(PASSWORD)
    }

    @Test
    fun `cancellation from encrypted credential read remains terminal`() = runTest {
        val resolver = XtreamPlaybackReferenceResolver(
            XtreamSourceAccessManager(PlaybackReferenceCredentialStore(cancelOnRead = true)),
        )

        val error = try {
            resolver.resolve(CREDENTIAL_ID.value, OPAQUE)
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }

        assertThat(error).isNotNull()
    }

    private companion object {
        val CREDENTIAL_ID: CredentialId = CredentialId.parse(
            "00000000-0000-0000-0000-000000000236",
        )
        const val OPAQUE = "muxtv-provider://xtream/live/707"
        const val USERNAME = "TEST_USER_236"
        const val PASSWORD = "TEST_PASS_236"
    }
}

private class PlaybackReferenceCredentialStore(
    initial: Map<CredentialId, ByteArray> = emptyMap(),
    private val unavailable: Boolean = false,
    private val cancelOnRead: Boolean = false,
) : CredentialStore {
    private val records = initial.mapValues { (_, bytes) -> bytes.copyOf() }.toMutableMap()
    var readCount: Int = 0
        private set

    override suspend fun put(id: CredentialId, secret: SecretBytes): CredentialWriteResult {
        records[id] = secret.copyBytes()
        return CredentialWriteResult.Stored
    }

    override suspend fun read(id: CredentialId): CredentialReadResult {
        readCount += 1
        if (cancelOnRead) throw CancellationException("synthetic cancellation")
        if (unavailable) {
            return CredentialReadResult.Unavailable(
                app.muxtv.credentials.CredentialUnavailableReason.IoFailure,
            )
        }
        val bytes = records[id] ?: return CredentialReadResult.NotFound
        return CredentialReadResult.Found(SecretBytes.copyOf(bytes))
    }

    override suspend fun remove(id: CredentialId): CredentialRemoveResult =
        if (records.remove(id) != null) CredentialRemoveResult.Removed else CredentialRemoveResult.NotFound

    override suspend fun reset(): CredentialResetResult {
        records.values.forEach { it.fill(0) }
        records.clear()
        return CredentialResetResult.Reset
    }
}
