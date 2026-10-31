package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Structural security gate for the separate LC04 socket module. */
class LocalControlNativeTcpIsolationTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test fun `native transport is separate from legacy LAN bearer and source modules`() {
        val gradle = root.resolve("local-control/native-transport/build.gradle.kts").readText()
        assertContains(gradle, """project(":local-control:tv-qr")""")
        assertContains(gradle, """project(":local-control:enrollment")""")
        assertContains(gradle, """project(":local-control:wire-lab")""")
        // The QR value is a test fixture, not an additional production authority.
        assertContains(gradle, """testImplementation(project(":local-control:qr-public"))""")
        assertContains(gradle, """testImplementation(project(":local-control:phone-flow"))""")
        assertFalse(gradle.contains("""implementation(project(":local-control:phone-flow"))"""),
            "No phone proof dependency in production TV transport")
        assertFalse(gradle.contains("""implementation(project(":local-control:qr-public"))"""))
        for (forbidden in listOf(
            ":local-control:server", ":local-control:onboarding",
            ":catalog:", ":core:credentials", ":local-control:core",
            "libs.ktor", "libs.okhttp",
        )) assertFalse(gradle.contains(forbidden), "Legacy or source dependency: $forbidden")

        val source = root.resolve(
            "local-control/native-transport/src/main/kotlin/app/muxtv/localcontrol/nativetransport/Lc04NativeTcpTransport.kt",
        ).readText()
        assertContains(source, "Lc04PrivateIpv4.parse")
        assertContains(source, "Lc04LengthPrefix.read")
        assertContains(source, "gate.beginPublicOffer(bytes)")
        assertContains(source, "gate.verifyPublicProof(bytes)")
        assertContains(source, "tv.acceptProtectedProbe(bytes, now)")
        assertContains(source, "tv.close()")
        for (forbidden in listOf(
            "HttpClient", "TvLanServer", "PairingAuthority", "PairingGrant",
            "SourceOnboarding", "SourcePreparationHandle",
            "PairingCapability.SOURCE_CREATE", "post(", "route(",
            "getPassword(", "getUsername(",
        )) assertFalse(source.contains(forbidden), "Forbidden transport authority: $forbidden")
        assertFalse(source.contains(".approveOnTv("),
            "Network request must not invoke TV-local approval")
    }

    @Test fun `legacy LAN pairing and unpermissioned phone remain isolated`() {
        val legacy = root.resolve("local-control/server/src/main")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(legacy.isNotEmpty())
        for (file in legacy) {
            val code = file.readText()
            assertFalse(code.contains("nativetransport"), file.path)
            assertFalse(code.contains("Lc04TvTcpServer"), file.path)
        }
        val manifest = root.resolve("app/phone/src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("""<uses-permission android:name="android.permission.INTERNET" />"""))
        val tvGradle = root.resolve("app/tv/build.gradle.kts").readText()
        val phoneGradle = root.resolve("app/phone/build.gradle.kts").readText()
        assertFalse(tvGradle.contains(":local-control:native-transport"))
        assertFalse(phoneGradle.contains(":local-control:native-transport"))
    }

    @Test fun `native TCP hosted test workflow pins HEAD and never self-hosts`() {
        val flow = root.resolve(".github/workflows/local-control-native-tcp-ci.yml").readText()
        assertContains(flow, "runs-on: ubuntu-latest")
        assertContains(flow, "test \"\$(git rev-parse HEAD)\" = \"\$EXPECTED_SHA\"")
        assertContains(flow, ":local-control:native-transport:test")
        assertContains(flow, ":local-control:phone-flow:test")
        assertContains(flow, "'local-control/phone-flow/**'")
        val e2e = root.resolve(
            "local-control/native-transport/src/test/kotlin/app/muxtv/localcontrol/nativetransport/Lc04NativePhoneProofEndToEndTest.kt",
        ).readText()
        for (required in listOf("Lc04TcpPhoneExchange.loopbackForTests",
            "Lc04PhoneProofFlow(", "finishAfterTvAction()", "approveOnTv()",
            "hasAuthenticatedReceipt()", "f.server.close()", "wrongListener.close()")) {
            assertContains(e2e, required)
        }
        assertFalse(flow.contains("self-hosted"))
    }
}
