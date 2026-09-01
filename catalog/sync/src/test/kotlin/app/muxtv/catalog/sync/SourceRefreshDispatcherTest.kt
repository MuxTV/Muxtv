package app.muxtv.catalog.sync

import app.muxtv.catalog.refresh.RemoteSourceRefreshRequest
import app.muxtv.catalog.refresh.RemoteSourceRefreshResult
import app.muxtv.catalog.refresh.SourceAccessReference
import app.muxtv.catalog.refresh.XtreamLiveRefreshRequest
import app.muxtv.catalog.refresh.XtreamLiveRefreshResult
import app.muxtv.credentials.CredentialId
import app.muxtv.database.SourceRefreshRunState
import app.muxtv.database.SourceRefreshTarget
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SourceRefreshDispatcherTest {
    @Test
    fun `legacy bare credential reference still dispatches to M3U refresher`() = runBlocking {
        val credentialId = CredentialId.parse("00000000-0000-0000-0000-000000000301")
        val m3uRequests = mutableListOf<RemoteSourceRefreshRequest>()
        var xtreamCalls = 0
        val dispatcher = SourceRefreshDispatcher(
            m3uRefresh = { request ->
                m3uRequests += request
                RemoteSourceRefreshResult.LocalNetworkAccessRequired
            },
            xtreamRefresh = {
                xtreamCalls += 1
                error("Legacy M3U reference must not reach Xtream refresher")
            },
        )

        val decision = dispatcher.refresh(
            target = SourceRefreshTarget(
                sourceId = "source-m3u",
                sourceName = "M3U",
                credentialRef = credentialId.value,
            ),
            runToken = "run-m3u",
        )

        assertThat(m3uRequests).hasSize(1)
        assertThat(m3uRequests.single().accessCredentialId).isEqualTo(credentialId)
        assertThat(m3uRequests.single().refreshRunToken).isEqualTo("run-m3u")
        assertThat(xtreamCalls).isEqualTo(0)
        assertThat(decision.resultFamily).isEqualTo("LOCAL_NETWORK")
    }

    @Test
    fun `typed Xtream reference dispatches to Xtream refresher with exact reference`() = runBlocking {
        val credentialId = CredentialId.parse("00000000-0000-0000-0000-000000000302")
        val reference = SourceAccessReference.xtream(credentialId)
        var m3uCalls = 0
        val xtreamRequests = mutableListOf<XtreamLiveRefreshRequest>()
        val dispatcher = SourceRefreshDispatcher(
            m3uRefresh = {
                m3uCalls += 1
                error("Xtream reference must not reach M3U refresher")
            },
            xtreamRefresh = { request ->
                xtreamRequests += request
                XtreamLiveRefreshResult.AuthenticationRejected
            },
        )

        val decision = dispatcher.refresh(
            target = SourceRefreshTarget(
                sourceId = "source-xtream",
                sourceName = "Xtream",
                credentialRef = reference.value,
            ),
            runToken = "run-xtream",
        )

        assertThat(m3uCalls).isEqualTo(0)
        assertThat(xtreamRequests).hasSize(1)
        val request = xtreamRequests.single()
        assertThat(request.accessCredentialId).isEqualTo(credentialId)
        assertThat(request.accessReference.value).isEqualTo(reference.value)
        assertThat(request.refreshRunToken).isEqualTo("run-xtream")
        assertThat(decision.state).isEqualTo(SourceRefreshRunState.NEEDS_AUTH)
        assertThat(decision.resultCode).isEqualTo("AUTHENTICATION_REJECTED")
    }

    @Test
    fun `invalid typed reference is rejected before either refresher`() = runBlocking {
        var calls = 0
        val dispatcher = SourceRefreshDispatcher(
            m3uRefresh = {
                calls += 1
                error("Invalid reference must not reach refresher")
            },
            xtreamRefresh = {
                calls += 1
                error("Invalid reference must not reach refresher")
            },
        )

        val decision = dispatcher.refresh(
            target = SourceRefreshTarget(
                sourceId = "source-invalid",
                sourceName = "Invalid",
                credentialRef = "muxtv-access:v1:unknown:00000000-0000-0000-0000-000000000303",
            ),
            runToken = "run-invalid",
        )

        assertThat(calls).isEqualTo(0)
        assertThat(decision.state).isEqualTo(SourceRefreshRunState.NEEDS_AUTH)
        assertThat(decision.resultCode).isEqualTo("INVALID_REFERENCE")
    }
}
