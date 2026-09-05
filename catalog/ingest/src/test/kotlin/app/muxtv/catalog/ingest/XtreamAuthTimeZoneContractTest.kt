package app.muxtv.catalog.ingest

import com.google.common.truth.Truth.assertThat
import java.nio.charset.StandardCharsets
import org.junit.Test

class XtreamAuthTimeZoneContractTest {
    @Test
    fun `authenticated response exposes server timezone without diagnostic value leakage`() {
        val result = StreamingXtreamParser().parseAuth(
            AUTH_WITH_TIMEZONE.byteInputStream(StandardCharsets.UTF_8),
        )

        assertThat(result).isInstanceOf(XtreamAuthResult.Authenticated::class.java)
        val authenticated = result as XtreamAuthResult.Authenticated
        assertThat(authenticated.serverTimeZoneId).isEqualTo("Europe/Stockholm")
        assertThat(authenticated.toString()).doesNotContain("Europe/Stockholm")
    }

    @Test
    fun `authenticated response without server info keeps timezone absent`() {
        val result = StreamingXtreamParser().parseAuth(
            AUTH_WITHOUT_SERVER_INFO.byteInputStream(StandardCharsets.UTF_8),
        ) as XtreamAuthResult.Authenticated

        assertThat(result.serverTimeZoneId).isNull()
    }

    private companion object {
        const val AUTH_WITH_TIMEZONE =
            "{\"user_info\":{\"auth\":1,\"status\":\"Active\"}," +
                "\"server_info\":{\"timezone\":\"Europe/Stockholm\"}}"
        const val AUTH_WITHOUT_SERVER_INFO =
            "{\"user_info\":{\"auth\":1,\"status\":\"Active\"}}"
    }
}
