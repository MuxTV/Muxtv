package app.muxtv.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderRequestMetadataTest {
    @Test
    fun `header policy canonicalizes accepted aliases and case variants`() {
        assertThat(ProviderRequestHeaderPolicy.canonicalize("user-agent"))
            .isEqualTo(ProviderRequestHeaderDecision.Accepted("User-Agent"))
        assertThat(ProviderRequestHeaderPolicy.canonicalize("REFERRER"))
            .isEqualTo(ProviderRequestHeaderDecision.Accepted("Referer"))
        assertThat(ProviderRequestHeaderPolicy.canonicalize("origin"))
            .isEqualTo(ProviderRequestHeaderDecision.Accepted("Origin"))
        assertThat(ProviderRequestHeaderPolicy.canonicalize("AUTHORIZATION"))
            .isEqualTo(ProviderRequestHeaderDecision.Accepted("Authorization"))
        assertThat(ProviderRequestHeaderPolicy.canonicalize("cookie"))
            .isEqualTo(ProviderRequestHeaderDecision.Accepted("Cookie"))
        assertThat(ProviderRequestHeaderPolicy.canonicalize("x-api-key"))
            .isEqualTo(ProviderRequestHeaderDecision.Accepted("X-Api-Key"))
        assertThat(ProviderRequestHeaderPolicy.canonicalize("x-auth-token"))
            .isEqualTo(ProviderRequestHeaderDecision.Accepted("X-Auth-Token"))
        assertThat(ProviderRequestHeaderPolicy.canonicalize("x-access-token"))
            .isEqualTo(ProviderRequestHeaderDecision.Accepted("X-Access-Token"))
    }

    @Test
    fun `header policy rejects transport owned unsupported and malformed names`() {
        assertThat(ProviderRequestHeaderPolicy.canonicalize("Host"))
            .isEqualTo(ProviderRequestHeaderDecision.Forbidden)
        assertThat(ProviderRequestHeaderPolicy.canonicalize("Range"))
            .isEqualTo(ProviderRequestHeaderDecision.Forbidden)
        assertThat(ProviderRequestHeaderPolicy.canonicalize("Proxy-Authorization"))
            .isEqualTo(ProviderRequestHeaderDecision.Forbidden)
        assertThat(ProviderRequestHeaderPolicy.canonicalize("X-Unbounded-Custom"))
            .isEqualTo(ProviderRequestHeaderDecision.Unsupported)
        assertThat(ProviderRequestHeaderPolicy.canonicalize("bad:name"))
            .isEqualTo(ProviderRequestHeaderDecision.Malformed)
        assertThat(ProviderRequestHeaderPolicy.canonicalize("bad\nname"))
            .isEqualTo(ProviderRequestHeaderDecision.Malformed)
        assertThat(ProviderRequestHeaderPolicy.canonicalize(""))
            .isEqualTo(ProviderRequestHeaderDecision.Malformed)
    }

    @Test
    fun `metadata snapshots caller maps canonicalizes duplicates and scopes targets`() {
        val mutableDefault = linkedMapOf(
            "user-agent" to "Default-A",
            "USER-AGENT" to "Default-B",
            "referer" to "https://portal.invalid/",
        )
        val metadata = ProviderRequestMetadata(
            defaultHeaders = mutableDefault,
            manifestHeaders = mapOf("User-Agent" to "Manifest-Agent"),
            segmentHeaders = mapOf(
                "User-Agent" to "Segment-Agent",
                "Origin" to "https://portal.invalid",
            ),
        )

        mutableDefault["USER-AGENT"] = "Mutated"
        mutableDefault["Cookie"] = "late=mutation"

        assertThat(metadata.defaultHeaders).containsExactly(
            "User-Agent", "Default-B",
            "Referer", "https://portal.invalid/",
        ).inOrder()
        assertThat(metadata.headersFor(ProviderRequestTarget.MANIFEST)).containsExactly(
            "User-Agent", "Manifest-Agent",
            "Referer", "https://portal.invalid/",
        )
        assertThat(metadata.headersFor(ProviderRequestTarget.SEGMENT)).containsExactly(
            "User-Agent", "Segment-Agent",
            "Referer", "https://portal.invalid/",
            "Origin", "https://portal.invalid",
        )
    }

    @Test
    fun `metadata rejects unsupported forbidden malformed and unsafe values`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderRequestMetadata(defaultHeaders = mapOf("X-Unbounded-Custom" to "value"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderRequestMetadata(defaultHeaders = mapOf("Host" to "streams.invalid"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderRequestMetadata(defaultHeaders = mapOf("User-Agent" to "line1\nline2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderRequestMetadata(defaultHeaders = mapOf("Cookie" to "nul\u0000value"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderRequestMetadata(defaultHeaders = mapOf("User-Agent" to "x".repeat(8_193)))
        }
        assertThat(ProviderRequestHeaderPolicy.canonicalize("x".repeat(65)))
            .isEqualTo(ProviderRequestHeaderDecision.Malformed)
    }

    @Test
    fun `empty metadata is stable and target projections are empty`() {
        assertThat(ProviderRequestMetadata.EMPTY.defaultHeaders).isEmpty()
        assertThat(ProviderRequestMetadata.EMPTY.headersFor(ProviderRequestTarget.MANIFEST)).isEmpty()
        assertThat(ProviderRequestMetadata.EMPTY.headersFor(ProviderRequestTarget.SEGMENT)).isEmpty()
    }

    @Test
    fun `toString exposes counts but never header names or values`() {
        val secret = "TEST_C21_MODEL_SECRET"
        val metadata = ProviderRequestMetadata(
            defaultHeaders = mapOf(
                "Authorization" to "Bearer $secret",
                "Referer" to "https://portal.invalid/$secret/",
            ),
            manifestHeaders = mapOf("User-Agent" to "Agent-$secret"),
            segmentHeaders = mapOf("Origin" to "https://$secret.invalid"),
        )

        val diagnostic = metadata.toString()
        assertThat(diagnostic).contains("defaultHeaderCount=2")
        assertThat(diagnostic).contains("manifestHeaderCount=1")
        assertThat(diagnostic).contains("segmentHeaderCount=1")
        assertThat(diagnostic).doesNotContain(secret)
        assertThat(diagnostic).doesNotContain("Authorization")
        assertThat(diagnostic).doesNotContain("Cookie")
        assertThat(diagnostic).doesNotContain("Referer")
    }
}
